package cn.wpgd.app.session

import cn.wpgd.app.core.match.MatchMemberView
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchProjectionCodec
import cn.wpgd.app.core.match.MatchProjector
import cn.wpgd.app.core.rules.LegalActionGenerator
import cn.wpgd.app.core.rules.MatchOptions
import cn.wpgd.app.core.rules.RuleProfile
import cn.wpgd.app.core.rules.RuleProfileLoader
import cn.wpgd.app.core.rules.RuleSettings
import cn.wpgd.app.core.state.RandomSource
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.StandardDeck
import cn.wpgd.app.session.client.ClientConfig
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.client.SessionClient
import cn.wpgd.app.session.crypto.Aead
import cn.wpgd.app.session.crypto.CryptoSuite
import cn.wpgd.app.session.crypto.JcaAesGcm
import cn.wpgd.app.session.crypto.JcaHmacSha256
import cn.wpgd.app.session.crypto.JcaP256KeyAgreement
import cn.wpgd.app.session.crypto.SecureBytes
import cn.wpgd.app.session.host.AbsencePolicy
import cn.wpgd.app.session.host.HostConfig
import cn.wpgd.app.session.host.RecoveryPolicy
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.transport.InMemoryLink
import cn.wpgd.app.session.transport.InMemoryNetwork
import cn.wpgd.app.session.transport.Cancellable
import cn.wpgd.app.session.transport.LinkFaults
import cn.wpgd.app.session.transport.Scheduler
import cn.wpgd.app.session.transport.VirtualScheduler
import cn.wpgd.app.session.wire.CommandBody
import cn.wpgd.app.session.wire.ConsentBody
import cn.wpgd.app.session.wire.GrantStatus
import cn.wpgd.app.session.wire.PassBody
import cn.wpgd.app.session.wire.PlayBody
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.session.wire.RejectionReceipt
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.session.wire.WireAssignment
import cn.wpgd.app.session.wire.WireMessage
import java.io.File
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.test.assertTrue
import kotlin.test.fail

/** The provisional base and match profiles, read from the repository's `rules/profiles/`. */
internal object TestProfiles {
    private fun read(name: String): String {
        val f = File("../rules/profiles/$name.json")
        check(f.isFile) { "profile not found at ${f.absolutePath}" }
        return f.readText()
    }

    val base: RuleProfile by lazy { RuleProfileLoader.parse(read("P1-base-v1")).getOrThrow() }
    val match: RuleProfile by lazy { RuleProfileLoader.parse(read("M1-match-v1"), MatchOptions.registry).getOrThrow() }
}

/** Seeded bytes for tests only: makes capabilities, digest keys and command ids reproducible. */
internal class SeededBytes(seed: Long) : SecureBytes {
    private val random = Random(seed)
    override fun next(size: Int): ByteArray = random.nextBytes(size)
}

/** Test crypto: real JCA primitives, seeded "random" bytes. ECDH keys stay truly random. */
internal fun testCrypto(seed: Long, aead: Aead = JcaAesGcm): CryptoSuite =
    CryptoSuite(SeededBytes(seed), JcaP256KeyAgreement(SecureRandom()), aead, JcaHmacSha256)

/** Positive-control AEAD for the confidentiality scanner: no encryption at all. Never used outside tests. */
internal object IdentityAead : Aead {
    override val keySize = 32
    override val nonceSize = 12
    override fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray) = plaintext + ByteArray(16)
    override fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray) = ciphertext.copyOfRange(0, ciphertext.size - 16)
}

/** A [RandomSource] that makes the match deal exactly [deck] (top to bottom) and draws [cutCardId] as the cut card. */
internal class ScriptedDeal(deck: List<String>, cutCardId: String) : RandomSource {
    private val values = ArrayDeque<Long>()

    init {
        val out = StandardDeck.cards.map { it.id }.toMutableList()
        check(deck.toSet() == out.toSet() && deck.size == 108)
        for (i in out.lastIndex downTo 1) {
            val j = out.subList(0, i + 1).indexOf(deck[i])
            values += j.toLong() shl 1 // nextInt(bound) of (j shl 1) is j: r = j, accepted because j < bound
            val t = out[i]; out[i] = out[j]; out[j] = t
        }
        check(out == deck)
        values += StandardDeck.cards.indexOfFirst { it.id == cutCardId }.toLong() shl 1
    }

    override fun nextLong(): Long = values.removeFirstOrNull() ?: error("scripted deal exhausted")
}

internal const val ROOM = "room-a"

/**
 * Recovery policy of the WI-05 session tests: a long grace, then pause. None of those tests runs
 * an absent seat past it; the WI-06 tests set their own policies.
 */
internal val TEST_RECOVERY = RecoveryPolicy(graceMs = 30_000, afterGrace = AbsencePolicy.PAUSE, afterLeave = AbsencePolicy.PAUSE, substitute = null)

/**
 * The host's process (WI-06 crash tests): its timers run on the shared virtual clock until the
 * process dies; after [alive] turns false none of its scheduled tasks runs.
 */
internal class HostProcess(private val base: Scheduler) : Scheduler {
    var alive = true
    override val now: Long get() = base.now
    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable = base.schedule(delayMs) { if (alive) task() }
}

/** A host with in-memory links to its clients, all in one virtual time. */
internal class Table(
    val seed: Long = 1,
    val hostConfig: HostConfig = HostConfig(roomId = ROOM, recovery = TEST_RECOVERY),
    hostCrypto: CryptoSuite = testCrypto(seed),
    dealRandom: ((Long) -> RandomSource)? = null,
    val clientAead: Aead = JcaAesGcm,
    val scheduler: VirtualScheduler = VirtualScheduler(),
    val network: InMemoryNetwork = InMemoryNetwork(scheduler, seed),
    /** WI-06: builds a (saved) host on its own [HostProcess], so the test can crash and restart it. */
    hostFactory: ((Scheduler) -> SessionHost)? = null,
) {
    var process: HostProcess = HostProcess(scheduler)
        private set
    var host: SessionHost = when {
        hostFactory != null -> hostFactory(process)
        dealRandom == null -> SessionHost(hostConfig, TestProfiles.base, TestProfiles.match, hostCrypto, scheduler)
        else -> SessionHost(hostConfig, TestProfiles.base, TestProfiles.match, hostCrypto, scheduler, dealRandom)
    }
        private set
    private val links = ArrayList<InMemoryLink>()

    /** The host process dies: none of its timers runs again and every link to it drops. */
    fun crashHost() {
        process.alive = false
        links.forEach { it.disconnect() }
        links.clear()
    }

    /** Starts a new host process (for example a host restored from storage). */
    fun startHost(factory: (Scheduler) -> SessionHost): SessionHost {
        process = HostProcess(scheduler)
        host = factory(process)
        return host
    }

    /** [peer]'s client opens a new connection to the current host (same client state: epoch, capability, revision). */
    fun reconnect(peer: Peer, faults: LinkFaults = LinkFaults.RELIABLE): Peer {
        val link = network.link(toHost = faults, toClient = faults)
        links += link
        host.accept(link.hostEnd)
        peer.client.connect(link.clientEnd)
        return Peer(peer.client, link, peer.received)
    }
    val projector = MatchProjector(TestProfiles.base, TestProfiles.match)
    private var clientSeed = seed * 1000

    class Peer(val client: SessionClient, val link: InMemoryLink, val received: MutableList<WireMessage>)

    /** A rejoin ticket ([rejoin]) is pinned to this table's current host key unless [pin] names another (G2). */
    fun config(
        role: Role, nickname: String, seat: Seat? = null, replace: Boolean = false, roomId: String = hostConfig.roomId,
        rejoin: String? = null, pin: String? = rejoin?.let { host.hostKeyFingerprint },
    ) = ClientConfig(roomId, role, nickname, TestProfiles.base.hash, TestProfiles.match.hash, seat = seat, replace = replace, rejoinCapability = rejoin, pinnedHostKey = pin)

    /** Connects a client (not yet admitted). Every message it receives is recorded in [Peer.received]. */
    fun connect(config: ClientConfig, faults: LinkFaults = LinkFaults.RELIABLE, toHost: LinkFaults = faults): Peer {
        val client = SessionClient(config, testCrypto(++clientSeed, clientAead), scheduler)
        val received = ArrayList<WireMessage>()
        client.tap = { received += it }
        val link = network.link(toHost = toHost, toClient = faults)
        links += link
        host.accept(link.hostEnd)
        client.connect(link.clientEnd)
        return Peer(client, link, received)
    }

    /** Runs until [client] waits for approval, approves it with its own code, and runs until admitted. */
    fun admit(peer: Peer): String {
        val c = peer.client
        val ok = scheduler.runUntil(60_000) { c.phase == ClientPhase.PENDING }
        check(ok) { "not pending: phase ${c.phase}, close ${c.closeReason}, denial ${c.denial}, rejected ${c.helloRejection}, host closed ${host.metrics.closed}, received ${peer.received.map { it::class.simpleName }}" }
        val p = host.pendingAdmissions().single { it.nickname == nickname(c) }
        val result = host.approve(p.connectionId, checkNotNull(c.confirmationCode))
        check(result is SessionHost.ApprovalResult.Admitted) { "approval failed: $result" }
        until(60_000) { c.phase == ClientPhase.ADMITTED && c.revision >= 0 }
        return result.memberId
    }

    private fun nickname(c: SessionClient): String = names.getValue(c)
    private val names = HashMap<SessionClient, String>()

    fun player(seat: Seat, faults: LinkFaults = LinkFaults.RELIABLE): Peer =
        connect(config(Role.PLAYER, seat.name.lowercase(), seat), faults).also { names[it.client] = seat.name.lowercase(); admit(it) }

    fun observer(name: String, faults: LinkFaults = LinkFaults.RELIABLE): Peer =
        connect(config(Role.OBSERVER, name), faults).also { names[it.client] = name; admit(it) }

    /** Connect without admitting; for admission tests. */
    fun joiner(config: ClientConfig, faults: LinkFaults = LinkFaults.RELIABLE): Peer =
        connect(config, faults).also { names[it.client] = config.nickname }

    fun fourPlayers(faults: LinkFaults = LinkFaults.RELIABLE): Map<Seat, Peer> = Seat.entries.associateWith { player(it, faults) }

    fun run(ms: Long) = scheduler.runFor(ms)

    fun until(maxMs: Long, done: () -> Boolean) {
        assertTrue(scheduler.runUntil(maxMs, done = done), "condition not reached within $maxMs ms of virtual time (now ${scheduler.now})")
    }

    /** Deals the first deal and opens play. */
    fun startDeal() {
        while (host.state.phase != MatchPhase.PLAYING) {
            check(host.advance() is SessionHost.AdvanceResult.Applied) { "cannot advance from ${host.state.phase}" }
        }
        run(50)
    }

    fun memberBytes(seat: Seat): String = MatchProjectionCodec.encode(projector.memberView(host.state, seat)).decodeToString()

    fun spectatorBytes(): String = MatchProjectionCodec.encode(projector.spectatorView(host.state)).decodeToString()

    /** Submits [body] from [peer] and runs until its reply arrives. */
    fun command(peer: Peer, body: CommandBody, maxMs: Long = 10_000): WireMessage {
        val id = checkNotNull(peer.client.submit(body)) { "client cannot submit in phase ${peer.client.phase}" }
        until(maxMs) { id in peer.client.replies }
        return peer.client.replies.getValue(id)
    }
}

/** The seat to act and whether it leads, from the host's authoritative state. */
internal fun Table.turn(): Seat = checkNotNull(host.state.deal?.turn) { "no turn in ${host.state.phase}" }

/**
 * The simplest legal play: lead the lowest natural single (any single may lead), follow by passing.
 * Plays the whole deal out: the leader runs its hand, then 接风 passes the lead to its partner.
 */
internal fun simpleBody(view: MatchMemberView, exclude: Set<String> = emptySet()): CommandBody {
    val deal = checkNotNull(view.deal)
    if (deal.table.trick.isNotEmpty()) return PassBody
    val level = deal.table.level
    val cards = deal.hand.map { checkNotNull(StandardDeck[it.id]) }
    val natural = cards.filter { !(it.isWildcard(level)) && it.id !in exclude }.sortedWith(compareBy({ it.rank }, { it.id }))
    if (natural.isNotEmpty()) return PlayBody(listOf(natural.first().id), emptyList(), null)
    val action = LegalActionGenerator(RuleSettings.of(TestProfiles.base), level).generate(cards, null).minByOrNull { it.cards.size }
        ?: fail("a leader with cards must have a legal action")
    return PlayBody(action.cards.map { it.id }, action.declaration?.map { WireAssignment(it.rank, it.suit) }, action.combination.type)
}

/**
 * Drives one player's client: whenever its view says it must act and nothing is pending, it waits
 * [thinkMs] and submits [simpleBody] for the revision it saw. A stale-revision rejection waits for a
 * newer view. Also consents to every pending follow request on its seat when [consents] is true.
 */
internal class Bot(private val table: Table, val peer: Table.Peer, private val thinkMs: Long = 15, private val consents: Boolean = false) {
    val client get() = peer.client
    val gameCommands = ArrayList<String>()
    private var scheduledFor = -2L
    private var rejectedAt = -2L
    private val consented = HashSet<String>()
    var enabled = true

    init {
        val previous = client.onUpdate
        client.onUpdate = { previous?.invoke(); poke() }
    }

    fun poke() {
        if (!enabled || client.phase != ClientPhase.ADMITTED || client.pendingCommands > 0) return
        if (consents) {
            client.grants.firstOrNull { it.status == GrantStatus.PENDING && it.grantId !in consented }?.let {
                consented += it.grantId
                client.submit(ConsentBody(it.grantId, approve = true))
                return
            }
        }
        val v = client.memberView ?: return
        val deal = v.deal ?: return
        val rev = client.revision
        if (v.match.phase != MatchPhase.PLAYING || !deal.yourTurn || rev == scheduledFor || rev == rejectedAt) return
        scheduledFor = rev
        table.scheduler.schedule(thinkMs) {
            if (!enabled || client.revision != rev || client.pendingCommands > 0 || client.phase != ClientPhase.ADMITTED) return@schedule
            val id = client.submit(simpleBody(checkNotNull(client.memberView))) ?: return@schedule
            gameCommands += id
            watch(id, rev)
        }
    }

    private fun watch(id: String, rev: Long) {
        table.scheduler.schedule(5) {
            val reply = client.replies[id]
            when {
                reply == null && client.pendingCommands > 0 -> watch(id, rev)
                reply is RejectionReceipt && reply.code == RejectionCode.STALE_REVISION -> { rejectedAt = rev; poke() }
                else -> poke()
            }
        }
    }
}

/** Reassembles every captured chunk per link direction and returns the whole frames (for scanning). */
internal fun capturedFrames(network: InMemoryNetwork): List<ByteArray> {
    val limits = cn.wpgd.app.session.framing.FramingLimits(maxPartialFrames = 64, rememberedFrameIds = 1_000_000)
    val streams = HashMap<Pair<Int, Boolean>, cn.wpgd.app.session.framing.Reassembler>()
    val frames = ArrayList<ByteArray>()
    for (c in network.captured) {
        val r = streams.getOrPut(c.linkId to c.toHost) { cn.wpgd.app.session.framing.Reassembler(limits) }
        val result = r.accept(c.bytes)
        if (result is cn.wpgd.app.session.framing.Reassembler.Result.Complete) frames += result.frame
    }
    return frames
}

internal fun ByteArray.containsSequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return true
    }
    return false
}
