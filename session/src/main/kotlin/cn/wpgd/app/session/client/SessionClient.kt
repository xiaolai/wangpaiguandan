package cn.wpgd.app.session.client

import cn.wpgd.app.core.match.MatchMemberView
import cn.wpgd.app.core.match.MatchSpectatorView
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.channel.Direction
import cn.wpgd.app.session.channel.Inbound
import cn.wpgd.app.session.channel.Opener
import cn.wpgd.app.session.channel.OutKind
import cn.wpgd.app.session.channel.PeerLink
import cn.wpgd.app.session.channel.Sealer
import cn.wpgd.app.session.crypto.CryptoFailure
import cn.wpgd.app.session.crypto.CryptoSuite
import cn.wpgd.app.session.crypto.HostKey
import cn.wpgd.app.session.crypto.RejoinProof
import cn.wpgd.app.session.crypto.SessionKeys
import cn.wpgd.app.session.framing.FramingLimits
import cn.wpgd.app.session.transport.Cancellable
import cn.wpgd.app.session.transport.Scheduler
import cn.wpgd.app.session.transport.Transport
import cn.wpgd.app.session.transport.TransportListener
import cn.wpgd.app.session.wire.AdmissionDenial
import cn.wpgd.app.session.wire.AdmissionDenied
import cn.wpgd.app.session.wire.AdmissionPending
import cn.wpgd.app.session.wire.Admitted
import cn.wpgd.app.session.wire.B64
import cn.wpgd.app.session.wire.CloseReason
import cn.wpgd.app.session.wire.Closing
import cn.wpgd.app.session.wire.CommandBody
import cn.wpgd.app.session.wire.CommandMessage
import cn.wpgd.app.session.wire.FollowSnapshot
import cn.wpgd.app.session.wire.GrantStatus
import cn.wpgd.app.session.wire.GrantView
import cn.wpgd.app.session.wire.HandshakeMessage
import cn.wpgd.app.session.wire.Heartbeat
import cn.wpgd.app.session.wire.Hello
import cn.wpgd.app.session.wire.HelloRejected
import cn.wpgd.app.session.wire.Join
import cn.wpgd.app.session.wire.PROTOCOL_VERSION
import cn.wpgd.app.session.wire.Receipt
import cn.wpgd.app.session.wire.RejectionReceipt
import cn.wpgd.app.session.wire.Resync
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.session.wire.RoomPolicyView
import cn.wpgd.app.session.wire.RoomView
import cn.wpgd.app.session.wire.Snapshot
import cn.wpgd.app.session.wire.Status
import cn.wpgd.app.session.wire.Welcome
import cn.wpgd.app.session.wire.WireMessage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

data class ClientConfig(
    val roomId: String,
    val role: Role,
    val nickname: String,
    val baseProfileHash: String,
    val matchProfileHash: String,
    /** The seat a player claims; null for observers. */
    val seat: Seat? = null,
    /** Ask to take over an occupied seat (host-approved replacement). */
    val replace: Boolean = false,
    /**
     * A capability stored from an earlier admission in the same epoch: rejoin without approval. It
     * is never sent: the join carries a proof keyed with it (G2). Needs [pinnedHostKey].
     */
    val rejoinCapability: String? = null,
    /**
     * The host key fingerprint (base64url) pinned with the rejoin ticket when it was first issued: a
     * welcome carrying any other room key is refused before anything is sent (G2).
     */
    val pinnedHostKey: String? = null,
    /**
     * The highest committed revision this member held before (a stored receipt or snapshot), or -1.
     * Presented in heartbeats so a host restored below it quarantines instead of playing on (WI-06).
     */
    val heldRevision: Long = -1,
    val protocolMin: Int = PROTOCOL_VERSION,
    val protocolMax: Int = PROTOCOL_VERSION,
    val limits: FramingLimits = FramingLimits(),
    val queueLimit: Int = 64,
    /**
     * Unanswered hello, join and commands are resent (same ids: idempotent), first after [retryMs],
     * then with the interval doubling up to [maxRetryMs], so retries never saturate a slow link.
     */
    val retryMs: Long = 400,
    val maxRetryMs: Long = 3_200,
    val heartbeatIntervalMs: Long = 1_000,
    val livenessTimeoutMs: Long = 5_000,
    val resyncMinIntervalMs: Long = 300,
) {
    init {
        require(protocolMin >= 1 && protocolMax >= protocolMin) { "protocol range $protocolMin..$protocolMax" }
        require(rejoinCapability == null || pinnedHostKey != null) { "a rejoin ticket needs the host key it was issued by" }
        require(heldRevision >= -1) { "heldRevision $heldRevision" }
        require(queueLimit > 0) { "queueLimit must be positive" }
        // Zero intervals would reschedule in a loop at the same instant; negative ones reach the scheduler.
        require(retryMs > 0 && maxRetryMs >= retryMs) { "retry $retryMs..$maxRetryMs" }
        require(heartbeatIntervalMs > 0 && livenessTimeoutMs > 0) { "heartbeat and liveness must be positive" }
        require(resyncMinIntervalMs >= 0) { "resyncMinIntervalMs must not be negative" }
    }
}

enum class ClientPhase { IDLE, HELLO_SENT, JOINING, PENDING, ADMITTED, REJECTED, CLOSED }

/**
 * One member's end of a session. Single-threaded on the [scheduler].
 *
 * - Never regresses: a snapshot older than the current revision is ignored.
 * - Verifies each receipt's keyed digest against its own projection at that revision; a mismatch
 *   is counted and triggers a resync.
 * - Observers keep a follow view only while the matching grant is ACTIVE at the exact grant
 *   revision; any follow frame with another grant revision is discarded (P4).
 * - Every [connect] is a new connection generation; callbacks of older ones are ignored (A18).
 */
class SessionClient(
    /** What this member asked for (role, seat, nickname). Contains the rejoin capability: keep it on the device. */
    val config: ClientConfig,
    private val crypto: CryptoSuite,
    private val scheduler: Scheduler,
) {
    var phase: ClientPhase = ClientPhase.IDLE
        private set
    var helloRejection: HelloRejected? = null
        private set
    var denial: AdmissionDenial? = null
        private set
    var closeReason: CloseReason? = null
        private set

    /** The six-digit code to show the host user, once keys exist. */
    var confirmationCode: String? = null
        private set
    var policy: RoomPolicyView? = null
        private set
    var epoch: Long? = null
        private set
    var memberId: String? = null
        private set
    var seat: Seat? = null
        private set

    /** Base64url capability; kept for rejoin within the same epoch. */
    var capability: String? = null
        private set

    /**
     * The configured rejoin ticket ([ClientConfig.rejoinCapability]), for the first epoch only. It is
     * dropped whenever the capability it stands for ends (a new epoch, replacement, epoch ended), so
     * a later join asks for fresh admission instead of presenting an obsolete proof.
     */
    private var configuredTicket: String? = config.rejoinCapability

    /**
     * The host key fingerprint this client is pinned to (base64url): the configured pin, else the
     * first welcome's. Every later welcome on any connection of this client must carry the same key.
     * Store it with the capability as the rejoin ticket.
     */
    var hostKeyFingerprint: String? = config.pinnedHostKey
        private set
    private var digestKey: ByteArray? = null

    /** Revision of the last applied snapshot; -1 before the first. */
    var revision: Long = -1
        private set

    /**
     * The highest committed revision this member has evidence of: snapshots, receipts and
     * [ClientConfig.heldRevision]. Sent in every heartbeat (WI-06: a host below it must quarantine).
     */
    var heldRevision: Long = config.heldRevision
        private set

    /** The last applied projection, exactly as received (the digest covers these bytes). */
    var viewJson: String? = null
        private set
    var memberView: MatchMemberView? = null
        private set
    var spectatorView: MatchSpectatorView? = null
        private set

    data class Follow(val grantId: String, val grantRevision: Long, val seat: Seat, val revision: Long, val view: MatchMemberView)

    var follow: Follow? = null
        private set
    var grants: List<GrantView> = emptyList()
        private set

    /** WI-09: the room roster and settings from the latest status; null before the first. */
    var room: RoomView? = null
        private set
    var statusRevision: Long = 0
        private set

    /** True when the host has been silent past the liveness limit: controls must be disabled (GDN-088). */
    var stale: Boolean = false
        private set

    /** Final replies by command id. */
    val replies = LinkedHashMap<String, WireMessage>()

    /** Replies that arrived after the command was already answered (retries, duplicates), newest last. */
    val repeatedReplies = ArrayList<WireMessage>()
    private val sent = LinkedHashMap<String, CommandMessage>()
    private val pending = LinkedHashMap<String, Pair<CommandMessage, Cancellable>>()
    val pendingCommands: Int get() = pending.size

    val metrics = ClientMetrics()

    /** Called after anything the UI shows may have changed. */
    var onUpdate: (() -> Unit)? = null

    /** Test seam: sees every decoded message from the host, plain or sealed, before it is handled. */
    internal var tap: ((WireMessage) -> Unit)? = null

    /** Test seam: sees every message this client queues for the host, plain or sealed. */
    internal var sentTap: ((WireMessage) -> Unit)? = null

    /** Test seam: a decoded message for which this returns true is lost (after [tap]), as if never delivered. */
    internal var loseInbound: ((WireMessage) -> Boolean)? = null

    /** Test seam: replaces the join this client sends (a replayed or forged proof). */
    internal var rewriteJoin: ((Join) -> Join)? = null

    private var generation = 0L
    private var link: PeerLink? = null
    private var keyPair: cn.wpgd.app.session.crypto.EphemeralKeyPair? = null
    private val digests = LinkedHashMap<Long, ByteArray>()
    private val unverified = HashMap<Long, Pair<String, ByteArray>>()

    /** Test seam: receipts waiting for their revision's snapshot to be verified. */
    internal val deferredReceiptDigests: Int get() = unverified.size
    private var lastInboundAt = 0L
    private var lastResyncAt = Long.MIN_VALUE / 2
    private var previousHeartbeat: Heartbeat? = null
    private val timers = ArrayList<Cancellable>()
    private var nextCommand = 1L
    private val commandPrefix = B64.encode(crypto.random.next(9))

    private val strict = Json { ignoreUnknownKeys = false }

    /** Opens a new connection generation on [transport] and starts the handshake. */
    fun connect(transport: Transport) {
        disposeConnection()
        val gen = ++generation
        val l = PeerLink(transport, config.limits, config.queueLimit)
        link = l
        // Failures belong to the connection that had them: a new generation starts without any.
        helloRejection = null
        denial = null
        closeReason = null
        phase = ClientPhase.HELLO_SENT
        stale = false
        lastInboundAt = scheduler.now
        transport.open(object : TransportListener {
            override fun onReceive(chunk: ByteArray) = guarded(gen) { onChunk(chunk) }
            override fun onWritable() = guarded(gen) { l.pump() }
            override fun onClosed() = guarded(gen) { connectionClosed(null) }
        })
        val kp = crypto.keyAgreement.generate()
        keyPair = kp
        val hello = Hello(config.protocolMin, config.protocolMax, config.role, config.roomId, config.baseProfileHash, config.matchProfileHash, B64.encode(kp.publicKey))
        repeatWhile(gen, { phase == ClientPhase.HELLO_SENT }) { send(OutKind.CONTROL, hello) }
        val tick = object : () -> Unit {
            override fun invoke() {
                if (gen != generation || phase == ClientPhase.CLOSED || phase == ClientPhase.REJECTED) return
                if (scheduler.now - lastInboundAt > config.livenessTimeoutMs) {
                    stale = true
                    metrics.livenessTimeouts++
                    connectionClosed(CloseReason.HEARTBEAT_TIMEOUT)
                    return
                }
                if (l.sealed) {
                    send(OutKind.HEARTBEAT, Heartbeat(epoch ?: 0, maxOf(revision, heldRevision).takeIf { it >= 0 }, statusRevision))
                    // Nothing else may write soon: an idle link would otherwise hold the heartbeat back.
                    link?.pump()
                }
                later(config.heartbeatIntervalMs, this)
            }
        }
        later(config.heartbeatIntervalMs, tick)
    }

    /**
     * Sends a command acting on the current revision and keeps resending the same message until a
     * receipt or rejection arrives. Returns the command id, or null when not admitted.
     */
    fun submit(body: CommandBody): String? {
        if (phase != ClientPhase.ADMITTED || stale) return null
        val id = "$commandPrefix-${nextCommand++}"
        val message = CommandMessage(checkNotNull(epoch), checkNotNull(capability), id, revision.coerceAtLeast(0), body)
        sent[id] = message
        while (sent.size > REPLY_HISTORY) sent.remove(sent.keys.first())
        pending[id] = message to Cancellable {}
        val cancel = repeatWhile(generation, { id in pending }) {
            metrics.commandSends++
            send(OutKind.CONTROL, message)
        }
        pending[id]?.let { pending[id] = it.first to cancel }
        return id
    }

    /** Sends command [commandId] again, byte for byte the same message, even after its reply arrived. */
    fun resend(commandId: String): Boolean {
        val m = sent[commandId] ?: return false
        if (phase != ClientPhase.ADMITTED) return false
        send(OutKind.CONTROL, m)
        link?.pump()
        return true
    }

    /** Test seam: sends any message, crafted by the test, on the current sealed link. */
    internal fun sendRaw(m: WireMessage) {
        send(OutKind.CONTROL, m)
        link?.pump()
    }

    fun close() {
        phase = ClientPhase.CLOSED
        disposeConnection()
    }

    // ---- Inbound -----------------------------------------------------------------------------------

    private inline fun guarded(gen: Long, block: () -> Unit) {
        if (gen != generation) {
            metrics.staleCallbacks++
            return
        }
        block()
        link?.pump()
        onUpdate?.invoke()
    }

    private fun onChunk(chunk: ByteArray) {
        val l = link ?: return
        when (val inbound = l.receive(chunk) ?: return) {
            is Inbound.Bad -> {
                metrics.violations++
                if (inbound.violation.fatal) connectionClosed(CloseReason.PROTOCOL_VIOLATION)
            }
            is Inbound.Plain -> onPlain(inbound.message)
            is Inbound.Sealed -> {
                lastInboundAt = scheduler.now
                stale = false
                onMessage(inbound.message)
            }
        }
    }

    private fun onPlain(m: HandshakeMessage) {
        tap?.invoke(m)
        // Once keys are installed only authenticated frames prove the host alive: plaintext can come from anyone.
        if (link?.sealed != true) lastInboundAt = scheduler.now
        when (m) {
            is HelloRejected -> if (phase == ClientPhase.HELLO_SENT) {
                helloRejection = m
                phase = ClientPhase.REJECTED
                disposeConnection()
            }
            is Welcome -> if (phase == ClientPhase.HELLO_SENT) onWelcome(m)
            is Hello, is cn.wpgd.app.session.wire.RoomQuery, is cn.wpgd.app.session.wire.RoomListing -> metrics.ignored++
        }
    }

    private fun onWelcome(m: Welcome) {
        if (m.roomId != config.roomId || m.protocolVersion !in config.protocolMin..config.protocolMax) {
            return connectionClosed(CloseReason.PROTOCOL_VIOLATION)
        }
        val kp = checkNotNull(keyPair)
        val roomKey = B64.decode(m.hostKey) ?: return connectionClosed(CloseReason.PROTOCOL_VIOLATION)
        val nonce = B64.decode(m.nonce)?.takeIf { it.size == SessionKeys.NONCE_BYTES } ?: return connectionClosed(CloseReason.PROTOCOL_VIOLATION)
        // G2: a pinned member talks only to the room key it pinned; nothing is sent to any other.
        val fingerprint = HostKey.fingerprintOf(crypto.hmac, roomKey)
        val pin = hostKeyFingerprint
        // A pin that does not decode to a fingerprint matches no key: refuse (fail closed), never skip the check.
        val pinned = pin?.let { B64.decode(it)?.takeIf { b -> b.size == HostKey.FINGERPRINT_BYTES } }
        if (pin != null && (pinned == null || !crypto.hmac.equal(pinned, fingerprint))) {
            metrics.hostKeyMismatches++
            return connectionClosed(CloseReason.HOST_KEY_MISMATCH)
        }
        val hostPublic: ByteArray
        val keys = try {
            hostPublic = B64.decode(m.publicKey) ?: throw CryptoFailure("bad key")
            SessionKeys.derive(
                crypto.hkdf, crypto.keyAgreement.agree(kp, hostPublic), crypto.keyAgreement.agree(kp, roomKey),
                config.roomId, m.epoch, kp.publicKey, hostPublic, roomKey, nonce,
            )
        } catch (_: CryptoFailure) {
            return connectionClosed(CloseReason.PROTOCOL_VIOLATION)
        }
        hostKeyFingerprint = B64.encode(fingerprint)
        if (epoch != m.epoch) {
            // A new epoch: the old capability and state belong to a room that no longer exists.
            capability = null; memberId = null; digestKey = null
            // A revision held in an older epoch says nothing about this one; the configured one is for the first epoch.
            if (epoch != null) { heldRevision = -1; configuredTicket = null }
            revision = -1; viewJson = null; memberView = null; spectatorView = null; follow = null
            grants = emptyList(); room = null; statusRevision = 0; digests.clear(); unverified.clear()
        }
        // Status revisions count per host process: a host restored from its save (same epoch) numbers
        // this member's statuses from 1 again, and its first status follows admission on this connection.
        statusRevision = 0
        epoch = m.epoch
        policy = m.policy
        confirmationCode = keys.confirmationCode
        val l = checkNotNull(link)
        l.installKeys(Sealer(crypto.aead, keys.clientToHost, Direction.CLIENT_TO_HOST), Opener(crypto.aead, keys.hostToClient, Direction.HOST_TO_CLIENT))
        phase = ClientPhase.JOINING
        // The capability never leaves this device: a returning member proves it over this connection's transcript.
        val proof = (capability ?: configuredTicket)?.let { B64.decode(it) }?.let { cap ->
            val transcript = RejoinProof.transcript(config.roomId, m.epoch, nonce, fingerprint, kp.publicKey, hostPublic)
            B64.encode(RejoinProof.of(crypto.hmac, cap, transcript))
        }
        val join = Join(m.epoch, config.nickname, config.seat, config.replace, proof).let { rewriteJoin?.invoke(it) ?: it }
        val gen = generation
        repeatWhile(gen, { phase == ClientPhase.JOINING || phase == ClientPhase.PENDING }) { send(OutKind.CONTROL, join) }
    }

    /** Handles one decoded sealed message. Internal so tests can inject crafted frames. */
    internal fun onMessage(m: WireMessage) {
        tap?.invoke(m)
        if (loseInbound?.invoke(m) == true) return
        when (m) {
            is AdmissionPending -> if (phase == ClientPhase.JOINING) phase = ClientPhase.PENDING
            is Admitted -> onAdmitted(m)
            is AdmissionDenied -> {
                denial = m.reason
                phase = ClientPhase.REJECTED
                disposeConnection()
            }
            is Snapshot -> onSnapshot(m)
            is FollowSnapshot -> onFollow(m)
            is Status -> onStatus(m)
            is Receipt -> {
                if (m.epoch == epoch) heldRevision = maxOf(heldRevision, m.revision)
                onReply(m.commandId, m) { verifyReceipt(m) }
            }
            is RejectionReceipt -> onReply(m.commandId, m) {
                metrics.rejections++
                // The host is ahead of this view: fetch the current one before acting again.
                if (m.revision > revision) requestResync(force = true)
            }
            is Heartbeat -> onHeartbeat(m)
            is Closing -> {
                closeReason = m.reason
                if (m.reason == CloseReason.REPLACED || m.reason == CloseReason.EPOCH_ENDED) { capability = null; configuredTicket = null }
                connectionClosed(m.reason)
            }
            else -> metrics.ignored++
        }
    }

    private fun onAdmitted(m: Admitted) {
        if (m.epoch != epoch) { metrics.staleEpochFrames++; return }
        if (phase == ClientPhase.ADMITTED) return
        val key = B64.decode(m.digestKey) ?: return connectionClosed(CloseReason.PROTOCOL_VIOLATION)
        val moved = seat != null && seat != m.seat
        memberId = m.memberId
        seat = m.seat
        // Readmitted to another seat (the host moved it while offline): the old seat's view is not this member's.
        if (moved) dropSeatView()
        capability = m.capability
        digestKey = key
        phase = ClientPhase.ADMITTED
    }

    private fun onSnapshot(m: Snapshot) {
        if (phase != ClientPhase.ADMITTED || m.epoch != epoch) { metrics.staleEpochFrames++; return }
        if (m.revision < revision) { metrics.olderSnapshotsIgnored++; return }
        if (m.revision == revision && m.view == viewJson) { metrics.duplicateSnapshots++; return }
        val own = seat
        if (own != null) {
            memberView = decodeMemberView(m.view, own) ?: return
        } else {
            val v = try {
                strict.decodeFromString(MatchSpectatorView.serializer(), m.view)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            if (v == null || !hashesMatch(v.match.baseProfileHash, v.match.matchProfileHash)) { metrics.badSnapshots++; return }
            spectatorView = v
        }
        check(m.revision >= revision) { "revision would regress from $revision to ${m.revision}" }
        revision = m.revision
        heldRevision = maxOf(heldRevision, m.revision)
        viewJson = m.view
        val digest = crypto.hmac.mac(checkNotNull(digestKey), m.view.encodeToByteArray())
        digests[m.revision] = digest
        while (digests.size > DIGEST_HISTORY) digests.remove(digests.keys.first())
        unverified.remove(m.revision)?.let { (_, expected) -> compareDigest(expected, digest) }
        // Receipts for revisions that were skipped can no longer be checked.
        val skipped = unverified.keys.filter { it < m.revision }
        skipped.forEach { unverified.remove(it); metrics.unverifiedReceipts++ }
    }

    /**
     * The member view in [json] when it decodes strictly, is [expected]'s seat and carries this
     * room's profile hashes; otherwise counts a bad snapshot and returns null. Snapshots and follow
     * snapshots share it, so both apply the same checks.
     */
    private fun decodeMemberView(json: String, expected: Seat): MatchMemberView? {
        val v = try {
            strict.decodeFromString(MatchMemberView.serializer(), json)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (v == null || v.seat != expected || !hashesMatch(v.match.baseProfileHash, v.match.matchProfileHash)) {
            metrics.badSnapshots++
            return null
        }
        return v
    }

    private fun hashesMatch(base: String, match: String): Boolean {
        val ok = base == config.baseProfileHash && match == config.matchProfileHash
        if (!ok) metrics.profileHashMismatches++
        return ok
    }

    private fun onFollow(m: FollowSnapshot) {
        val active = grants.firstOrNull { it.status == GrantStatus.ACTIVE }
        val current = follow
        if (m.epoch != epoch || active == null || active.grantId != m.grantId || active.grantRevision != m.grantRevision ||
            active.seat != m.seat || (current != null && current.grantId == m.grantId && m.revision < current.revision)
        ) {
            metrics.staleFollowDiscarded++
            return
        }
        val v = decodeMemberView(m.view, m.seat) ?: return
        follow = Follow(m.grantId, m.grantRevision, m.seat, m.revision, v)
    }

    private fun onStatus(m: Status) {
        if (m.epoch != epoch || m.statusRevision <= statusRevision) { metrics.olderStatusIgnored++; return }
        statusRevision = m.statusRevision
        grants = m.grants
        room = m.room
        policy = m.room.settings.policy
        seatMoved(m.room.yourSeat)
        val active = grants.firstOrNull { it.status == GrantStatus.ACTIVE }
        val f = follow
        // Revocation clears the private view at once (GDN-067).
        if (f != null && (active == null || active.grantId != f.grantId || active.grantRevision != f.grantRevision)) {
            follow = null
            metrics.followCleared++
        }
        if (active != null && follow == null && config.role == Role.OBSERVER) requestResync()
    }

    /**
     * WI-09: the host moved this player to [newSeat] in the lobby. The old seat's view and digests no
     * longer describe this member; the new seat's snapshot follows (fetched at once if it was lost).
     */
    private fun seatMoved(newSeat: Seat?) {
        val old = seat
        if (config.role != Role.PLAYER || newSeat == null || old == null || newSeat == old) return
        seat = newSeat
        dropSeatView()
        requestResync(force = true)
    }

    /**
     * The seat changed: its view and digests no longer describe this member. The applied revision
     * goes back to "none" ([heldRevision] keeps the evidence), so until the new seat's snapshot
     * arrives every heartbeat sees this client behind and asks again, even if that snapshot and the
     * first resync are both lost.
     */
    private fun dropSeatView() {
        memberView = null
        viewJson = null
        revision = -1
        digests.clear()
        unverified.clear()
        metrics.seatMoves++
    }

    private fun onReply(commandId: String, m: WireMessage, then: () -> Unit) {
        val p = pending.remove(commandId)
        if (p == null) {
            metrics.duplicateReplies++
            repeatedReplies += m
            while (repeatedReplies.size > REPLY_HISTORY) repeatedReplies.removeAt(0)
            return
        }
        p.second.cancel()
        replies[commandId] = m
        while (replies.size > REPLY_HISTORY) replies.remove(replies.keys.first())
        then()
    }

    private fun verifyReceipt(r: Receipt) {
        val expected = B64.decode(r.digest) ?: return
        val mine = digests[r.revision]
        when {
            mine != null -> compareDigest(expected, mine)
            // The snapshot is usually still in flight (its chunks race the receipt's): verify on arrival.
            r.revision > revision -> {
                unverified[r.revision] = r.commandId to expected
                // Bounded: while snapshots stay undelivered, the oldest waiting receipt is given up.
                while (unverified.size > DIGEST_HISTORY) {
                    unverified.remove(unverified.keys.min())
                    metrics.unverifiedReceipts++
                }
            }
            else -> metrics.unverifiedReceipts++
        }
    }

    private fun compareDigest(expected: ByteArray, mine: ByteArray) {
        if (crypto.hmac.equal(expected, mine)) {
            metrics.digestsVerified++
        } else {
            metrics.digestMismatches++
            requestResync(force = true)
        }
    }

    /**
     * A heartbeat announces the host's committed revision and this member's status revision. If this
     * client is still below what the *previous* heartbeat announced (a full interval ago, so not just
     * in flight), a snapshot, follow snapshot or status was lost: resync.
     */
    private fun onHeartbeat(m: Heartbeat) {
        if (m.epoch != epoch || phase != ClientPhase.ADMITTED) return
        val announced = previousHeartbeat
        previousHeartbeat = m
        if (announced == null) return
        val rev = announced.revision ?: -1
        // Only an observer receives follow snapshots; a player's grants are the ones it gave.
        val followBehind = config.role == Role.OBSERVER && grants.any { it.status == GrantStatus.ACTIVE } && (follow?.revision ?: -1) < rev
        if (revision < rev || statusRevision < (announced.statusRevision ?: 0) || followBehind) requestResync()
    }

    private fun requestResync(force: Boolean = false) {
        if (phase != ClientPhase.ADMITTED) return
        if (!force && scheduler.now - lastResyncAt < config.resyncMinIntervalMs) return
        lastResyncAt = scheduler.now
        metrics.resyncRequests++
        send(OutKind.CONTROL, Resync(checkNotNull(epoch), checkNotNull(capability)))
    }

    // ---- Plumbing ----------------------------------------------------------------------------------

    private fun send(kind: OutKind, m: WireMessage) {
        val l = link ?: return
        sentTap?.invoke(m)
        if (!l.enqueue(kind, m)) connectionClosed(CloseReason.QUEUE_OVERFLOW)
    }

    /** Runs [action] now and again with exponential backoff while [condition] holds for generation [gen]. */
    private fun repeatWhile(gen: Long, condition: () -> Boolean, action: () -> Unit): Cancellable {
        var cancelled = false
        val handle = Cancellable { cancelled = true }
        fun step(delay: Long) {
            if (cancelled || gen != generation || !condition()) return
            action()
            link?.pump()
            later(delay) { step(minOf(delay * 2, config.maxRetryMs)) }
        }
        step(config.retryMs)
        return handle
    }

    /**
     * Schedules [task] as a timer of this connection. Its handle is kept only while it is due, so a
     * long connection holds only the timers still scheduled. A timer that changes what the UI shows
     * (phase or staleness) notifies [onUpdate]: no transport callback follows a closed connection.
     */
    private fun later(delayMs: Long, task: () -> Unit) {
        var handle: Cancellable? = null
        var ran = false
        handle = scheduler.schedule(delayMs) {
            ran = true
            handle?.let { timers.remove(it) }
            val phaseBefore = phase
            val staleBefore = stale
            task()
            if (phase != phaseBefore || stale != staleBefore) onUpdate?.invoke()
        }
        if (!ran) timers += handle
    }

    /** Test seam: the timers this client still holds. */
    internal val scheduledTimers: Int get() = timers.size

    private fun connectionClosed(reason: CloseReason?) {
        if (reason != null && closeReason == null) closeReason = reason
        if (phase != ClientPhase.REJECTED) phase = ClientPhase.CLOSED
        disposeConnection()
    }

    /**
     * Ends the current connection generation: cancels its timers and retries, and closes its
     * transport (after the generation moved on, so the transport's own close callback is stale).
     */
    private fun disposeConnection() {
        timers.forEach { it.cancel() }
        timers.clear()
        for ((_, p) in pending) p.second.cancel()
        pending.clear()
        val old = link
        link = null
        previousHeartbeat = null
        // Grants and the private follow view hold only while the host can revoke them on this
        // connection (GDN-067): one revoked while away must not stay visible through a reconnect.
        // The next connection's first status brings the current grants, and the follow view after it.
        if (follow != null) metrics.followCleared++
        follow = null
        grants = emptyList()
        generation++
        old?.close()
    }

    internal companion object {
        /** Own snapshot digests kept, and receipts kept waiting for their snapshot (each bounded by it). */
        const val DIGEST_HISTORY = 64
        const val REPLY_HISTORY = 512
    }
}

class ClientMetrics {
    var digestsVerified = 0L
    var digestMismatches = 0L
    var unverifiedReceipts = 0L
    var resyncRequests = 0L
    var staleFollowDiscarded = 0L
    var followCleared = 0L
    var olderSnapshotsIgnored = 0L
    var duplicateSnapshots = 0L
    var olderStatusIgnored = 0L
    var badSnapshots = 0L
    var staleEpochFrames = 0L
    var profileHashMismatches = 0L
    var duplicateReplies = 0L
    var rejections = 0L
    var commandSends = 0L
    var staleCallbacks = 0L
    var violations = 0L
    var livenessTimeouts = 0L
    var ignored = 0L
    var seatMoves = 0L
    /** G2: welcomes refused because they carried a room key other than the pinned one. */
    var hostKeyMismatches = 0L
}
