package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.host.AbsencePolicy
import cn.wpgd.app.session.host.EndReason
import cn.wpgd.app.session.host.HostConfig
import cn.wpgd.app.session.host.PauseReason
import cn.wpgd.app.session.host.RecoveryPolicy
import cn.wpgd.app.session.host.RoomStatus
import cn.wpgd.app.session.host.SeatControl
import cn.wpgd.app.session.host.SeatEvent
import cn.wpgd.app.session.host.SeatRecovery
import cn.wpgd.app.session.storage.SavedTable
import cn.wpgd.app.session.storage.playersAt
import cn.wpgd.app.session.storage.policy
import cn.wpgd.app.session.storage.savedConfig
import cn.wpgd.app.session.storage.standIn
import cn.wpgd.app.session.wire.CloseReason
import cn.wpgd.app.session.wire.LeaveBody
import cn.wpgd.app.session.wire.PassBody
import cn.wpgd.app.session.wire.Receipt
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.session.wire.RejectionReceipt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * GDN-086, GDN-087, GDN-018, audit A11 and A22: one recovery transition for disconnect, grace, pause
 * and substitution, live and after a restore. No bot plays inside the grace window; a restored absent
 * human is never stuck; pause, leave, suspend and end are distinct.
 */
class RecoveryPolicyTest {
    private val grace = 3_000L

    private fun table(seed: Long, after: AbsencePolicy) =
        Table(seed = seed, hostConfig = HostConfig(roomId = ROOM, recovery = policy(grace, after)))

    /** Plays until [seat] must act; every other seat passes or leads with [simpleBody]. */
    private fun Table.untilTurnOf(seat: Seat, players: Map<Seat, Table.Peer>) {
        var guard = 0
        while (turn() != seat) {
            check(++guard < 200)
            val p = players.getValue(turn())
            command(p, simpleBody(checkNotNull(p.client.memberView)))
            run(100)
        }
    }

    @Test fun theTransitionTable() {
        val p = policy(grace, AbsencePolicy.SUBSTITUTE)
        val pause = policy(grace, AbsencePolicy.PAUSE)
        val m = "m1"
        assertEquals(SeatControl.Human(m), SeatRecovery.next(SeatControl.Empty, SeatEvent.Connected(m), p))
        assertEquals(SeatControl.Absent(m, 10 + grace), SeatRecovery.next(SeatControl.Human(m), SeatEvent.Disconnected(10), p))
        val absent = SeatControl.Absent(m, 10 + grace)
        assertEquals(absent, SeatRecovery.next(absent, SeatEvent.GraceElapsed(10 + grace - 1), p), "no substitution inside the grace window")
        assertEquals(SeatControl.StandIn(m), SeatRecovery.next(absent, SeatEvent.GraceElapsed(10 + grace), p))
        assertEquals(SeatControl.AwaitingPlayer(m), SeatRecovery.next(absent, SeatEvent.GraceElapsed(10 + grace), pause))
        for (c in listOf(absent, SeatControl.StandIn(m), SeatControl.AwaitingPlayer(m), SeatControl.Human(m))) {
            assertEquals(SeatControl.Human(m), SeatRecovery.next(c, SeatEvent.Connected(m), p), "rejoin reclaims from $c")
            assertEquals(c, SeatRecovery.next(c, SeatEvent.Connected("someone-else"), p), "only the owner reclaims $c")
            assertEquals(SeatControl.StandIn(null), SeatRecovery.next(c, SeatEvent.Left, p))
            assertEquals(SeatControl.AwaitingPlayer(null), SeatRecovery.next(c, SeatEvent.Left, pause))
            assertEquals(c, SeatRecovery.next(c, SeatEvent.AiAssigned, p), "the host cannot give a human's seat to an AI")
            assertEquals(SeatControl.Human("m2"), SeatRecovery.next(c, SeatEvent.Replaced("m2"), p))
        }
        assertEquals(SeatControl.Ai, SeatRecovery.next(SeatControl.StandIn(null), SeatEvent.AiAssigned, p))
        assertEquals(SeatControl.Ai, SeatRecovery.next(SeatControl.Ai, SeatEvent.Disconnected(5), p), "an AI does not disconnect")
        assertFailsWith<IllegalArgumentException> { RecoveryPolicy(1, AbsencePolicy.SUBSTITUTE, AbsencePolicy.PAUSE, null) }
    }

    @Test fun graceThenSubstitutionAndNoBotPlayInsideTheGrace() {
        val t = table(1201, AbsencePolicy.SUBSTITUTE)
        val players = t.fourPlayers()
        t.startDeal()
        t.untilTurnOf(Seat.WEST, players)
        val revision = t.host.revision
        val west = players.getValue(Seat.WEST)
        west.link.disconnect()
        t.run(50)
        val absent = assertIs<SeatControl.Absent>(t.host.seatControl(Seat.WEST))
        // Inside the grace window nobody acts for WEST, however long the AI delay.
        t.scheduler.runUntil(absent.graceEndsAt - t.scheduler.now - 1) { false }
        assertEquals(revision, t.host.revision, "a bot played inside the grace window")
        assertIs<SeatControl.Absent>(t.host.seatControl(Seat.WEST))
        // Then the policy: an AI stands in for the absent human and plays once.
        t.run(1 + t.hostConfig.aiDelayMs + 10)
        assertEquals(SeatControl.StandIn(west.client.memberId), t.host.seatControl(Seat.WEST))
        assertEquals(revision + 1, t.host.revision)
        // The human comes back and reclaims the seat at once.
        val back = t.reconnect(west)
        t.until(5_000) { back.client.phase == ClientPhase.ADMITTED }
        assertEquals(SeatControl.Human(west.client.memberId!!), t.host.seatControl(Seat.WEST))
    }

    @Test fun graceThenPauseUntilThePlayerReturns() {
        val t = table(1202, AbsencePolicy.PAUSE)
        val players = t.fourPlayers()
        t.startDeal()
        t.untilTurnOf(Seat.NORTH, players)
        val north = players.getValue(Seat.NORTH)
        north.link.disconnect()
        t.run(grace + 500)
        assertEquals(RoomStatus.Paused(setOf(PauseReason.AWAITING_PLAYER)), t.host.status)
        // A paused table refuses every game command (GDN-018).
        val other = players.getValue(Seat.EAST)
        val reply = t.command(other, PassBody)
        assertEquals(RejectionCode.ROOM_PAUSED, assertIs<RejectionReceipt>(reply).code)
        val back = t.reconnect(north)
        t.until(5_000) { back.client.phase == ClientPhase.ADMITTED }
        assertEquals(RoomStatus.Active, t.host.status)
        assertIs<Receipt>(t.command(back, simpleBody(checkNotNull(back.client.memberView))))
    }

    /** A rejoin, then a second disconnect: the first absence's timer neither ends nor shortens the second grace. */
    @Test fun aSecondAbsenceGetsAFullGrace() {
        val t = table(1207, AbsencePolicy.SUBSTITUTE)
        val players = t.fourPlayers()
        t.startDeal()
        t.untilTurnOf(Seat.EAST, players)
        val revision = t.host.revision
        var east = players.getValue(Seat.EAST)
        east.link.disconnect()
        t.run(grace / 2)
        east = t.reconnect(east)
        t.until(5_000) { east.client.phase == ClientPhase.ADMITTED }
        east.link.disconnect()
        t.run(10)
        val second = assertIs<SeatControl.Absent>(t.host.seatControl(Seat.EAST))
        // The first absence's grace would have ended here.
        t.scheduler.runUntil(second.graceEndsAt - t.scheduler.now - 1) { false }
        assertIs<SeatControl.Absent>(t.host.seatControl(Seat.EAST))
        assertEquals(revision, t.host.revision, "no bot play inside the second grace")
        t.run(1 + t.hostConfig.aiDelayMs + 10)
        assertEquals(revision + 1, t.host.revision)
    }

    @Test fun aRestoredAbsentHumanIsNotStuck() {
        // Audit A11: upstream marked a restored human disconnected and waited for ever.
        val saved = SavedTable(1203, config = savedConfig(recovery = policy(grace, AbsencePolicy.SUBSTITUTE)))
        saved.playersAt(9)
        saved.crash()
        val r = saved.restored()
        val toAct = checkNotNull(r.host.state.deal?.turn)
        assertIs<SeatControl.Absent>(r.host.seatControl(toAct), "a restored human gets the same grace as a live disconnect")
        val revision = r.host.revision
        saved.t.run(grace - 1)
        assertEquals(revision, saved.host.revision, "no bot play inside the grace window after a restore")
        saved.t.run(1 + saved.config.aiDelayMs + 10)
        assertTrue(saved.host.revision > revision, "the table moved on after the grace")
        assertIs<SeatControl.StandIn>(saved.host.seatControl(toAct))
    }

    @Test fun pauseLeaveSuspendAndEndAreDistinct() {
        val t = table(1204, AbsencePolicy.SUBSTITUTE)
        val players = t.fourPlayers()
        t.startDeal()
        // Pause (host): refuses game commands, resumes on request.
        t.host.pause()
        assertEquals(RoomStatus.Paused(setOf(PauseReason.HOST)), t.host.status)
        val actor = players.getValue(t.turn())
        assertEquals(RejectionCode.ROOM_PAUSED, assertIs<RejectionReceipt>(t.command(actor, simpleBody(actor.client.memberView!!))).code)
        assertTrue(t.host.resume())
        assertEquals(RoomStatus.Active, t.host.status)
        // Leave: a seat event, not a room state. The seat goes to its policy at once (no grace).
        val leaver = players.getValue(Seat.SOUTH)
        assertIs<Receipt>(t.command(leaver, LeaveBody))
        assertEquals(SeatControl.StandIn(null), t.host.seatControl(Seat.SOUTH))
        assertEquals(RoomStatus.Active, t.host.status)
        // End: distinct from pause and suspend; members' capabilities end with it.
        t.host.end(0, 1)
        assertEquals(RoomStatus.Ended(EndReason.HOST_ENDED), t.host.status)
        t.run(500)
        assertEquals(CloseReason.EPOCH_ENDED, players.getValue(Seat.EAST).client.closeReason)
        assertNotEquals<RoomStatus>(RoomStatus.Paused(setOf(PauseReason.HOST)), t.host.status)

        // Suspend needs a saved table and is its own state (RetentionPolicyTest covers expiry).
        val saved = SavedTable(1205)
        saved.playersAt(5)
        saved.host.suspend(1, 2)
        assertIs<RoomStatus.Suspended>(saved.host.status)
        assertFailsWith<IllegalStateException> { t.host.suspend(1, 2) }
    }

    @Test fun anAiSeatTheHostAssignedIsRestoredAsAi() {
        val saved = SavedTable(1206)
        Seat.entries.filter { it != Seat.SOUTH }.forEach { saved.host.setAi(it, standIn) }
        val south = saved.t.player(Seat.SOUTH)
        Bot(saved.t, south).poke()
        saved.host.advance(); saved.host.advance()
        saved.t.until(60_000) { saved.host.revision >= 12 }
        saved.crash()
        val r = saved.restored()
        for (s in Seat.entries.filter { it != Seat.SOUTH }) assertEquals(SeatControl.Ai, r.host.seatControl(s))
        assertIs<SeatControl.Absent>(r.host.seatControl(Seat.SOUTH))
    }
}
