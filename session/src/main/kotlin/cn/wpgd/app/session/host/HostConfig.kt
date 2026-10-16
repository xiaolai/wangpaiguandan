package cn.wpgd.app.session.host

import cn.wpgd.app.core.match.MatchMemberView
import cn.wpgd.app.core.rules.LegalAction
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.framing.FramingLimits
import cn.wpgd.app.session.transport.Cancellable
import cn.wpgd.app.session.wire.CommandBody
import cn.wpgd.app.session.wire.PROTOCOL_VERSION
import cn.wpgd.app.session.wire.RoomMode
import cn.wpgd.app.session.wire.RoomPolicyView

/**
 * Room assistance policy. PROVISIONAL (gate G4 unsigned): the G4 proposed default is that
 * competitive rooms disable live hints and private follow, and friendly rooms allow both (private
 * follow still needs the player's consent). The room default is [FRIENDLY] (ledger default policy 7).
 */
data class RoomPolicy(val mode: RoomMode, val hints: Boolean, val privateFollow: Boolean) {
    fun view() = RoomPolicyView(mode, hints, privateFollow)

    companion object {
        val FRIENDLY = RoomPolicy(RoomMode.FRIENDLY, hints = true, privateFollow = true)
        val COMPETITIVE = RoomPolicy(RoomMode.COMPETITIVE, hints = false, privateFollow = false)
    }
}

/**
 * Host limits and timings (GDN-027, GDN-088). Every value is a bound, so a misbehaving nearby peer
 * costs bounded memory and time.
 */
data class HostConfig(
    val roomId: String,
    /** Grace, then substitution or pause, for absent humans (WI-06). Chosen by the lobby; no default. */
    val recovery: RecoveryPolicy,
    val protocolMin: Int = PROTOCOL_VERSION,
    val protocolMax: Int = PROTOCOL_VERSION,
    val policy: RoomPolicy = RoomPolicy.FRIENDLY,
    val limits: FramingLimits = FramingLimits(),
    /** Outbound messages queued per peer before snapshots are superseded, then the peer is closed. */
    val queueLimit: Int = 64,
    /** A new connection must complete hello and join within this time (three one-way trips plus retries). */
    val helloDeadlineMs: Long = 10_000,
    val heartbeatIntervalMs: Long = 1_000,
    /** No valid frame for this long: the peer is declared dead (the blackhole detection limit). */
    val livenessTimeoutMs: Long = 5_000,
    /** Admitted plus pending observers (ledger: two watchers guaranteed on tested hardware). */
    val maxObservers: Int = 2,
    /** Connections that have not been admitted yet. */
    val maxUnadmitted: Int = 8,
    /** Valid frames per peer per second before the peer is closed. */
    val maxFramesPerSecond: Int = 200,
    /** Replies remembered per member for idempotent retries. */
    val replyCacheSize: Int = 512,
    /** A member's resync requests closer together than this are ignored. */
    val resyncMinIntervalMs: Long = 200,
    /** Delay before an AI seat acts. */
    val aiDelayMs: Long = 200,
    /** A checkpoint (replay cache) every this many revisions, and after every settlement (WI-06). */
    val checkpointEvery: Int = 64,
) {
    init {
        require(checkpointEvery >= 1) { "checkpointEvery must be positive" }
        require(protocolMin in 1..protocolMax) { "protocol range $protocolMin..$protocolMax is empty or not positive" }
        require(queueLimit >= 1) { "queueLimit must be positive" }
        // A zero interval or deadline would reschedule its timer immediately, forever.
        require(helloDeadlineMs > 0 && heartbeatIntervalMs > 0 && livenessTimeoutMs > 0) { "deadlines and intervals must be positive" }
        require(maxObservers >= 0) { "maxObservers must not be negative" }
        require(maxUnadmitted >= 1 && maxFramesPerSecond >= 1 && replyCacheSize >= 1) { "capacities must be positive" }
        require(resyncMinIntervalMs >= 0 && aiDelayMs >= 0) { "delays must not be negative" }
    }
}

/**
 * What an AI seat may choose from: only its own observation and legal options (GDN-058).
 * [resistanceOpen]: the match lets this giver claim resistance now (WI-07 offers the claim to a
 * policy only when the seat's own hand shows it, so the flag never reveals a partner's card).
 */
data class SeatOptions(
    val plays: List<LegalAction>,
    val mayPass: Boolean,
    val tributeCards: List<String>,
    val returnCards: List<String>,
    val resistanceOpen: Boolean = false,
)

/** An AI seat's policy (WI-07 supplies real ones). Returns the body to submit for the seat. */
fun interface SeatPolicy {
    fun choose(observation: MatchMemberView, options: SeatOptions): CommandBody
}

/**
 * A policy whose answer may arrive later (WI-07): off the host thread, or at a deadline. The host
 * calls [start] once per (epoch, turn revision, seat controller) and applies what [deliver] receives
 * only while that binding still holds (audit A21); a second delivery for the same task is ignored.
 * The returned handle cancels the decision when the binding is gone.
 */
interface DeferredSeatPolicy : SeatPolicy {
    fun start(observation: MatchMemberView, options: SeatOptions, deliver: (CommandBody) -> Unit): Cancellable
}

/**
 * The public part of a room's listing that only its owner knows (join by address): the table and
 * host names and which seats a newcomer may ask for. The host adds room id, protocol range, profile
 * hashes and its key fingerprint. Mirrors the discovery advert; carries no member or card.
 */
data class RoomListingInfo(
    val tableName: String,
    val hostName: String,
    val openSeats: Set<Seat>,
    val replaceableSeats: Set<Seat> = emptySet(),
) {
    init {
        require(openSeats.none { it in replaceableSeats }) { "a seat is either open or replaceable" }
    }
}
