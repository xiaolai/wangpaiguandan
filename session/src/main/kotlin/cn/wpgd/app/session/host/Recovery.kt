package cn.wpgd.app.session.host

import cn.wpgd.app.session.storage.QuarantineReason

/** What happens to a seat whose human is gone: an AI stands in, or the room pauses for them. */
enum class AbsencePolicy { SUBSTITUTE, PAUSE }

/**
 * The room's recovery policy (GDN-086, GDN-087, audit A11). Set by the lobby; transport and session
 * hard-code no grace value (plan section 3: candidates are 10 s and 30 s).
 *
 * - [graceMs]: how long a disconnected human keeps the seat with nobody acting for it.
 * - [afterGrace]: what happens when the grace ends with the human still away.
 * - [afterLeave]: what happens when a human leaves on purpose (no grace).
 * - [substitute]: the AI that stands in; required when either policy is [AbsencePolicy.SUBSTITUTE].
 */
data class RecoveryPolicy(
    val graceMs: Long,
    val afterGrace: AbsencePolicy,
    val afterLeave: AbsencePolicy,
    val substitute: SeatPolicy?,
) {
    init {
        require(graceMs >= 0) { "negative grace" }
        require(substitute != null || (afterGrace == AbsencePolicy.PAUSE && afterLeave == AbsencePolicy.PAUSE)) { "substitution needs a substitute policy" }
    }
}

/** Who controls a seat. [owner] is the human member the seat belongs to, if any. */
sealed interface SeatControl {
    val owner: String?

    /** True when an AI acts for the seat. */
    val aiActs: Boolean get() = this is StandIn || this is Ai

    data object Empty : SeatControl {
        override val owner: String? get() = null
    }

    /** The human is connected and acts. */
    data class Human(val memberId: String) : SeatControl {
        override val owner: String get() = memberId
    }

    /** The human is disconnected; until [graceEndsAt] (scheduler time) nobody acts for the seat. */
    data class Absent(val memberId: String, val graceEndsAt: Long) : SeatControl {
        override val owner: String get() = memberId
    }

    /** An AI acts for the seat: for its absent human ([memberId]), or for a seat whose human left (null). */
    data class StandIn(val memberId: String?) : SeatControl {
        override val owner: String? get() = memberId
    }

    /** The room is paused until the seat's human returns ([memberId]) or someone replaces them (null). */
    data class AwaitingPlayer(val memberId: String?) : SeatControl {
        override val owner: String? get() = memberId
    }

    /** The host gave the seat to an AI. */
    data object Ai : SeatControl {
        override val owner: String? get() = null
    }
}

sealed interface SeatEvent {
    /** The seat's owner (re)joined, or a new member was admitted to an empty seat. */
    data class Connected(val memberId: String) : SeatEvent

    /** The owner's connection was lost (live), or the host restarted from storage (restore). */
    data class Disconnected(val now: Long) : SeatEvent

    data class GraceElapsed(val now: Long) : SeatEvent

    /** The owner left on purpose. */
    data object Left : SeatEvent

    /** An approved replacement takes the seat. */
    data class Replaced(val memberId: String) : SeatEvent

    data object AiAssigned : SeatEvent

    /** WI-09 (H1): the host lets the AI stand in now, without waiting for the grace to end. */
    data object HostStandIn : SeatEvent
}

/**
 * The single recovery transition for disconnect, grace, pause and substitution, used both live and
 * after a restore (a restored seat's human is simply disconnected at restore time). Pure.
 *
 * | From | Event | To |
 * |---|---|---|
 * | Empty | Connected(m) | Human(m) |
 * | Human(m), Absent(m), StandIn(m), AwaitingPlayer(m) | Connected(m) | Human(m) (rejoin reclaims at once) |
 * | Human(m) | Disconnected(t) | Absent(m, t + grace) |
 * | Absent(m, end) | GraceElapsed(t ≥ end) | StandIn(m) or AwaitingPlayer(m) by [RecoveryPolicy.afterGrace] |
 * | any owned seat | Left | StandIn(null) or AwaitingPlayer(null) by [RecoveryPolicy.afterLeave] |
 * | any | Replaced(m) | Human(m) |
 * | Empty, Ai, StandIn(null), AwaitingPlayer(null) | AiAssigned | Ai |
 * | Absent(m), AwaitingPlayer(m) | HostStandIn | StandIn(m), only with a substitute policy |
 *
 * Every other pair leaves the seat unchanged (a stale timer, a duplicate event).
 */
object SeatRecovery {
    fun next(c: SeatControl, e: SeatEvent, p: RecoveryPolicy): SeatControl = when (e) {
        is SeatEvent.Connected -> when {
            c is SeatControl.Empty -> SeatControl.Human(e.memberId)
            c.owner == e.memberId -> SeatControl.Human(e.memberId)
            else -> c
        }
        is SeatEvent.Disconnected -> if (c is SeatControl.Human) SeatControl.Absent(c.memberId, e.now + p.graceMs) else c
        is SeatEvent.GraceElapsed -> if (c is SeatControl.Absent && e.now >= c.graceEndsAt) {
            if (p.afterGrace == AbsencePolicy.SUBSTITUTE) SeatControl.StandIn(c.memberId) else SeatControl.AwaitingPlayer(c.memberId)
        } else c
        SeatEvent.Left -> if (c.owner != null) {
            if (p.afterLeave == AbsencePolicy.SUBSTITUTE) SeatControl.StandIn(null) else SeatControl.AwaitingPlayer(null)
        } else c
        is SeatEvent.Replaced -> SeatControl.Human(e.memberId)
        SeatEvent.AiAssigned -> if (c.owner == null) SeatControl.Ai else c
        SeatEvent.HostStandIn -> if ((c is SeatControl.Absent || c is SeatControl.AwaitingPlayer) && p.substitute != null) SeatControl.StandIn(c.owner) else c
    }
}

enum class PauseReason {
    /** The host paused the table. */
    HOST,

    /** A seat waits for its human (or a replacement) under [AbsencePolicy.PAUSE]. */
    AWAITING_PLAYER,

    /** A write of the event log failed; only a restart from storage can resume the table. */
    STORAGE_FAILURE,
}

enum class EndReason { MATCH_OVER, HOST_ENDED }

/**
 * The room's state (GDN-018, audit A22). Pause, suspend, end and quarantine are distinct; a member
 * leaving is a seat event ([SeatEvent.Left]), not a room state. Only [Active] accepts game commands.
 */
sealed interface RoomStatus {
    data object Active : RoomStatus

    data class Paused(val reasons: Set<PauseReason>) : RoomStatus

    /** Saved and closed by the host's choice, kept until [expiresAt] (wall clock). */
    data class Suspended(val expiresAt: Long) : RoomStatus

    data class Ended(val reason: EndReason) : RoomStatus

    /** Acknowledged data may be lost or the save is invalid. Nothing is played until the user acts. */
    data class Quarantined(val reason: QuarantineReason, val detail: String) : RoomStatus
}

/** The wire form of an [AbsencePolicy] (WI-09 roster). */
val AbsencePolicy.mode: cn.wpgd.app.session.wire.AbsenceMode
    get() = when (this) {
        AbsencePolicy.SUBSTITUTE -> cn.wpgd.app.session.wire.AbsenceMode.SUBSTITUTE
        AbsencePolicy.PAUSE -> cn.wpgd.app.session.wire.AbsenceMode.PAUSE
    }
