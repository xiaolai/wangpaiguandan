package cn.wpgd.app.core.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GDN-039, KEEP-04: who leads the next trick. Rules: base-v1 section 9, D19 (order, trick end),
 * D20 (接风: the partner of a player who went out on the winning play leads), D21 (deal end).
 * Seats in play order: SOUTH, EAST, NORTH, WEST; partners SOUTH–NORTH and EAST–WEST.
 */
class PartnerLeadTest {
    private val r = p1Reducer()
    private val pass = Command::Pass

    private fun trickEnded(events: List<DealEvent>) = events.filterIsInstance<DealEvent.TrickEnded>().single()

    @Test fun allPassLastPlayerLeadsAgain() {
        val s = playingState(mapOf(Seat.SOUTH to "0S4 0S3", Seat.EAST to "0D3", Seat.NORTH to "0C3", Seat.WEST to "0H3"), Seat.SOUTH)
        val done = r.run(s, play(Seat.SOUTH, "0S4"), pass(Seat.EAST), pass(Seat.NORTH), pass(Seat.WEST))
        assertEquals(DealEvent.TrickEnded(done.state.revision, winner = Seat.SOUTH, nextLeader = Seat.SOUTH, partnerLead = false), trickEnded(done.events))
        assertEquals(Seat.SOUTH, done.state.turn)
        assertEquals(Seat.SOUTH, done.state.leader)
        assertNull(done.state.trick.top)
    }

    @Test fun activePartnerInheritsTheLeadWhenTheWinnerWentOut() {
        val s = playingState(mapOf(Seat.SOUTH to "0S3", Seat.EAST to "0D3 0D5", Seat.NORTH to "0C3 0C5", Seat.WEST to "0H3 0H5"), Seat.SOUTH)
        val out = r.run(s, play(Seat.SOUTH, "0S3"))
        assertEquals(listOf(Seat.SOUTH), out.state.finishOrder)
        assertEquals(Seat.EAST, out.state.turn)
        val done = r.run(out.state, pass(Seat.EAST), pass(Seat.NORTH), pass(Seat.WEST))
        assertEquals(DealEvent.TrickEnded(done.state.revision, winner = Seat.SOUTH, nextLeader = Seat.NORTH, partnerLead = true), trickEnded(done.events))
        assertEquals(Seat.NORTH, done.state.turn)
        assertEquals(Seat.NORTH, done.state.leader)
    }

    @Test fun partnerMayBeatTheLastCardInsteadOfInheriting() {
        val s = playingState(mapOf(Seat.SOUTH to "0S3", Seat.EAST to "0D3 0D5", Seat.NORTH to "0C3 0C5", Seat.WEST to "0H3 0H5"), Seat.SOUTH)
        val afterNorth = r.run(s, play(Seat.SOUTH, "0S3"), pass(Seat.EAST), play(Seat.NORTH, "0C5"))
        assertEquals(Seat.WEST, afterNorth.state.turn)
        // WEST passes; SOUTH has gone out and is skipped; EAST passes: NORTH won the trick in its own right.
        val west = r.run(afterNorth.state, pass(Seat.WEST))
        assertEquals(Seat.EAST, west.state.turn)
        val done = r.run(west.state, pass(Seat.EAST))
        assertEquals(DealEvent.TrickEnded(done.state.revision, winner = Seat.NORTH, nextLeader = Seat.NORTH, partnerLead = false), trickEnded(done.events))
    }

    @Test fun secondFinisherWithActivePartnerPassesTheLeadToThatPartner() {
        val s = playingState(mapOf(Seat.SOUTH to "0S3", Seat.NORTH to "0C3 0C5", Seat.WEST to "0H3 0H5"), Seat.SOUTH, finished = listOf(Seat.EAST))
        val out = r.run(s, play(Seat.SOUTH, "0S3"))
        assertEquals(listOf(Seat.EAST, Seat.SOUTH), out.state.finishOrder)
        assertEquals(Phase.PLAYING, out.state.phase)
        assertEquals(Seat.NORTH, out.state.turn, "EAST has gone out and is skipped")
        val done = r.run(out.state, pass(Seat.NORTH), pass(Seat.WEST))
        assertEquals(DealEvent.TrickEnded(done.state.revision, winner = Seat.SOUTH, nextLeader = Seat.NORTH, partnerLead = true), trickEnded(done.events))
    }

    @Test fun finishedPartnerMeansTheDealEndsAsADoubleUp() {
        val s = playingState(mapOf(Seat.SOUTH to "0S3", Seat.EAST to "0D3 0D5", Seat.WEST to "0H3 0H5"), Seat.SOUTH, finished = listOf(Seat.NORTH))
        val done = r.run(s, play(Seat.SOUTH, "0S3"))
        assertEquals(Phase.ENDED, done.state.phase)
        assertNull(done.state.turn)
        assertEquals(listOf(Seat.NORTH, Seat.SOUTH), done.state.finishOrder)
        assertEquals(DealEvent.DealEnded(done.state.revision, listOf(Seat.NORTH, Seat.SOUTH), listOf(Seat.EAST, Seat.WEST)), done.events.last())
        assertTrue(done.events.none { it is DealEvent.TrickEnded }, "no lead to pass on: the deal is over")
    }

    @Test fun thirdFinisherEndsTheDeal() {
        val s = playingState(mapOf(Seat.SOUTH to "0S3", Seat.WEST to "0H3 0H5"), Seat.SOUTH, finished = listOf(Seat.EAST, Seat.NORTH))
        val done = r.run(s, play(Seat.SOUTH, "0S3"))
        assertEquals(Phase.ENDED, done.state.phase)
        assertEquals(listOf(Seat.EAST, Seat.NORTH, Seat.SOUTH), done.state.finishOrder)
        assertEquals(DealEvent.DealEnded(done.state.revision, listOf(Seat.EAST, Seat.NORTH, Seat.SOUTH), listOf(Seat.WEST)), done.events.last())
    }

    @Test fun finishedSeatsAreSkippedAndTheActiveWinnerLeads() {
        val s = playingState(mapOf(Seat.SOUTH to "0S4 0S3", Seat.EAST to "0D3 0D5", Seat.WEST to "0H3 0H5"), Seat.SOUTH, finished = listOf(Seat.NORTH))
        val east = r.run(s, play(Seat.SOUTH, "0S4"), pass(Seat.EAST))
        assertEquals(Seat.WEST, east.state.turn)
        val done = r.run(east.state, pass(Seat.WEST))
        assertEquals(DealEvent.TrickEnded(done.state.revision, winner = Seat.SOUTH, nextLeader = Seat.SOUTH, partnerLead = false), trickEnded(done.events))
    }

    @Test fun passingEarlierInATrickDoesNotStopALaterPlay() {
        val s = playingState(mapOf(Seat.SOUTH to "0S4 0S3", Seat.EAST to "0D8 0D3", Seat.NORTH to "0C6 0C3", Seat.WEST to "0H7 0H3"), Seat.SOUTH)
        val mid = r.run(s, play(Seat.SOUTH, "0S4"), pass(Seat.EAST), play(Seat.NORTH, "0C6"), pass(Seat.WEST), pass(Seat.SOUTH))
        assertEquals(Seat.EAST, mid.state.turn, "the trick is still open: EAST has not passed since NORTH's play")
        val east = r.run(mid.state, play(Seat.EAST, "0D8"))
        assertEquals(Seat.EAST, east.state.trick.top?.seat)
        val done = r.run(east.state, pass(Seat.NORTH), pass(Seat.WEST), pass(Seat.SOUTH))
        assertEquals(Seat.EAST, trickEnded(done.events).nextLeader)
    }
}
