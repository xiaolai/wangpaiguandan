package cn.wpgd.app.core.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The WI-04 extension of the deal reducer: deal without a lead, move cards while DEALT, then open play. */
class SetupCommandTest {
    private val r = p1Reducer()

    private fun dealt(seed: Long = 5): ReduceResult.Accepted = r.setup(r.newDeal(2), SetupCommand.Deal(SplitMix64(seed))).accepted()

    @Test fun dealLeavesTheDealWithoutALeaderAndDealsTheSameHandsAsStart() {
        val d = dealt()
        assertEquals(Phase.DEALT, d.state.phase)
        assertNull(d.state.turn)
        assertNull(d.state.leader)
        assertEquals(r.started(5).state.hands, d.state.hands, "same shuffle and round-robin deal as Command.Start")
        assertEquals(DealEvents.replay(r.newDeal(2), d.events), d.state)
    }

    @Test fun cardsMoveOnlyWhileDealtAndOnlyFromTheirHolder() {
        val d = dealt().state
        val card = d.hands.getValue(Seat.WEST).first()
        val moved = r.setup(d, SetupCommand.MoveCard(Seat.WEST, Seat.SOUTH, card)).accepted().state
        assertEquals(26, moved.hands.getValue(Seat.WEST).size)
        assertEquals(28, moved.hands.getValue(Seat.SOUTH).size)
        assertEquals(StandardDeck.ids, moved.hands.values.flatten().toSet())
        r.setup(d, SetupCommand.MoveCard(Seat.EAST, Seat.SOUTH, card)).rejectedWith<Rejection.UnownedCard>()
        r.setup(d, SetupCommand.MoveCard(Seat.WEST, Seat.WEST, card)).rejectedWith<Rejection.SelfMove>()
        val playing = r.setup(moved, SetupCommand.BeginPlay(Seat.NORTH)).accepted().state
        assertEquals(Seat.NORTH, playing.turn)
        r.setup(playing, SetupCommand.MoveCard(Seat.SOUTH, Seat.WEST, card)).rejectedWith<Rejection.WrongPhase>()
        r.setup(playing, SetupCommand.BeginPlay(Seat.EAST)).rejectedWith<Rejection.WrongPhase>()
        r.setup(r.newDeal(2), SetupCommand.BeginPlay(Seat.EAST)).rejectedWith<Rejection.WrongPhase>()
        r.setup(playing, SetupCommand.Deal(SplitMix64(1))).rejectedWith<Rejection.WrongPhase>()
    }

    @Test fun aDealtDealRefusesPlayUntilItIsOpened() {
        val d = dealt().state
        r.reduce(d, Command.Pass(Seat.SOUTH)).rejectedWith<Rejection.WrongPhase>()
        r.reduce(d, Command.Start(SplitMix64(1), Seat.SOUTH)).rejectedWith<Rejection.WrongPhase>()
        val moveEvent = r.setup(d, SetupCommand.MoveCard(Seat.WEST, Seat.SOUTH, d.hands.getValue(Seat.WEST).first())).accepted().events.single()
        assertEquals(Visibility.HostOnly, moveEvent.visibility, "the match layer announces tribute moves itself")
    }
}
