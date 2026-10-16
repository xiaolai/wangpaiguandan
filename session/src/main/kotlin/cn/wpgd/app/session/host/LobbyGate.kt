package cn.wpgd.app.session.host

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.session.wire.RosterSeat

/**
 * WI-09 (GDN-013): what stops a room from starting. One pure rule used by the host ([SessionHost.start])
 * and by every member's lobby screen, so the start button and the host never disagree.
 *
 * - An empty seat blocks: the host must give it to an AI explicitly, so nobody is surprised by a bot.
 * - A connected human who is not ready blocks.
 * - A human who is away (disconnected, or the room waits for them) blocks.
 */
object LobbyGate {
    enum class Reason { EMPTY_SEAT, NOT_READY, AWAY }

    data class Blocker(val seat: Seat, val reason: Reason)

    fun blockers(seats: List<RosterSeat>): List<Blocker> = seats.mapNotNull { s ->
        when (s.occupant) {
            Occupant.EMPTY -> Blocker(s.seat, Reason.EMPTY_SEAT)
            Occupant.HUMAN -> if (s.ready) null else Blocker(s.seat, Reason.NOT_READY)
            Occupant.ABSENT, Occupant.AWAITING_PLAYER, Occupant.STAND_IN -> Blocker(s.seat, Reason.AWAY)
            Occupant.AI -> null
        }
    }
}
