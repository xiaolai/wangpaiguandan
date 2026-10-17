package cn.wpgd.app.core.state

import cn.wpgd.app.core.rules.RejectReason
import kotlin.test.Test
import kotlin.test.assertEquals

/** KEEP-04: every illegal command is refused with a typed reason, and the state is untouched. */
class IllegalCommandNoMutationTest {
    private val r = p1Reducer()

    /** Rejects [command] on [state], checks the reason, and checks the state hash before and after. */
    private inline fun <reified R : Rejection> assertRefused(state: DealState, command: Command): R {
        val before = StateHash.of(state)
        val snapshot = state.copy()
        val reason = r.reduce(state, command).rejectedWith<R>()
        assertEquals(before, StateHash.of(state), "state hash changed after rejecting $command")
        assertEquals(snapshot, state, "state changed after rejecting $command")
        return reason
    }

    private val opened = r.started(seed = 4, leader = Seat.SOUTH).state
    private fun hand(seat: Seat, s: DealState = opened) = s.hands.getValue(seat).sorted()

    @Test fun wrongSeat() {
        val rej = assertRefused<Rejection.WrongSeat>(opened, Command.Play(Seat.EAST, hand(Seat.EAST).take(1), emptyList()))
        assertEquals(Seat.SOUTH, rej.expected)
        assertEquals(Seat.EAST, rej.actual)
        assertRefused<Rejection.WrongSeat>(opened, Command.Pass(Seat.NORTH))
    }

    @Test fun outOfTurnFollow() {
        val led = r.reduce(opened, singleOf(opened, Seat.SOUTH)).accepted().state
        assertEquals(Seat.EAST, led.turn)
        val rej = assertRefused<Rejection.WrongSeat>(led, Command.Pass(Seat.WEST))
        assertEquals(Seat.EAST, rej.expected)
        assertRefused<Rejection.WrongSeat>(led, Command.Play(Seat.NORTH, hand(Seat.NORTH, led).take(1), emptyList()))
        assertRefused<Rejection.WrongSeat>(led, Command.Pass(Seat.SOUTH))
    }

    @Test fun wrongPhase() {
        val waiting = r.newDeal(2)
        assertEquals(Phase.AWAITING_DEAL, assertRefused<Rejection.WrongPhase>(waiting, Command.Pass(Seat.SOUTH)).phase)
        assertRefused<Rejection.WrongPhase>(waiting, Command.Play(Seat.SOUTH, listOf("0S3"), emptyList()))
        assertEquals(Phase.PLAYING, assertRefused<Rejection.WrongPhase>(opened, Command.Start(SplitMix64(1), Seat.SOUTH)).phase)
        val ended = playDeal(r, 8)
        assertEquals(Phase.ENDED, ended.phase)
        val anyone = Seat.entries.first { ended.hands.getValue(it).isNotEmpty() }
        assertRefused<Rejection.WrongPhase>(ended, Command.Play(anyone, hand(anyone, ended).take(1), emptyList()))
        assertRefused<Rejection.WrongPhase>(ended, Command.Pass(anyone))
        assertRefused<Rejection.WrongPhase>(ended, Command.Start(SplitMix64(1), Seat.SOUTH))
    }

    @Test fun unownedCard() {
        val east = hand(Seat.EAST).first()
        val rej = assertRefused<Rejection.UnownedCard>(opened, Command.Play(Seat.SOUTH, listOf(east), emptyList()))
        assertEquals(listOf(east), rej.cardIds)
        assertRefused<Rejection.UnownedCard>(opened, Command.Play(Seat.SOUTH, listOf(hand(Seat.SOUTH).first(), east), emptyList()))
        assertRefused<Rejection.UnownedCard>(opened, Command.Play(Seat.SOUTH, listOf("9XX"), emptyList()))
    }

    @Test fun duplicateCardId() {
        val id = hand(Seat.SOUTH).first()
        val rej = assertRefused<Rejection.DuplicateCard>(opened, Command.Play(Seat.SOUTH, listOf(id, id), emptyList()))
        assertEquals(listOf(id), rej.cardIds)
    }

    @Test fun emptySelection() {
        assertRefused<Rejection.EmptySelection>(opened, Command.Play(Seat.SOUTH, emptyList(), emptyList()))
    }

    @Test fun passAsLeader() {
        assertRefused<Rejection.PassAsLeader>(opened, Command.Pass(Seat.SOUTH))
        // After a trick ends, the new leader may not pass either.
        val s = playingState(mapOf(Seat.SOUTH to "0S3 0S4", Seat.EAST to "0D3", Seat.NORTH to "0C3", Seat.WEST to "0H3"), Seat.SOUTH)
        val won = r.run(s, play(Seat.SOUTH, "0S4"), Command.Pass(Seat.EAST), Command.Pass(Seat.NORTH), Command.Pass(Seat.WEST)).state
        assertEquals(Seat.SOUTH, won.turn)
        assertRefused<Rejection.PassAsLeader>(won, Command.Pass(Seat.SOUTH))
    }

    @Test fun illegalFollowDoesNotMutate() {
        val s = playingState(
            mapOf(Seat.SOUTH to "0S9 0D9 0S3", Seat.EAST to "0S4 0S5 0D4 1C8", Seat.NORTH to "0C3", Seat.WEST to "0H3"),
            Seat.SOUTH,
        )
        val led = r.run(s, play(Seat.SOUTH, "0S9 0D9")).state
        assertRefused<Rejection.DoesNotBeatTrick>(led, play(Seat.EAST, "0S4 0D4"))
        assertRefused<Rejection.DoesNotBeatTrick>(led, play(Seat.EAST, "1C8"))
        assertEquals(RejectReason.NO_COMBINATION, assertRefused<Rejection.IllegalCombination>(led, play(Seat.EAST, "0S4 0S5")).reason)
    }

    @Test fun finishedSeatCannotAct() {
        val s = playingState(mapOf(Seat.SOUTH to "0S3", Seat.EAST to "0D3 0D5", Seat.NORTH to "0C3 0C5", Seat.WEST to "0H3 0H5"), Seat.SOUTH)
        val out = r.run(s, play(Seat.SOUTH, "0S3")).state
        assertEquals(listOf(Seat.SOUTH), out.finishOrder)
        assertRefused<Rejection.SeatAlreadyFinished>(out, Command.Pass(Seat.SOUTH))
        assertRefused<Rejection.SeatAlreadyFinished>(out, Command.Play(Seat.SOUTH, listOf("0S3"), emptyList()))
    }

    @Test fun rejectionCarriesNoState() {
        // The caller keeps its own object: a rejection has nothing to replace it with.
        val result = r.reduce(opened, Command.Pass(Seat.SOUTH))
        assertEquals(ReduceResult.Rejected(Rejection.PassAsLeader), result)
    }

    private fun singleOf(s: DealState, seat: Seat): Command.Play {
        val a = r.legalActions(s, seat).first { it.cards.size == 1 }
        return Command.Play(seat, a.cards.map { it.id }, a.declaration, a.combination.type)
    }
}
