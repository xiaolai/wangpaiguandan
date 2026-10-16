package cn.wpgd.app.session.host

import cn.wpgd.app.core.match.MatchCommand
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchProjectionCodec
import cn.wpgd.app.core.match.MatchProjector
import cn.wpgd.app.core.match.MatchReduceResult
import cn.wpgd.app.core.match.MatchReducer
import cn.wpgd.app.core.match.MatchRejection
import cn.wpgd.app.core.match.MatchState
import cn.wpgd.app.core.match.MatchStateCodec
import cn.wpgd.app.core.rules.RuleProfile
import cn.wpgd.app.core.rules.WildcardAssignment
import cn.wpgd.app.core.state.Command
import cn.wpgd.app.core.state.RandomSource
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.SplitMix64
import cn.wpgd.app.session.channel.Direction
import cn.wpgd.app.session.channel.Inbound
import cn.wpgd.app.session.channel.Opener
import cn.wpgd.app.session.channel.OutKind
import cn.wpgd.app.session.channel.PeerLink
import cn.wpgd.app.session.channel.Sealer
import cn.wpgd.app.session.crypto.CryptoFailure
import cn.wpgd.app.session.crypto.CryptoSuite
import cn.wpgd.app.session.crypto.EphemeralKeyPair
import cn.wpgd.app.session.crypto.HostKey
import cn.wpgd.app.session.crypto.RejoinProof
import cn.wpgd.app.session.crypto.SessionKeys
import cn.wpgd.app.session.framing.Violation
import cn.wpgd.app.session.storage.CommandRef
import cn.wpgd.app.session.storage.MembersFile
import cn.wpgd.app.session.storage.QuarantineReason
import cn.wpgd.app.session.storage.RecoveredTable
import cn.wpgd.app.session.storage.RecoveryReport
import cn.wpgd.app.session.storage.RecoveryResult
import cn.wpgd.app.session.storage.Storage
import cn.wpgd.app.session.storage.StoredMember
import cn.wpgd.app.session.storage.StoredPrivateView
import cn.wpgd.app.session.storage.TableJournal
import cn.wpgd.app.session.storage.TableMeta
import cn.wpgd.app.session.storage.TableRecovery
import cn.wpgd.app.session.storage.TableStatus
import cn.wpgd.app.session.storage.STORAGE_SCHEMA_VERSION
import cn.wpgd.app.session.transport.Cancellable
import cn.wpgd.app.session.transport.Scheduler
import cn.wpgd.app.session.transport.Transport
import cn.wpgd.app.session.transport.TransportListener
import cn.wpgd.app.session.wire.AdmissionDenial
import cn.wpgd.app.session.wire.AdmissionDenied
import cn.wpgd.app.session.wire.AdmissionPending
import cn.wpgd.app.session.wire.Admitted
import cn.wpgd.app.session.wire.AskCountBody
import cn.wpgd.app.session.wire.B64
import cn.wpgd.app.session.wire.CloseReason
import cn.wpgd.app.session.wire.Closing
import cn.wpgd.app.session.wire.CommandBody
import cn.wpgd.app.session.wire.CommandMessage
import cn.wpgd.app.session.wire.ConsentBody
import cn.wpgd.app.session.wire.FollowRequestBody
import cn.wpgd.app.session.wire.FollowSnapshot
import cn.wpgd.app.session.wire.GiveTributeBody
import cn.wpgd.app.session.wire.GrantStatus
import cn.wpgd.app.session.wire.GrantView
import cn.wpgd.app.session.wire.HandshakeMessage
import cn.wpgd.app.session.wire.Heartbeat
import cn.wpgd.app.session.wire.Hello
import cn.wpgd.app.session.wire.HelloRejectReason
import cn.wpgd.app.session.wire.HelloRejected
import cn.wpgd.app.session.wire.Join
import cn.wpgd.app.session.wire.LeaveBody
import cn.wpgd.app.session.wire.PassBody
import cn.wpgd.app.session.wire.PlayBody
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.session.wire.ReadyBody
import cn.wpgd.app.session.wire.Receipt
import cn.wpgd.app.session.wire.RoomSettingsView
import cn.wpgd.app.session.wire.RoomView
import cn.wpgd.app.session.wire.RosterSeat
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.session.wire.RejectionReceipt
import cn.wpgd.app.session.wire.ResistBody
import cn.wpgd.app.session.wire.Resync
import cn.wpgd.app.session.wire.ReturnTributeBody
import cn.wpgd.app.session.wire.RevokeBody
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.session.wire.Snapshot
import cn.wpgd.app.session.wire.Status
import cn.wpgd.app.session.wire.Welcome
import cn.wpgd.app.session.wire.WireMessage
import cn.wpgd.app.session.wire.WireValidation
import java.io.IOException

/**
 * The authoritative host of one room. Single-threaded: every entry point (transport callbacks,
 * timers, the host UI calls) must run on the [scheduler]'s thread.
 *
 * - Identity: [roomId] (from the config) and [epoch]. [restartRoom] starts a new epoch; every
 *   connection, capability, grant and timer of the old epoch becomes inert.
 * - Connections: each accepted transport gets a connection id that is never reused. Every callback
 *   and timer is bound to (epoch, connection id) and does nothing once that pair is not current
 *   (audit A16, A18, GDN-053).
 * - Admission (G2, owner-delegated default; no external cryptography review): hello (version
 *   range, room id, both profile hashes) → welcome (ephemeral P-256 key, the static room key
 *   [hostKeyFingerprint] identifies, a fresh nonce) → sealed join → host approval with the joiner's
 *   confirmation code ([approve]). A member admitted earlier in this epoch rejoins without approval
 *   by sending a proof keyed with its capability over this connection's transcript; the capability
 *   itself is never sent by a joiner. Nothing but handshake and admission messages reaches an
 *   unadmitted peer (P6).
 * - Commands: capability, epoch, idempotent command id, expected revision; then the WI-04
 *   [MatchReducer]. Every accepted command sends each member its own projection (WI-03/WI-04
 *   projector) and the issuer a receipt with a keyed digest of its own projection; every refused
 *   command sends the issuer a typed rejection (audit A20).
 * - Observer grants (PROVISIONAL, G4): consent, one private viewpoint per deal, revocation
 *   boundary, departure revokes, replacement needs new consent.
 * - Persistence (WI-06, when created with [create] or [restore]): every revision is written to the
 *   table's [TableJournal] (record, marker, high-water mark, each durable) before any member sees it;
 *   a failed write pauses the room and nothing of that revision is sent. Members and their
 *   capabilities are saved before `admitted` is sent.
 * - Recovery (WI-06): every seat moves through the one [SeatRecovery] transition: disconnect, grace,
 *   then substitution or pause, live and after a restore. Game commands are applied only while the
 *   [status] is [RoomStatus.Active].
 * - Lobby (WI-09): before the first deal of a match ([inLobby]) the host user may change the room
 *   settings ([updateSettings], which clears every ready flag), arrange seats ([swapSeats],
 *   [clearAiSeat]) and [start] once every seat is filled and every human is connected and ready
 *   ([LobbyGate]). Every member sees the roster and settings in its [Status] ([RoomView]).
 *   [rematch] starts a new match with the same members, seats and connections.
 */
class SessionHost internal constructor(
    private val config: HostConfig,
    base: RuleProfile,
    match: RuleProfile,
    private val crypto: CryptoSuite,
    private val scheduler: Scheduler,
    /** Shuffle source for a deal seed. Production: [SplitMix64]; tests inject exact deals. */
    private val dealRandom: (Long) -> RandomSource,
    /** The table's event log; null for a room that is not saved. */
    private val journal: TableJournal? = null,
    /** The room key (G2); a saved table passes the one it stored, an unsaved room makes its own. */
    roomKey: EphemeralKeyPair? = null,
) {
    constructor(config: HostConfig, base: RuleProfile, match: RuleProfile, crypto: CryptoSuite, scheduler: Scheduler) :
        this(config, base, match, crypto, scheduler, ::SplitMix64)

    private val hostKey = HostKey(roomKey ?: crypto.keyAgreement.generate(), crypto.hmac)

    /** Public identity of this room's key (base64url): adverts carry it, joiners pin it with their ticket. */
    val hostKeyFingerprint: String = B64.encode(hostKey.fingerprint)

    private val reducer = MatchReducer(base, match)
    private val projector = MatchProjector(base, match)
    private val baseHash = base.hash
    private val matchHash = match.hash

    val roomId: String get() = config.roomId

    var epoch: Long = 1
        private set

    /** Host-internal authoritative state. Never sent to anyone; members get projections. */
    var state: MatchState = reducer.newMatch()
        private set

    val revision: Long get() = state.revision

    val metrics = HostMetrics()

    private enum class Phase { AWAITING_HELLO, AWAITING_JOIN, PENDING, ADMITTED }

    private inner class Conn(val id: Long, val epoch: Long, val link: PeerLink) {
        var phase = Phase.AWAITING_HELLO
        var hello: Hello? = null
        var keyPair: EphemeralKeyPair? = null
        var keys: SessionKeys? = null
        /** Fresh per connection: a rejoin proof is bound to it, so no proof verifies twice. */
        var nonce: ByteArray? = null
        var join: Join? = null
        var memberId: String? = null
        var lastInboundAt = scheduler.now
        var windowStart = scheduler.now
        var framesInWindow = 0
        val timers = ArrayList<Cancellable>()
    }

    private class Member(
        val id: String,
        val role: Role,
        /** Changes only in the lobby ([swapSeats]). */
        var seat: Seat?,
        val nickname: String,
        val capability: ByteArray,
        val digestKey: ByteArray,
    ) {
        var connId: Long? = null
        val replies = LinkedHashMap<String, WireMessage>()
        var statusRevision = 0L
        var grantCounter = 0L
        /** (deal number, seat) of the private viewpoint this observer held in that deal (P5). */
        var privateView: Pair<Int, Seat>? = null
        var lastResyncAt = Long.MIN_VALUE / 2
    }

    /** A seat's control and the token that binds AI tasks and grace timers to exactly this control. */
    private class Slot(val control: SeatControl, val token: Long)

    private class Grant(val id: String, val observerId: String, val observerName: String, val seat: Seat) {
        var status = GrantStatus.PENDING
        var grantRevision = 0L
        val live: Boolean get() = status == GrantStatus.PENDING || status == GrantStatus.ACTIVE
    }

    private val connections = LinkedHashMap<Long, Conn>()
    private val members = LinkedHashMap<String, Member>()
    private val seats = HashMap<Seat, Slot>()
    private val aiPolicies = HashMap<Seat, SeatPolicy>()
    private val grants = LinkedHashMap<String, Grant>()
    private val aiScheduledFor = HashMap<Seat, Long>()
    /** WI-07: a deferred AI decision still running per seat; cancelled when its binding goes. */
    private val aiInFlight = HashMap<Seat, Cancellable>()
    private var nextConnId = 1L
    private var nextMemberId = 1L
    private var nextGrantId = 1L
    private var nextAiToken = 1L
    private var heartbeatTimer: Cancellable? = null

    // ---- Room status (WI-06, audit A22) -------------------------------------------------------------

    // ---- Lobby (WI-09) -----------------------------------------------------------------------------

    /** Room settings the lobby may change; [settingsRevision] counts the changes. */
    private var policy: RoomPolicy = config.policy
    private var recovery: RecoveryPolicy = config.recovery

    var settingsRevision: Long = 1
        private set

    /** Members that said they are ready for the settings at [settingsRevision]. */
    private val readyMembers = HashSet<String>()

    val roomPolicy: RoomPolicy get() = policy
    val recoveryPolicy: RecoveryPolicy get() = recovery

    /** True before the first deal of a match: settings, seats and ready flags may change. */
    val inLobby: Boolean
        get() = state.phase == MatchPhase.AWAITING_DEAL && state.dealNumber == 0 && quarantine == null && suspendedUntil == null && ended == null

    private val pauseReasons = HashSet<PauseReason>()
    private var quarantine: RoomStatus.Quarantined? = null
    private var suspendedUntil: Long? = null
    private var ended: EndReason? = null

    val status: RoomStatus
        get() {
            quarantine?.let { return it }
            suspendedUntil?.let { return RoomStatus.Suspended(it) }
            ended?.let { return RoomStatus.Ended(it) }
            val reasons = pauseReasons + (if (Seat.entries.any { control(it) is SeatControl.AwaitingPlayer }) setOf(PauseReason.AWAITING_PLAYER) else emptySet())
            return if (reasons.isEmpty()) RoomStatus.Active else RoomStatus.Paused(reasons)
        }

    private val active: Boolean get() = status == RoomStatus.Active

    fun seatControl(seat: Seat): SeatControl = control(seat)

    /** The table this room is saved as; null when it is not saved. */
    val tableId: String? get() = journal?.tableId

    /** The host pauses the table: game commands are refused until [resume]. */
    fun pause() {
        if (pauseReasons.add(PauseReason.HOST)) {
            broadcastStatus()
            pumpAll()
        }
    }

    /** Lifts a host pause. A storage-failure pause cannot be lifted: restart from the save instead. */
    fun resume(): Boolean {
        if (pauseReasons.remove(PauseReason.HOST)) {
            broadcastStatus()
            pumpAll()
        }
        if (active) scheduleAi()
        return active
    }

    /**
     * Suspends a saved table until [expiresAt] (wall clock): the save is kept, every connection is
     * closed with [CloseReason.SUSPENDED], capabilities stay valid for when the table is restored.
     * Returns false when the status could not be saved: the room pauses for the storage failure and
     * nothing is closed, since the on-disk status is unknown until a restart recovers it.
     */
    fun suspend(now: Long, expiresAt: Long): Boolean {
        val j = checkNotNull(journal) { "only a saved table can be suspended" }
        require(expiresAt > now) { "expiry must be in the future" }
        check(quarantine == null) { "a quarantined table is not suspended" }
        if (!saveStatus(j, TableStatus.SUSPENDED, now, expiresAt)) return false
        suspendedUntil = expiresAt
        for (c in connections.values.toList()) close(c, CloseReason.SUSPENDED)
        return true
    }

    /**
     * The host ends the table. A saved table is kept until [expiresAt]; members' capabilities end.
     * Returns false, with the room paused and nothing closed, when the saved status could not be written.
     */
    fun end(now: Long, expiresAt: Long): Boolean {
        if (journal != null && !saveStatus(journal, TableStatus.ENDED, now, expiresAt)) return false
        ended = EndReason.HOST_ENDED
        for (c in connections.values.toList()) close(c, CloseReason.EPOCH_ENDED)
        return true
    }

    private fun saveStatus(j: TableJournal, status: TableStatus, now: Long, expiresAt: Long): Boolean {
        if (j.broken) return false.also { storageFailed() }
        return try {
            j.writeMeta(j.meta.copy(status = status, statusAt = now, expiresAt = expiresAt))
            true
        } catch (_: IOException) {
            storageFailed()
            false
        }
    }

    // ---- Host UI surface -------------------------------------------------------------------------

    /** A joiner waiting for approval. The host user compares [confirmationCode] with the joiner's screen. */
    data class PendingAdmission(
        val connectionId: Long,
        val role: Role,
        val seat: Seat?,
        val replace: Boolean,
        val nickname: String,
        val confirmationCode: String,
    )

    data class MemberInfo(val memberId: String, val role: Role, val seat: Seat?, val nickname: String, val connected: Boolean)

    sealed interface ApprovalResult {
        data class Admitted(val memberId: String) : ApprovalResult
        data object NotPending : ApprovalResult

        /** The code the host user confirmed is not this connection's: possible interception. Still pending. */
        data object CodeMismatch : ApprovalResult
        data class Denied(val reason: AdmissionDenial) : ApprovalResult

        /** The room is quarantined or its save failed: nobody new is admitted. */
        data class RoomNotActive(val status: RoomStatus) : ApprovalResult
    }

    fun pendingAdmissions(): List<PendingAdmission> = connections.values.filter { it.phase == Phase.PENDING }.map {
        val j = checkNotNull(it.join)
        PendingAdmission(it.id, checkNotNull(it.hello).role, j.seat, j.replace, j.nickname, checkNotNull(it.keys).confirmationCode)
    }

    fun members(): List<MemberInfo> = members.values.map { MemberInfo(it.id, it.role, it.seat, it.nickname, it.connId != null) }

    val connectionCount: Int get() = connections.size

    /** Queued outbound messages for [memberId]'s connection (observability; bounded by the queue limit). */
    fun queuedFor(memberId: String): Int = members[memberId]?.connId?.let { connections[it]?.link?.queued } ?: 0

    /** Accepts a new peer connection. Returns its connection id. */
    fun accept(transport: Transport): Long {
        val id = nextConnId++
        val ep = epoch
        val conn = Conn(id, ep, PeerLink(transport, config.limits, config.queueLimit))
        connections[id] = conn
        transport.open(object : TransportListener {
            override fun onReceive(chunk: ByteArray) = guarded(id, ep) { onChunk(it, chunk) }
            override fun onWritable() = guarded(id, ep) { it.link.pump() }
            override fun onClosed() = guarded(id, ep) { drop(it) }
        })
        conn.timers += scheduler.schedule(config.helloDeadlineMs) {
            guarded(id, ep) { if (it.phase == Phase.AWAITING_HELLO || it.phase == Phase.AWAITING_JOIN) close(it, CloseReason.HELLO_TIMEOUT) }
        }
        ensureHeartbeat()
        return id
    }

    fun approve(connectionId: Long, confirmedCode: String): ApprovalResult {
        val conn = connections[connectionId]?.takeIf { it.phase == Phase.PENDING } ?: return ApprovalResult.NotPending
        val keys = checkNotNull(conn.keys)
        if (!crypto.hmac.equal(confirmedCode.encodeToByteArray(), keys.confirmationCode.encodeToByteArray())) {
            metrics.codeMismatches++
            return ApprovalResult.CodeMismatch
        }
        if (quarantine != null || PauseReason.STORAGE_FAILURE in pauseReasons || ended == EndReason.HOST_ENDED) return ApprovalResult.RoomNotActive(status)
        val hello = checkNotNull(conn.hello)
        val join = checkNotNull(conn.join)
        val replaced = if (hello.role == Role.PLAYER) {
            val seat = checkNotNull(join.seat)
            if (!seatOpen(seat) && !join.replace) return deny(conn, AdmissionDenial.SEAT_TAKEN)
            control(seat).owner?.let { members[it] }
        } else null
        val member = Member(
            id = "m${nextMemberId}",
            role = hello.role,
            seat = join.seat.takeIf { hello.role == Role.PLAYER },
            nickname = join.nickname,
            capability = crypto.random.next(32),
            digestKey = crypto.random.next(32),
        )
        // The proposed membership and seating are saved before anything changes: the capability is
        // durable before it is handed out (a restarted host must still honour it), and a replacement
        // that cannot be saved leaves the old member seated, connected and unannounced.
        val proposed = members.values.filter { it !== replaced } + member
        val seated: (Seat) -> SeatControl = { s ->
            if (s == member.seat) SeatRecovery.next(control(s), SeatEvent.Replaced(member.id), recovery) else control(s)
        }
        if (!persistMembers(proposed, nextMemberId + 1, seated)) {
            close(conn, CloseReason.DENIED)
            return ApprovalResult.RoomNotActive(status)
        }
        nextMemberId++
        // Replacement is one step: the old member and its capability are gone before the new one exists.
        replaced?.let(::replaceOut)
        members[member.id] = member
        member.seat?.let { transition(it, SeatEvent.Replaced(member.id)) }
        bind(member, conn)
        metrics.admitted++
        broadcastStatus()
        scheduleAi()
        pumpAll()
        return ApprovalResult.Admitted(member.id)
    }

    fun deny(connectionId: Long): Boolean {
        val conn = connections[connectionId]?.takeIf { it.phase == Phase.PENDING } ?: return false
        deny(conn, AdmissionDenial.DENIED_BY_HOST)
        return true
    }

    /**
     * Puts an AI in [seat] (empty or AI seats only). Its tasks are bound to epoch and turn revision (A21).
     * False when the seat has an owner, or when the change could not be saved (nothing changes, the room pauses).
     */
    fun setAi(seat: Seat, policy: SeatPolicy): Boolean {
        if (control(seat).owner != null) return false
        // Saved before it is applied: a change that cannot be saved never reaches a member's roster.
        if (!persistMembers(members.values.toList(), nextMemberId) { if (it == seat) SeatControl.Ai else control(it) }) {
            pumpAll()
            return false
        }
        aiPolicies[seat] = policy
        setControl(seat, SeatControl.Ai)
        return true
    }

    /**
     * Lobby (B10): moves whoever holds [a] to [b] and the other way round (a human, an AI or nobody).
     * Grants on either seat end (consent was given by a person at a seat), and the moved humans must
     * ready again. False outside the lobby, or when the new seating could not be saved (nobody moves
     * and the room pauses; ended grants stay ended).
     */
    fun swapSeats(a: Seat, b: Seat): Boolean {
        if (!inLobby || a == b) return false
        for (g in grants.values.toList()) if (g.live && (g.seat == a || g.seat == b)) endGrant(g)
        val ca = control(a)
        val cb = control(b)
        val ma = ca.owner?.let { members[it] }
        val mb = cb.owner?.let { members[it] }
        // Saved before it is applied: a seating that cannot be saved never reaches a member's roster.
        ma?.seat = b
        mb?.seat = a
        val swapped: (Seat) -> SeatControl = { when (it) { a -> cb; b -> ca; else -> control(it) } }
        if (!persistMembers(members.values.toList(), nextMemberId, swapped)) {
            ma?.seat = a
            mb?.seat = b
            pumpAll()
            return false
        }
        val pa = aiPolicies.remove(a)
        val pb = aiPolicies.remove(b)
        pb?.let { aiPolicies[a] = it }
        pa?.let { aiPolicies[b] = it }
        listOfNotNull(ma, mb).forEach { readyMembers.remove(it.id) }
        setControl(a, cb)
        setControl(b, ca)
        listOfNotNull(ma, mb).forEach(::sendViews)
        pumpAll()
        return true
    }

    /**
     * Lobby: takes the AI off [seat] so a person can take it. False unless the seat is an AI seat, or
     * when the change could not be saved (the AI stays, the room pauses).
     */
    fun clearAiSeat(seat: Seat): Boolean {
        if (!inLobby || control(seat) != SeatControl.Ai) return false
        // Saved before it is applied: a change that cannot be saved never reaches a member's roster.
        val saved = persistMembers(members.values.toList(), nextMemberId) { if (it == seat) SeatControl.Empty else control(it) }
        if (saved) {
            aiPolicies.remove(seat)
            setControl(seat, SeatControl.Empty)
        }
        pumpAll()
        return saved
    }

    /**
     * Lobby (B1, D2): new room settings. Every ready flag is cleared, so nobody starts under rules
     * they have not seen; private follow turned off ends every grant. False outside the lobby.
     */
    fun updateSettings(policy: RoomPolicy, recovery: RecoveryPolicy): Boolean {
        if (!inLobby) return false
        if (policy == this.policy && recovery == this.recovery) return true
        this.policy = policy
        this.recovery = recovery
        settingsRevision++
        readyMembers.clear()
        if (!policy.privateFollow) grants.values.filter { it.live }.forEach(::endGrant)
        broadcastStatus()
        pumpAll()
        return true
    }

    /**
     * H1: the host lets the AI stand in now for a seat whose human is away (grace running, or the
     * room waiting for them). Needs a substitute policy. The human can still reclaim the seat.
     */
    fun standInNow(seat: Seat): Boolean {
        val before = control(seat)
        transition(seat, SeatEvent.HostStandIn)
        val changed = control(seat) != before
        if (changed) pumpAll()
        return changed
    }

    sealed interface StartResult {
        data class Started(val revision: Long) : StartResult
        data class Blocked(val blockers: List<LobbyGate.Blocker>) : StartResult
        data object NotInLobby : StartResult

        /** The deal could not be committed (the room is not active, or the save failed). */
        data class Failed(val result: AdvanceResult) : StartResult
    }

    /** Lobby: deals the first deal once [LobbyGate] finds nothing blocking (GDN-013). */
    fun start(): StartResult {
        if (!inLobby) return StartResult.NotInLobby
        val blockers = LobbyGate.blockers(room().seats)
        if (blockers.isNotEmpty()) return StartResult.Blocked(blockers)
        return when (val r = advance()) {
            is AdvanceResult.Applied -> StartResult.Started(r.revision)
            else -> StartResult.Failed(r)
        }
    }

    /**
     * GDN-017: after the match is over, starts a new one under the same profiles with the same
     * members, seats, capabilities and connections (nobody searches or joins again). The room is in
     * the lobby again, every ready flag cleared. False unless the match is over.
     */
    fun rematch(): Boolean {
        if (state.phase != MatchPhase.OVER || ended != EndReason.MATCH_OVER || quarantine != null || suspendedUntil != null) return false
        ended = null
        if (!active) {
            ended = EndReason.MATCH_OVER
            return false
        }
        val accepted = reducer.reduce(state, MatchCommand.Rematch) as? MatchReduceResult.Accepted ?: error("rematch refused in ${state.phase}")
        readyMembers.clear()
        // One private view per deal is counted per match: deal numbers start again. Saved before the
        // new match exists, so a restored new match never inherits the old match's viewpoints.
        members.values.forEach { it.privateView = null }
        if (!persistMembers(members.values.toList(), nextMemberId)) {
            ended = EndReason.MATCH_OVER
            pumpAll()
            return false
        }
        val ok = commit(accepted, null, null)
        pumpAll()
        return ok
    }

    /** The roster and settings as the host user sees them (no viewer seat). */
    fun room(): RoomView = roomView(null)

    sealed interface AdvanceResult {
        data class Applied(val command: String, val revision: Long) : AdvanceResult
        data object NotReady : AdvanceResult
        data object NothingToDo : AdvanceResult

        /** Paused, suspended, ended or quarantined; or the save failed while applying this step. */
        data class RoomNotActive(val status: RoomStatus) : AdvanceResult
    }

    /**
     * Applies the host-owned step of the current phase: deal (seeded from the platform CSPRNG; the
     * seed never leaves the host), start tribute, open play or settle. Dealing needs all four seats filled.
     */
    fun advance(): AdvanceResult {
        if (!active) return AdvanceResult.RoomNotActive(status)
        val command = when (state.phase) {
            MatchPhase.AWAITING_DEAL -> {
                if (Seat.entries.any { control(it) == SeatControl.Empty }) return AdvanceResult.NotReady
                MatchCommand.DealNext(dealRandom(seed()))
            }
            MatchPhase.DEALT -> MatchCommand.StartTribute
            MatchPhase.READY -> MatchCommand.OpenPlay
            MatchPhase.DEAL_ENDED -> MatchCommand.Settle
            else -> return AdvanceResult.NothingToDo
        }
        val result = reducer.reduce(state, command)
        val accepted = result as? MatchReduceResult.Accepted ?: error("host step $command refused: $result")
        val ok = commit(accepted, null, null)
        pumpAll()
        return if (ok) AdvanceResult.Applied(command.toString(), state.revision) else AdvanceResult.RoomNotActive(status)
    }

    /**
     * Starts a new epoch: closes every connection, forgets every member, capability and grant, and
     * starts a new match. Old callbacks and timers can no longer close, change or act on anything.
     */
    fun restartRoom() {
        check(journal == null) { "a saved table is not restarted in place; create a new table" }
        for (c in connections.values.toList()) close(c, CloseReason.EPOCH_ENDED)
        connections.clear()
        members.clear()
        seats.clear()
        aiPolicies.clear()
        grants.clear()
        readyMembers.clear()
        aiScheduledFor.clear()
        cancelAiInFlight()
        pauseReasons.clear()
        ended = null
        heartbeatTimer?.cancel()
        heartbeatTimer = null
        epoch++
        // The room keeps counting matches across epochs, so (match number, deal number) stays unique per room.
        state = reducer.newMatch(state.matchNumber + 1)
    }

    // ---- Transport events --------------------------------------------------------------------------

    private inline fun guarded(id: Long, ep: Long, block: (Conn) -> Unit) {
        val c = connections[id]
        if (ep != epoch || c == null || c.epoch != ep) {
            metrics.staleCallbacks++
            return
        }
        block(c)
        pumpAll()
    }

    private fun onChunk(conn: Conn, chunk: ByteArray) {
        val inbound = conn.link.receive(chunk) ?: return
        when (inbound) {
            is Inbound.Bad -> {
                metrics.violations[inbound.violation] = (metrics.violations[inbound.violation] ?: 0) + 1
                if (inbound.violation.fatal) close(conn, CloseReason.PROTOCOL_VIOLATION)
            }
            is Inbound.Plain -> if (rateOk(conn)) onPlain(conn, inbound.message)
            is Inbound.Sealed -> if (rateOk(conn)) {
                conn.lastInboundAt = scheduler.now
                onSealed(conn, inbound.message)
            }
        }
    }

    private fun rateOk(conn: Conn): Boolean {
        val now = scheduler.now
        if (now - conn.windowStart >= 1_000) {
            conn.windowStart = now
            conn.framesInWindow = 0
        }
        if (++conn.framesInWindow > config.maxFramesPerSecond) {
            close(conn, CloseReason.RATE_LIMITED)
            return false
        }
        return true
    }

    private fun onPlain(conn: Conn, m: HandshakeMessage) {
        if (m !is Hello) return close(conn, CloseReason.PROTOCOL_VIOLATION)
        if (conn.phase != Phase.AWAITING_HELLO) {
            // A retried hello with the same key gets the same welcome; anything else is ignored.
            if (conn.hello?.publicKey == m.publicKey) send(conn, OutKind.CONTROL, welcome(conn)) else metrics.ignored++
            return
        }
        conn.lastInboundAt = scheduler.now
        val version = minOf(m.protocolMax, config.protocolMax)
        val problem = when {
            version < maxOf(m.protocolMin, config.protocolMin) -> HelloRejectReason.VERSION_UNSUPPORTED to "host speaks ${config.protocolMin}..${config.protocolMax}"
            m.roomId != config.roomId -> HelloRejectReason.ROOM_MISMATCH to "no such room here"
            m.baseProfileHash != baseHash || m.matchProfileHash != matchHash -> HelloRejectReason.PROFILE_MISMATCH to "rule profile hash differs"
            unadmittedCount() > config.maxUnadmitted -> HelloRejectReason.ROOM_FULL to "too many joiners"
            else -> null
        }
        if (problem != null) return rejectHello(conn, problem.first, problem.second)
        val keyPair = crypto.keyAgreement.generate()
        val nonce = crypto.random.next(SessionKeys.NONCE_BYTES)
        val keys = try {
            val peer = B64.decode(m.publicKey) ?: throw CryptoFailure("bad key encoding")
            SessionKeys.derive(
                crypto.hkdf, crypto.keyAgreement.agree(keyPair, peer), crypto.keyAgreement.agree(hostKey.pair, peer),
                config.roomId, epoch, peer, keyPair.publicKey, hostKey.publicKey, nonce,
            )
        } catch (_: CryptoFailure) {
            return rejectHello(conn, HelloRejectReason.MALFORMED, "bad public key")
        }
        conn.hello = m
        conn.keyPair = keyPair
        conn.keys = keys
        conn.nonce = nonce
        conn.link.installKeys(
            Sealer(crypto.aead, keys.hostToClient, Direction.HOST_TO_CLIENT),
            Opener(crypto.aead, keys.clientToHost, Direction.CLIENT_TO_HOST),
        )
        conn.phase = Phase.AWAITING_JOIN
        send(conn, OutKind.CONTROL, welcome(conn))
    }

    private fun welcome(conn: Conn) = Welcome(
        config.protocolMax.coerceAtMost(checkNotNull(conn.hello).protocolMax), config.roomId, epoch,
        B64.encode(checkNotNull(conn.keyPair).publicKey), B64.encode(hostKey.publicKey), B64.encode(checkNotNull(conn.nonce)), policy.view(),
    )

    private fun rejectHello(conn: Conn, reason: HelloRejectReason, message: String) {
        metrics.helloRejected++
        send(conn, OutKind.CONTROL, HelloRejected(reason, config.protocolMin, config.protocolMax, message))
        conn.link.pump()
        dropConnection(conn)
    }

    private fun onSealed(conn: Conn, m: WireMessage) {
        when (m) {
            is Join -> onJoin(conn, m)
            is CommandMessage -> admittedMember(conn)?.let { onCommand(conn, it, m) } ?: run { metrics.ignored++ }
            is Resync -> admittedMember(conn)?.let { onResync(conn, it, m) } ?: run { metrics.ignored++ }
            is Heartbeat -> admittedMember(conn)?.let { memberPresents(it, m.revision) }
            else -> close(conn, CloseReason.PROTOCOL_VIOLATION)
        }
    }

    private fun admittedMember(conn: Conn): Member? =
        if (conn.phase == Phase.ADMITTED) conn.memberId?.let { members[it] }?.takeIf { it.connId == conn.id } else null

    private fun onJoin(conn: Conn, j: Join) {
        when (conn.phase) {
            Phase.ADMITTED -> admittedMember(conn)?.let { send(conn, OutKind.CONTROL, admittedMessage(it)) }
            Phase.PENDING -> send(conn, OutKind.CONTROL, AdmissionPending(epoch))
            Phase.AWAITING_JOIN -> firstJoin(conn, j)
            Phase.AWAITING_HELLO -> metrics.ignored++
        }
    }

    private fun firstJoin(conn: Conn, j: Join) {
        // The host ended the table (also after a restore): its capabilities ended with it, and nobody new joins.
        if (ended == EndReason.HOST_ENDED) return close(conn, CloseReason.EPOCH_ENDED)
        if (j.epoch != epoch) run { deny(conn, AdmissionDenial.CAPABILITY_INVALID); return }
        val role = checkNotNull(conn.hello).role
        val proof = j.rejoinProof
        if (proof != null) {
            val bytes = B64.decode(proof)
            val member = bytes?.let { b -> provenMember(conn, b) }
            if (member == null || member.role != role) run { deny(conn, AdmissionDenial.CAPABILITY_INVALID); return }
            conn.join = j
            metrics.rejoined++
            bind(member, conn)
            return
        }
        when (role) {
            Role.PLAYER -> {
                val seat = j.seat ?: run { deny(conn, AdmissionDenial.ROLE_MISMATCH); return }
                if (!seatOpen(seat) && !j.replace) run { deny(conn, AdmissionDenial.SEAT_TAKEN); return }
                if (connections.values.any { it !== conn && it.phase == Phase.PENDING && it.join?.seat == seat }) run { deny(conn, AdmissionDenial.SEAT_TAKEN); return }
            }
            Role.OBSERVER -> {
                if (j.seat != null || j.replace) run { deny(conn, AdmissionDenial.ROLE_MISMATCH); return }
                // Checked here, not at hello: a rejoining observer (capability above) already counts.
                if (observerCount() >= config.maxObservers) run { deny(conn, AdmissionDenial.OBSERVERS_FULL); return }
            }
        }
        conn.join = j
        conn.phase = Phase.PENDING
        send(conn, OutKind.CONTROL, AdmissionPending(epoch))
    }

    /**
     * The member whose capability keys [proof] over this connection's transcript (G2), or null. Every
     * member is checked with a constant-time comparison; a proof made for another nonce, host key or
     * connection matches nobody.
     */
    private fun provenMember(conn: Conn, proof: ByteArray): Member? {
        val transcript = RejoinProof.transcript(
            config.roomId, epoch, checkNotNull(conn.nonce), hostKey.fingerprint,
            checkNotNull(B64.decode(checkNotNull(conn.hello).publicKey)), checkNotNull(conn.keyPair).publicKey,
        )
        var found: Member? = null
        for (m in members.values) if (crypto.hmac.equal(RejoinProof.of(crypto.hmac, m.capability, transcript), proof)) found = m
        return found
    }

    private fun deny(conn: Conn, reason: AdmissionDenial): ApprovalResult {
        metrics.denied++
        send(conn, OutKind.CONTROL, AdmissionDenied(epoch, reason))
        close(conn, CloseReason.DENIED)
        return ApprovalResult.Denied(reason)
    }

    /** Admits [member] on [conn]: a superseded older connection of the same member is closed. */
    private fun bind(member: Member, conn: Conn) {
        member.connId?.let { old -> if (old != conn.id) connections[old]?.let { close(it, CloseReason.SUPERSEDED) } }
        member.connId = conn.id
        conn.memberId = member.id
        conn.phase = Phase.ADMITTED
        conn.timers.forEach { it.cancel() }
        conn.timers.clear()
        send(conn, OutKind.CONTROL, admittedMessage(member))
        sendStatus(member)
        sendViews(member)
        member.seat?.let { transition(it, SeatEvent.Connected(member.id)) }
    }

    private fun admittedMessage(m: Member) =
        Admitted(epoch, m.id, m.role, m.seat, B64.encode(m.capability), B64.encode(m.digestKey))

    // ---- Commands ----------------------------------------------------------------------------------

    private fun onCommand(conn: Conn, member: Member, m: CommandMessage) {
        quarantine?.let { return reply(conn, member, m.commandId, reject(m.commandId, RejectionCode.ROOM_QUARANTINED, it.reason.name), cache = false) }
        if (m.epoch != epoch) return reply(conn, member, m.commandId, reject(m.commandId, RejectionCode.STALE_EPOCH, "epoch ${m.epoch}"), cache = false)
        val cap = B64.decode(m.capability)
        if (cap == null || !crypto.hmac.equal(cap, member.capability)) {
            return reply(conn, member, m.commandId, reject(m.commandId, RejectionCode.BAD_CAPABILITY, "capability"), cache = false)
        }
        member.replies[m.commandId]?.let {
            metrics.duplicateCommands++
            send(conn, OutKind.RECEIPT, it)
            return
        }
        val body = m.body
        if (body.isGame) gameCommand(conn, member, m, body) else sessionCommand(conn, member, m.commandId, body)
    }

    private fun gameCommand(conn: Conn, member: Member, m: CommandMessage, body: CommandBody) {
        if (member.role != Role.PLAYER) {
            metrics.observerCommandsRejected++
            return reply(conn, member, m.commandId, reject(m.commandId, RejectionCode.NOT_A_PLAYER, "observers cannot act"))
        }
        val seat = member.seat ?: return reply(conn, member, m.commandId, reject(m.commandId, RejectionCode.NOT_SEATED, "no seat"))
        if (m.expectedRevision > state.revision) {
            memberPresents(member, m.expectedRevision)
            return reply(conn, member, m.commandId, reject(m.commandId, RejectionCode.ROOM_QUARANTINED, QuarantineReason.MEMBER_AHEAD.name), cache = false)
        }
        if (!active) return reply(conn, member, m.commandId, reject(m.commandId, RejectionCode.ROOM_PAUSED, status.toString()), cache = false)
        if (m.expectedRevision != state.revision) {
            return reply(conn, member, m.commandId, reject(m.commandId, RejectionCode.STALE_REVISION, "now ${state.revision}"))
        }
        val command = toMatchCommand(body, seat)
            ?: return reply(conn, member, m.commandId, reject(m.commandId, RejectionCode.MALFORMED, "declaration"))
        when (val result = reducer.reduce(state, command)) {
            is MatchReduceResult.Rejected -> reply(conn, member, m.commandId, reject(m.commandId, RejectionCode.RULE, ruleReason(result.rejection)))
            is MatchReduceResult.Accepted -> if (!commit(result, member, m.commandId)) {
                // Not durable, so not applied and never acknowledged; a retry after recovery is judged afresh
                // (or, if the record did become durable, answered with its original receipt).
                reply(conn, member, m.commandId, reject(m.commandId, RejectionCode.ROOM_PAUSED, PauseReason.STORAGE_FAILURE.name), cache = false)
            }
        }
    }

    private fun toMatchCommand(body: CommandBody, seat: Seat): MatchCommand? = when (body) {
        is PlayBody -> {
            val declaration = try {
                body.declaration?.map { WildcardAssignment(it.rank, it.suit) }
            } catch (_: IllegalArgumentException) {
                return null
            }
            MatchCommand.InDeal(Command.Play(seat, body.cardIds, declaration, body.combinationType))
        }
        PassBody -> MatchCommand.InDeal(Command.Pass(seat))
        is GiveTributeBody -> MatchCommand.GiveTribute(seat, body.cardId)
        is ReturnTributeBody -> MatchCommand.ReturnTribute(seat, body.cardId)
        ResistBody -> MatchCommand.Resist(seat)
        is AskCountBody -> MatchCommand.AskCardCount(seat, body.target)
        else -> error("not a game body: $body")
    }

    /** The rule's type name only: the issuer learns which rule, never another seat's data. */
    private fun ruleReason(r: MatchRejection): String =
        if (r is MatchRejection.Deal) "Deal.${r.rejection::class.simpleName}" else checkNotNull(r::class.simpleName)

    private fun reject(commandId: String, code: RejectionCode, reason: String) =
        RejectionReceipt(epoch, commandId, state.revision, code, reason)

    private fun reply(conn: Conn, member: Member, commandId: String, message: WireMessage, cache: Boolean = true) {
        if (message is RejectionReceipt) metrics.rejections++
        if (cache) remember(member, commandId, message)
        send(conn, OutKind.RECEIPT, message)
    }

    private fun remember(member: Member, commandId: String, message: WireMessage) {
        member.replies[commandId] = message
        while (member.replies.size > config.replyCacheSize) member.replies.remove(member.replies.keys.first())
    }

    /**
     * The single path by which state changes: strictly increasing revision, durable in the journal
     * (record, marker, high-water mark), and only then applied and delivered. Returns false when the
     * save failed: the room pauses, the state is not changed and nothing of the revision is sent.
     */
    private fun commit(accepted: MatchReduceResult.Accepted, issuer: Member?, commandId: String?): Boolean {
        val next = accepted.state
        check(next.revision == state.revision + 1) { "revision would go from ${state.revision} to ${next.revision}" }
        check(next.baseProfileHash == baseHash && next.matchProfileHash == matchHash) { "profile hash changed inside a match" }
        check(active) { "commit while the room is $status" }
        val receiptDigest = issuer?.let { digest(it, next) }
        val hash = journal?.let { MatchStateCodec.hash(next) }
        if (journal != null) {
            val ref = if (issuer != null && commandId != null) CommandRef(issuer.id, commandId, checkNotNull(receiptDigest)) else null
            try {
                journal.commit(next.revision, accepted.events, checkNotNull(hash), ref)
            } catch (e: IOException) {
                storageFailed()
                return false
            }
        }
        val wasLobby = inLobby
        state = next
        metrics.commits++
        if (state.phase == MatchPhase.OVER) ended = EndReason.MATCH_OVER
        carryViewpoints()
        // The lobby opening or closing reaches members before the view that goes with it.
        if (inLobby != wasLobby) broadcastStatus()
        for (member in members.values) sendViews(member)
        if (issuer != null && commandId != null) {
            val receipt = Receipt(epoch, commandId, state.revision, checkNotNull(receiptDigest))
            remember(issuer, commandId, receipt)
            issuer.connId?.let { connections[it] }?.let { send(it, OutKind.RECEIPT, receipt) }
        }
        // A save after the commit (carried viewpoints) may have broken the journal: no checkpoint then.
        if (journal != null && !journal.broken && (state.revision % config.checkpointEvery == 0L || state.phase == MatchPhase.AWAITING_DEAL || state.phase == MatchPhase.OVER)) {
            try {
                journal.writeCheckpoint(state, checkNotNull(hash))
                metrics.checkpoints++
            } catch (_: IOException) {
                // A checkpoint is a cache: the log alone still restores this revision.
                metrics.checkpointFailures++
            }
        }
        cancelAiInFlight()
        scheduleAi()
        return true
    }

    /**
     * Active grants carry into a new deal, so their observers' viewpoints move to it. As at consent,
     * the viewpoint is durable before any private frame of that deal is sent: when it cannot be
     * saved, those grants end (the room pauses) and the observers keep their saved viewpoints.
     */
    private fun carryViewpoints() {
        val moved = grants.values.filter { it.status == GrantStatus.ACTIVE }.mapNotNull { g ->
            members[g.observerId]?.takeIf { it.privateView != state.dealNumber to g.seat }?.let { Triple(g, it, it.privateView) }
        }
        if (moved.isEmpty()) return
        moved.forEach { (g, observer, _) -> observer.privateView = state.dealNumber to g.seat }
        if (persistMembers(members.values.toList(), nextMemberId)) return
        moved.forEach { (g, observer, previous) ->
            observer.privateView = previous
            endGrant(g)
        }
    }

    /** A save failed: the room pauses until it is restarted from storage (which decides what is durable). */
    private fun storageFailed() {
        metrics.storageFailures++
        if (pauseReasons.add(PauseReason.STORAGE_FAILURE)) broadcastStatus()
    }

    /**
     * A member showed it holds revision [revision] (a receipt or snapshot it received). Above the
     * host's revision, acknowledged data was lost (for example a save restored from an older copy):
     * quarantine instead of playing on from a lower revision.
     */
    private fun memberPresents(member: Member, revision: Long?) {
        if (revision == null || revision <= state.revision || quarantine != null) return
        metrics.membersAhead++
        enterQuarantine(QuarantineReason.MEMBER_AHEAD, "member ${member.id} holds revision $revision, room is at ${state.revision}")
    }

    private fun enterQuarantine(reason: QuarantineReason, detail: String) {
        quarantine = RoomStatus.Quarantined(reason, detail)
        try {
            journal?.markQuarantined(reason, detail)
        } catch (_: IOException) {
            metrics.storageFailures++
        }
    }

    // ---- Projections and digests -------------------------------------------------------------------

    /** The member's canonical projection bytes (WI-03/WI-04 projector and codec). */
    private fun projection(member: Member, at: MatchState = state): ByteArray = when (val seat = member.seat) {
        null -> MatchProjectionCodec.encode(projector.spectatorView(at))
        else -> MatchProjectionCodec.encode(projector.memberView(at, seat))
    }

    private fun digest(member: Member, at: MatchState = state): String = B64.encode(crypto.hmac.mac(member.digestKey, projection(member, at)))

    /** Snapshot (and follow snapshot, if the member holds an active grant) to an admitted member. */
    private fun sendViews(member: Member) {
        if (quarantine != null) return
        val conn = member.connId?.let { connections[it] } ?: return
        send(conn, OutKind.SNAPSHOT, Snapshot(epoch, state.revision, projection(member).decodeToString()))
        for (g in grants.values) {
            if (g.observerId == member.id && g.status == GrantStatus.ACTIVE) {
                val view = MatchProjectionCodec.encode(projector.memberView(state, g.seat)).decodeToString()
                send(conn, OutKind.FOLLOW, FollowSnapshot(epoch, state.revision, g.id, g.grantRevision, g.seat, view), g.id)
            }
        }
    }

    private fun onResync(conn: Conn, member: Member, m: Resync) {
        val cap = B64.decode(m.capability)
        if (m.epoch != epoch || cap == null || !crypto.hmac.equal(cap, member.capability)) { metrics.ignored++; return }
        if (scheduler.now - member.lastResyncAt < config.resyncMinIntervalMs) { metrics.resyncsThrottled++; return }
        member.lastResyncAt = scheduler.now
        metrics.resyncs++
        sendStatus(member)
        sendViews(member)
    }

    // ---- Observer grants (PROVISIONAL, G4) ---------------------------------------------------------

    private fun sessionCommand(conn: Conn, member: Member, commandId: String, body: CommandBody) {
        if (body == LeaveBody) return leaveCommand(conn, member, commandId)
        val code: Pair<RejectionCode, String>? = when (body) {
            is FollowRequestBody -> followRequest(member, body.seat)
            is ConsentBody -> consent(member, body)
            is RevokeBody -> revoke(member, body.grantId)
            is ReadyBody -> ready(member, body)
            else -> error("not a session body: $body")
        }
        if (code != null) return reply(conn, member, commandId, reject(commandId, code.first, code.second), cache = code.first != RejectionCode.ROOM_PAUSED)
        reply(conn, member, commandId, Receipt(epoch, commandId, state.revision, digest(member)))
    }

    /**
     * A member leaves. The departure (member gone, seat vacated) is saved before it is acknowledged:
     * an acknowledged leave whose capability a restart restores would not be a departure. When the
     * save fails the room pauses, the member stays, and the issuer gets [RejectionCode.ROOM_PAUSED].
     */
    private fun leaveCommand(conn: Conn, member: Member, commandId: String) {
        val seat = member.seat?.takeIf { control(it).owner == member.id }
        val vacated = seat?.let { SeatRecovery.next(control(it), SeatEvent.Left, recovery) }
        val saved = persistMembers(members.values.filter { it.id != member.id }, nextMemberId) { s -> if (s == seat) checkNotNull(vacated) else control(s) }
        if (!saved) return reply(conn, member, commandId, reject(commandId, RejectionCode.ROOM_PAUSED, PauseReason.STORAGE_FAILURE.name), cache = false)
        reply(conn, member, commandId, Receipt(epoch, commandId, state.revision, digest(member)))
        leave(member)
    }

    /** WI-09: a seated human says it is ready (or not) for the settings it saw. Lobby only. */
    private fun ready(member: Member, body: ReadyBody): Pair<RejectionCode, String>? {
        if (member.role != Role.PLAYER) return RejectionCode.NOT_A_PLAYER to "observers do not ready"
        val seat = member.seat
        if (seat == null || control(seat).owner != member.id) return RejectionCode.NOT_SEATED to "no seat"
        if (!inLobby) return RejectionCode.NOT_IN_LOBBY to "match started"
        if (body.settingsRevision != settingsRevision) return RejectionCode.SETTINGS_CHANGED to "settings $settingsRevision"
        val changed = if (body.ready) readyMembers.add(member.id) else readyMembers.remove(member.id)
        if (changed) broadcastStatus()
        return null
    }

    private fun followRequest(observer: Member, seat: Seat): Pair<RejectionCode, String>? {
        if (observer.role != Role.OBSERVER) return RejectionCode.NOT_A_PLAYER to "only observers follow"
        if (!policy.privateFollow) return RejectionCode.FOLLOW_DISABLED to "room policy"
        if (grants.values.any { it.observerId == observer.id && it.live }) return RejectionCode.ALREADY_FOLLOWING to "revoke first"
        observer.privateView?.let { (deal, viewed) ->
            if (deal == state.dealNumber && viewed != seat) return RejectionCode.ONE_VIEW_PER_DEAL to "viewed $viewed this deal"
        }
        val player = control(seat).owner?.let { members[it] }
            ?: return RejectionCode.NO_CONSENTING_PLAYER to "no human at $seat"
        val g = Grant("g${nextGrantId++}", observer.id, observer.nickname, seat)
        g.grantRevision = ++observer.grantCounter
        grants[g.id] = g
        trimGrants()
        sendStatus(observer)
        sendStatus(player)
        return null
    }

    private fun consent(player: Member, body: ConsentBody): Pair<RejectionCode, String>? {
        val g = grants[body.grantId]
        if (g == null || g.status != GrantStatus.PENDING || player.role != Role.PLAYER || g.seat != player.seat) {
            return RejectionCode.GRANT_UNKNOWN to "no pending grant"
        }
        val observer = members[g.observerId] ?: return RejectionCode.GRANT_UNKNOWN to "observer gone"
        val oneView = observer.privateView.let { it == null || it.first != state.dealNumber || it.second == g.seat }
        val activate = body.approve && oneView
        if (activate && observer.privateView != state.dealNumber to g.seat) {
            // The viewpoint is durable before any private frame is sent: a restart must not grant a second one.
            val previous = observer.privateView
            observer.privateView = state.dealNumber to g.seat
            if (!persistMembers(members.values.toList(), nextMemberId)) {
                observer.privateView = previous
                return RejectionCode.ROOM_PAUSED to PauseReason.STORAGE_FAILURE.name
            }
        }
        g.status = if (activate) GrantStatus.ACTIVE else GrantStatus.DENIED
        g.grantRevision = ++observer.grantCounter
        if (g.status == GrantStatus.ACTIVE) metrics.grantsActivated++
        sendStatus(observer)
        sendStatus(player)
        if (g.status == GrantStatus.ACTIVE) sendViews(observer)
        return null
    }

    private fun revoke(member: Member, grantId: String): Pair<RejectionCode, String>? {
        val g = grants[grantId]
        if (g == null || !g.live || (g.observerId != member.id && !(member.role == Role.PLAYER && member.seat == g.seat))) {
            return RejectionCode.GRANT_UNKNOWN to "no live grant"
        }
        endGrant(g)
        return null
    }

    /**
     * The revocation boundary (P4): from here the host emits no follow frame for [g] and drops the
     * queued ones. Frames already handed to the transport may still arrive; the client discards
     * them by grant revision.
     */
    private fun endGrant(g: Grant) {
        if (!g.live) return
        val observer = members[g.observerId]
        g.status = GrantStatus.REVOKED
        if (observer != null) {
            g.grantRevision = ++observer.grantCounter
            observer.connId?.let { connections[it] }?.let { metrics.followFramesDropped += it.link.dropFollow(g.id) }
            sendStatus(observer)
        }
        control(g.seat).owner?.let { members[it] }?.let(::sendStatus)
        metrics.grantsRevoked++
    }

    private fun trimGrants() {
        val dead = grants.values.filter { !it.live }
        if (grants.size > MAX_GRANTS_KEPT) dead.take(grants.size - MAX_GRANTS_KEPT).forEach { grants.remove(it.id) }
    }

    private fun sendStatus(member: Member) {
        member.statusRevision++
        val conn = member.connId?.let { connections[it] } ?: return
        val visible = grants.values.filter { if (member.role == Role.OBSERVER) it.observerId == member.id else it.seat == member.seat }
            .takeLast(WireValidation.MAX_GRANTS)
            .map { GrantView(it.id, it.observerName, it.seat, it.status, it.grantRevision) }
        send(conn, OutKind.CONTROL, Status(epoch, member.statusRevision, visible, roomView(member)))
    }

    /** Every member's status: the roster or the settings changed (WI-09). */
    private fun broadcastStatus() {
        for (m in members.values.toList()) sendStatus(m)
    }

    private fun roomView(viewer: Member?): RoomView = RoomView(
        lobby = inLobby,
        paused = status is RoomStatus.Paused,
        settings = RoomSettingsView(policy.view(), recovery.graceMs, recovery.afterGrace.mode, recovery.afterLeave.mode),
        settingsRevision = settingsRevision,
        seats = Seat.entries.map(::rosterSeat),
        observers = members.values.filter { it.role == Role.OBSERVER }.map { it.nickname }.take(WireValidation.MAX_OBSERVERS),
        yourSeat = viewer?.seat,
    )

    private fun rosterSeat(seat: Seat): RosterSeat {
        val c = control(seat)
        val owner = c.owner?.let { members[it] }
        val occupant = when (c) {
            SeatControl.Empty -> Occupant.EMPTY
            is SeatControl.Human -> Occupant.HUMAN
            is SeatControl.Absent -> Occupant.ABSENT
            is SeatControl.StandIn -> Occupant.STAND_IN
            is SeatControl.AwaitingPlayer -> Occupant.AWAITING_PLAYER
            SeatControl.Ai -> Occupant.AI
        }
        val ready = c is SeatControl.Human && owner != null && owner.id in readyMembers
        val grace = (c as? SeatControl.Absent)?.let { maxOf(0, it.graceEndsAt - scheduler.now) }
        return RosterSeat(seat, occupant, owner?.nickname, ready, grace)
    }

    // ---- Departure and replacement -----------------------------------------------------------------

    /**
     * A member leaves on purpose: its seat follows [RecoveryPolicy.afterLeave] at once (no grace).
     * The departure is already saved ([leaveCommand]).
     */
    private fun leave(member: Member) {
        readyMembers.remove(member.id)
        removeMember(member)
        member.seat?.let { s -> if (control(s).owner == member.id) transition(s, SeatEvent.Left) }
        member.connId?.let { connections[it] }?.let { close(it, CloseReason.LEFT) }
        broadcastStatus()
    }

    private fun replaceOut(old: Member) {
        readyMembers.remove(old.id)
        removeMember(old)
        old.connId?.let { connections[it] }?.let { close(it, CloseReason.REPLACED) }
        metrics.replacements++
    }

    /** Forgets [member] and its capability; a departing player's seat grants and an observer's own grants end (GDN-072). */
    private fun removeMember(member: Member) {
        for (g in grants.values.toList()) {
            if (g.observerId == member.id || (member.role == Role.PLAYER && g.seat == member.seat)) endGrant(g)
        }
        members.remove(member.id)
    }

    // ---- Seat control: the one recovery transition (WI-06) -----------------------------------------

    private fun control(seat: Seat): SeatControl = seats[seat]?.control ?: SeatControl.Empty

    /** A seat a newcomer may take with approval alone: empty, or vacated by a human who left. */
    private fun seatOpen(seat: Seat): Boolean = control(seat).let { it == SeatControl.Empty || (it.owner == null && it !is SeatControl.Ai) }

    private fun transition(seat: Seat, event: SeatEvent) {
        val old = control(seat)
        val next = SeatRecovery.next(old, event, recovery)
        if (next != old) setControl(seat, next)
    }

    /** Installs [next] with a fresh token: AI tasks and grace timers of the old control become inert. */
    private fun setControl(seat: Seat, next: SeatControl) {
        val token = nextAiToken++
        if (next == SeatControl.Empty) seats.remove(seat) else seats[seat] = Slot(next, token)
        aiScheduledFor.remove(seat)
        cancelAiInFlight(seat)
        metrics.seatTransitions++
        if (next is SeatControl.Absent) {
            val ep = epoch
            scheduler.schedule(maxOf(0, next.graceEndsAt - scheduler.now)) {
                if (ep == epoch && seats[seat]?.token == token) {
                    transition(seat, SeatEvent.GraceElapsed(scheduler.now))
                    pumpAll()
                }
            }
        }
        broadcastStatus()
        scheduleAi()
    }

    /**
     * Saves the member list (capabilities, observers' private viewpoints) and the seats nobody owns
     * but that are not empty (AI, stand-in or awaiting a player). [controlOf] lets a caller save a
     * seating before applying it. False, and the room paused, when the save fails.
     */
    private fun persistMembers(list: List<Member>, nextId: Long, controlOf: (Seat) -> SeatControl = ::control): Boolean {
        val j = journal ?: return true
        if (j.broken) return false.also { storageFailed() }
        val file = MembersFile(
            nextMemberId = nextId,
            members = list.map {
                val view = it.privateView?.let { (deal, seat) -> StoredPrivateView(deal, seat) }
                StoredMember(it.id, it.role, it.seat, it.nickname, B64.encode(it.capability), B64.encode(it.digestKey), view)
            },
            aiSeats = Seat.entries.filter { controlOf(it) == SeatControl.Ai },
            standInSeats = Seat.entries.filter { controlOf(it) == SeatControl.StandIn(null) },
            awaitingSeats = Seat.entries.filter { controlOf(it) == SeatControl.AwaitingPlayer(null) },
        )
        return try {
            j.writeMembers(file)
            true
        } catch (_: IOException) {
            storageFailed()
            false
        }
    }

    // ---- AI seats (audit A21) ----------------------------------------------------------------------

    private fun actingSeats(): List<Seat> {
        val s = state
        return when (s.phase) {
            MatchPhase.PLAYING -> listOfNotNull(s.deal?.turn)
            MatchPhase.TRIBUTE -> s.tribute?.let { t -> t.obligation.givers.filter { it !in t.given } }.orEmpty()
            MatchPhase.RETURN -> s.tribute?.let { t -> t.routes.values.filter { it !in t.returns } }.orEmpty()
            else -> emptyList()
        }
    }

    private fun scheduleAi() {
        if (!active) return
        val ep = epoch
        val rev = state.revision
        for (seat in actingSeats()) {
            val slot = seats[seat]?.takeIf { it.control.aiActs } ?: continue
            if (aiScheduledFor[seat] == rev) continue
            aiScheduledFor[seat] = rev
            val token = slot.token
            scheduler.schedule(config.aiDelayMs) { aiTurn(ep, rev, seat, token) }
        }
    }

    private fun aiPolicy(seat: Seat, control: SeatControl): SeatPolicy? = when (control) {
        SeatControl.Ai -> aiPolicies[seat]
        is SeatControl.StandIn -> recovery.substitute
        else -> null
    }

    /** True while an AI task scheduled for (epoch, revision, seat controller token) may still act. */
    private fun aiBindingHolds(ep: Long, rev: Long, seat: Seat, token: Long): Boolean {
        val bound = ep == epoch && rev == state.revision && seats[seat]?.token == token
        // Bound but the room is paused: forget that this turn was scheduled, so the [scheduleAi] that
        // runs when the room is active again (resume, a seat reclaimed) re-arms it instead of skipping it.
        if (bound && !active && aiScheduledFor[seat] == rev) aiScheduledFor.remove(seat)
        return bound && active
    }

    /** Acts only if the epoch, the revision and the seat's controller are all still the ones it was scheduled for. */
    private fun aiTurn(ep: Long, rev: Long, seat: Seat, token: Long) {
        val slot = seats[seat]
        val policy = slot?.let { aiPolicy(seat, it.control) }
        if (!aiBindingHolds(ep, rev, seat, token) || policy == null) {
            metrics.staleAiTasks++
            return
        }
        val observation = projector.aiObservation(state, seat)
        val tribute = state.tribute
        val options = SeatOptions(
            plays = reducer.legalActions(state, seat),
            mayPass = observation.deal?.mayPass ?: false,
            tributeCards = reducer.legalTributeCards(state, seat),
            returnCards = reducer.legalReturnCards(state, seat),
            resistanceOpen = tribute != null && seat in tribute.obligation.givers && seat !in tribute.given && reducer.mayResist(state),
        )
        if (policy !is DeferredSeatPolicy) return applyAi(ep, rev, seat, token, policy.choose(observation, options))
        // WI-07: the answer may come later (worker, deadline). It is applied only under the same binding, once.
        aiInFlight.remove(seat)?.cancel()
        var delivered = false
        var task: Cancellable? = null
        val started = policy.start(observation, options) { body ->
            if (delivered) {
                metrics.aiDuplicateDeliveries++
            } else {
                delivered = true
                // Only this task's own entry: a late answer of a cancelled task must not forget a newer one.
                if (task != null && aiInFlight[seat] === task) aiInFlight.remove(seat)
                applyAi(ep, rev, seat, token, body)
            }
        }
        task = started
        if (!delivered) aiInFlight[seat] = started
    }

    private fun applyAi(ep: Long, rev: Long, seat: Seat, token: Long, body: CommandBody) {
        if (!aiBindingHolds(ep, rev, seat, token)) {
            metrics.staleAiTasks++
            return
        }
        val command = if (body.isGame) toMatchCommand(body, seat) else null
        val result = command?.let { reducer.reduce(state, it) }
        if (result is MatchReduceResult.Accepted) commit(result, null, null) else metrics.aiRejected++
        pumpAll()
    }

    /** Cancels AI decisions still running: their binding (revision or controller) is gone. */
    private fun cancelAiInFlight(seat: Seat? = null) {
        val stale = if (seat == null) aiInFlight.keys.toList() else listOf(seat)
        for (s in stale) aiInFlight.remove(s)?.let { it.cancel(); metrics.aiCancelled++ }
    }

    // ---- Plumbing ----------------------------------------------------------------------------------

    private fun send(conn: Conn, kind: OutKind, message: WireMessage, grantId: String? = null) {
        val privateBytes = message is Snapshot || message is FollowSnapshot || message is Status || message is Receipt || message is Admitted
        check(!privateBytes || conn.phase == Phase.ADMITTED || message is Admitted) { "private ${message::class.simpleName} to an unadmitted peer" }
        if (!conn.link.enqueue(kind, message, grantId)) close(conn, CloseReason.QUEUE_OVERFLOW)
    }

    private fun pumpAll() {
        for (c in connections.values.toList()) c.link.pump()
    }

    private fun close(conn: Conn, reason: CloseReason) {
        if (connections[conn.id] !== conn) return
        if (conn.link.sealed && reason != CloseReason.QUEUE_OVERFLOW) {
            conn.link.enqueue(OutKind.CONTROL, Closing(epoch, reason))
            conn.link.pump()
        }
        metrics.closed[reason] = (metrics.closed[reason] ?: 0) + 1
        dropConnection(conn)
    }

    /** Removes [conn]. Its member, if any, stays seated but disconnected: grace, then the recovery policy. */
    private fun drop(conn: Conn) {
        if (connections[conn.id] !== conn) return
        metrics.transportClosed++
        dropConnection(conn)
    }

    private fun dropConnection(conn: Conn) {
        connections.remove(conn.id)
        conn.timers.forEach { it.cancel() }
        conn.memberId?.let { members[it] }?.let { m ->
            if (m.connId == conn.id) {
                m.connId = null
                m.seat?.let { s -> if (control(s).owner == m.id) transition(s, SeatEvent.Disconnected(scheduler.now)) }
            }
        }
        conn.link.close()
    }

    private fun ensureHeartbeat() {
        if (heartbeatTimer != null) return
        val ep = epoch
        fun tick() {
            if (ep != epoch) return
            val now = scheduler.now
            for (c in connections.values.toList()) {
                if (c.phase == Phase.AWAITING_HELLO) continue
                if (now - c.lastInboundAt > config.livenessTimeoutMs) {
                    metrics.heartbeatTimeouts++
                    close(c, CloseReason.HEARTBEAT_TIMEOUT)
                    continue
                }
                val member = admittedMember(c)
                send(c, OutKind.HEARTBEAT, Heartbeat(epoch, member?.takeIf { quarantine == null }?.let { state.revision }, member?.statusRevision))
            }
            pumpAll()
            heartbeatTimer = scheduler.schedule(config.heartbeatIntervalMs) { tick() }
        }
        heartbeatTimer = scheduler.schedule(config.heartbeatIntervalMs) { tick() }
    }

    private fun unadmittedCount() = connections.values.count { it.phase != Phase.ADMITTED }

    private fun observerCount() = members.values.count { it.role == Role.OBSERVER } +
        connections.values.count { it.phase == Phase.PENDING && it.hello?.role == Role.OBSERVER }

    private fun seed(): Long = crypto.random.next(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

    // ---- Restore (WI-06) ---------------------------------------------------------------------------

    /** Installs a recovered table: every human seat goes through the same disconnect transition as live. */
    private fun installRestored(t: RecoveredTable) {
        epoch = t.meta.epoch
        state = t.state
        installMembers(t.members)
        for ((revision, ref) in t.commands) {
            members[ref.memberId]?.let { remember(it, ref.commandId, Receipt(epoch, ref.commandId, revision, ref.digest)) }
        }
        // The policy of an AI seat is not saved: the substitute plays it. Without one the seat waits
        // for a player (the room pauses, anyone may take it) instead of silently becoming empty.
        val substitute = recovery.substitute
        for (seat in t.members.aiSeats) {
            if (substitute != null) {
                aiPolicies[seat] = substitute
                seats[seat] = Slot(SeatControl.Ai, nextAiToken++)
            } else {
                seats[seat] = Slot(SeatControl.AwaitingPlayer(null), nextAiToken++)
            }
        }
        // Seats whose human left: the stand-in keeps playing (if a substitute exists), or the room waits.
        for (seat in t.members.standInSeats) {
            seats[seat] = Slot(if (substitute != null) SeatControl.StandIn(null) else SeatControl.AwaitingPlayer(null), nextAiToken++)
        }
        for (seat in t.members.awaitingSeats) seats[seat] = Slot(SeatControl.AwaitingPlayer(null), nextAiToken++)
        for (m in members.values) {
            val seat = m.seat ?: continue
            seats[seat] = Slot(SeatControl.Human(m.id), nextAiToken++)
            // Nobody is connected after a restart: grace, then the policy (audit A11: never stuck).
            transition(seat, SeatEvent.Disconnected(scheduler.now))
        }
        when (t.meta.status) {
            TableStatus.ENDED -> ended = EndReason.HOST_ENDED
            else -> if (state.phase == MatchPhase.OVER) ended = EndReason.MATCH_OVER
        }
        scheduleAi()
    }

    private fun installQuarantined(q: RecoveryResult.Quarantined) {
        epoch = q.meta?.epoch ?: epoch
        q.members?.let(::installMembers)
        quarantine = RoomStatus.Quarantined(q.reason, q.detail)
    }

    private fun installMembers(file: MembersFile) {
        nextMemberId = file.nextMemberId
        for (sm in file.members) {
            members[sm.id] = Member(
                id = sm.id, role = sm.role, seat = sm.seat, nickname = sm.nickname,
                capability = checkNotNull(B64.decode(sm.capability)) { "stored capability is not base64url" },
                digestKey = checkNotNull(B64.decode(sm.digestKey)) { "stored digest key is not base64url" },
            ).also { m -> m.privateView = sm.privateView?.let { it.dealNumber to it.seat } }
        }
    }

    /** Outcome of [restore]. Only [Restored] can be played on; [Quarantined] still answers rejoining members. */
    sealed interface RestoreResult {
        data class Restored(val host: SessionHost, val report: RecoveryReport) : RestoreResult
        data class Quarantined(val host: SessionHost, val reason: QuarantineReason, val detail: String) : RestoreResult

        /** Saved by a newer app version: left untouched. */
        data class FutureSchema(val version: Int, val where: String) : RestoreResult
        data object Missing : RestoreResult

        /** Reading or the first write (raising the high-water mark) failed; nothing was played. */
        data class StorageFailed(val cause: IOException) : RestoreResult
    }

    companion object {
        private const val MAX_GRANTS_KEPT = 64

        /** Creates the saved table [tableId] in [storage] (wall-clock [now]) and its host. */
        fun create(
            config: HostConfig, base: RuleProfile, match: RuleProfile, crypto: CryptoSuite, scheduler: Scheduler,
            storage: Storage, tableId: String, now: Long,
        ): SessionHost = create(config, base, match, crypto, scheduler, storage, tableId, now, ::SplitMix64)

        internal fun create(
            config: HostConfig, base: RuleProfile, match: RuleProfile, crypto: CryptoSuite, scheduler: Scheduler,
            storage: Storage, tableId: String, now: Long, dealRandom: (Long) -> RandomSource,
        ): SessionHost {
            val meta = TableMeta(STORAGE_SCHEMA_VERSION, tableId, 1, base.hash, match.hash, now, TableStatus.ACTIVE, now, null)
            // The room key is durable before the table exists: a restored table proves the same identity.
            val roomKey = crypto.keyAgreement.generate()
            return SessionHost(config, base, match, crypto, scheduler, dealRandom, TableJournal.create(storage, meta, crypto.keyAgreement.export(roomKey)), roomKey)
        }

        /**
         * Restarts the saved table [tableId] from its event log ([TableRecovery]). A suspended table
         * becomes active again (wall-clock [now]). Never resumes below the high-water mark.
         */
        fun restore(
            config: HostConfig, base: RuleProfile, match: RuleProfile, crypto: CryptoSuite, scheduler: Scheduler,
            storage: Storage, tableId: String, now: Long,
        ): RestoreResult = restore(config, base, match, crypto, scheduler, storage, tableId, now, ::SplitMix64)

        internal fun restore(
            config: HostConfig, base: RuleProfile, match: RuleProfile, crypto: CryptoSuite, scheduler: Scheduler,
            storage: Storage, tableId: String, now: Long, dealRandom: (Long) -> RandomSource,
        ): RestoreResult {
            val result = try {
                TableRecovery.recover(storage, tableId, base, match)
            } catch (e: IOException) {
                return RestoreResult.StorageFailed(e)
            }
            return when (result) {
                RecoveryResult.Missing -> RestoreResult.Missing
                is RecoveryResult.FutureSchema -> RestoreResult.FutureSchema(result.version, result.where)
                is RecoveryResult.Quarantined -> quarantined(config, base, match, crypto, scheduler, dealRandom, result)
                is RecoveryResult.Recovered -> {
                    val t = result.table
                    val roomKey = try {
                        crypto.keyAgreement.import(t.hostKey)
                    } catch (_: CryptoFailure) {
                        val q = RecoveryResult.Quarantined(QuarantineReason.HOST_KEY_UNREADABLE, "room key does not decode", t.meta, t.members, t.state.revision, null)
                        try {
                            t.journal.markQuarantined(q.reason, q.detail)
                        } catch (e: IOException) {
                            return RestoreResult.StorageFailed(e)
                        }
                        return quarantined(config, base, match, crypto, scheduler, dealRandom, q)
                    }
                    if (t.meta.status == TableStatus.SUSPENDED) {
                        try {
                            t.journal.writeMeta(t.meta.copy(status = TableStatus.ACTIVE, statusAt = now, expiresAt = null))
                        } catch (e: IOException) {
                            return RestoreResult.StorageFailed(e)
                        }
                    }
                    val host = SessionHost(config, base, match, crypto, scheduler, dealRandom, t.journal, roomKey)
                    host.installRestored(t)
                    RestoreResult.Restored(host, t.report)
                }
            }
        }

        /** A host that answers rejoining members of a quarantined table, with its stored room key when that still decodes. */
        private fun quarantined(
            config: HostConfig, base: RuleProfile, match: RuleProfile, crypto: CryptoSuite, scheduler: Scheduler,
            dealRandom: (Long) -> RandomSource, q: RecoveryResult.Quarantined,
        ): RestoreResult.Quarantined {
            val roomKey = q.hostKey?.let { k ->
                try {
                    crypto.keyAgreement.import(k)
                } catch (_: CryptoFailure) {
                    null
                }
            }
            val host = SessionHost(config, base, match, crypto, scheduler, dealRandom, null, roomKey)
            host.installQuarantined(q)
            return RestoreResult.Quarantined(host, q.reason, q.detail)
        }
    }
}

/** Counters for diagnostics and tests. No hands, seeds, capabilities or keys (P8). */
class HostMetrics {
    var commits = 0L
    var rejections = 0L
    var duplicateCommands = 0L
    var observerCommandsRejected = 0L
    var staleCallbacks = 0L
    var staleAiTasks = 0L
    var aiRejected = 0L
    /** WI-07: deferred AI decisions cancelled because their turn or controller changed. */
    var aiCancelled = 0L
    /** WI-07: a deferred policy delivered twice for one task; the second was ignored. */
    var aiDuplicateDeliveries = 0L
    var admitted = 0L
    var rejoined = 0L
    var replacements = 0L
    var denied = 0L
    var helloRejected = 0L
    var codeMismatches = 0L
    var ignored = 0L
    var resyncs = 0L
    var resyncsThrottled = 0L
    var grantsActivated = 0L
    var grantsRevoked = 0L
    var followFramesDropped = 0L
    var heartbeatTimeouts = 0L
    var transportClosed = 0L
    var storageFailures = 0L
    var checkpoints = 0L
    var checkpointFailures = 0L
    var membersAhead = 0L
    var seatTransitions = 0L
    val violations = HashMap<Violation, Long>()
    val closed = HashMap<CloseReason, Long>()
}
