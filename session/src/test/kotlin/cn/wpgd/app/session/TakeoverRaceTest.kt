package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.host.AbsencePolicy
import cn.wpgd.app.session.host.HostConfig
import cn.wpgd.app.session.host.SeatControl
import cn.wpgd.app.session.storage.policy
import cn.wpgd.app.session.wire.Receipt
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.session.wire.RejectionReceipt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * GDN-051: the absent human rejoins just as the stand-in AI is about to act. Whatever the order,
 * the turn gets exactly one action and the seat ends with exactly one controller, the human.
 */
class TakeoverRaceTest {
    private val grace = 2_000L

    @Test fun rejoinVersusAiGivesOneActionAndOneController() {
        var humanWon = 0
        var aiWon = 0
        // Rejoin attempts spread across the moment the grace ends and the AI's delay runs out.
        for (offset in -300L..500L step 20) {
            val t = Table(seed = 1301, hostConfig = HostConfig(roomId = ROOM, recovery = policy(grace, AbsencePolicy.SUBSTITUTE)))
            val players = t.fourPlayers()
            t.startDeal()
            val seat = t.turn()
            val human = players.getValue(seat)
            val memberId = checkNotNull(human.client.memberId)
            val revision = t.host.revision
            val commitsBefore = t.host.metrics.commits
            human.link.disconnect()
            t.run(10)
            val absent = assertIs<SeatControl.Absent>(t.host.seatControl(seat))
            val rejoinAt = absent.graceEndsAt + offset
            t.scheduler.runUntil(rejoinAt - t.scheduler.now) { false }

            val back = t.reconnect(human)
            t.until(5_000) { back.client.phase == ClientPhase.ADMITTED && back.client.revision >= revision }
            // The human acts on what it sees, as soon as it is back.
            val id = if (back.client.memberView!!.deal!!.yourTurn) back.client.submit(simpleBody(back.client.memberView!!)) else null
            t.run(2_000)

            assertEquals(SeatControl.Human(memberId), t.host.seatControl(seat), "offset $offset: one controller, the human")
            assertEquals(revision + 1, t.host.revision, "offset $offset: exactly one action for the turn")
            assertEquals(commitsBefore + 1, t.host.metrics.commits)
            val reply = id?.let { back.client.replies[it] }
            when {
                reply is Receipt -> humanWon++
                reply is RejectionReceipt && reply.code == RejectionCode.STALE_REVISION -> aiWon++
                id == null -> aiWon++ // the AI had already acted when the human looked
                else -> fail("offset $offset: unexpected reply $reply")
            }
        }
        println("TakeoverRace: human acted $humanWon times, stand-in AI acted $aiWon times, never both")
        assertTrue(humanWon > 0 && aiWon > 0, "both orders were exercised (human $humanWon, AI $aiWon)")
    }

    @Test fun aStandInTaskQueuedBeforeTheRejoinIsInert() {
        val t = Table(seed = 1302, hostConfig = HostConfig(roomId = ROOM, recovery = policy(grace, AbsencePolicy.SUBSTITUTE)))
        val players = t.fourPlayers()
        t.startDeal()
        val seat = t.turn()
        val human = players.getValue(seat)
        human.link.disconnect()
        // Past the grace: the stand-in is installed and its task is queued (it fires aiDelayMs later).
        t.run(grace + 20)
        assertIs<SeatControl.StandIn>(t.host.seatControl(seat))
        val revision = t.host.revision
        val stale = t.host.metrics.staleAiTasks
        val back = t.reconnect(human)
        t.until(t.hostConfig.aiDelayMs) { back.client.phase == ClientPhase.ADMITTED }
        t.run(t.hostConfig.aiDelayMs)
        assertEquals(revision, t.host.revision, "the stand-in must not act after the human reclaimed the seat")
        assertTrue(t.host.metrics.staleAiTasks > stale)
    }
}
