package cn.wpgd.app.table

import cn.wpgd.app.VirtualSolo
import cn.wpgd.app.drive
import cn.wpgd.app.human
import cn.wpgd.app.owner.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** GDN-078: a late reconnect reconstructs the same current trick display and per-seat last actions. */
class ReconnectTrickDisplayTest {
    @Test fun aReconnectShowsTheSameTrickAndLastActions() {
        val solo = VirtualSolo(seed = 31).start()
        val vm = solo.viewModel()
        val human = solo.human(vm)
        // Wait for this member's turn in a trick with at least two plays and a pass behind it.
        assertTrue(
            solo.drive(human) {
                val ui = vm.uiState.value
                ui.turn.youAct && ui.trick.plays.size >= 2 && ui.trick.lastActions.values.any { it == SeatLastAction.Passed } && ui.controlsEnabled
            },
        )
        val before = vm.uiState.value
        val revision = solo.table!!.revision

        solo.owner.reconnect()
        // While rejoining, the last known trick stays on screen with controls disabled.
        val during = vm.uiState.value
        solo.run(1)
        assertEquals(before.trick, vm.uiState.value.trick)
        assertTrue(solo.runUntil(10_000) { vm.uiState.value.connection == ConnectionState.CONNECTED && vm.uiState.value.controlsEnabled })
        val after = vm.uiState.value
        assertEquals(revision, solo.table!!.revision)
        assertEquals(before.trick, after.trick)
        assertEquals(before.turn, after.turn)
        assertEquals(before.seats.map { it.lastAction }, after.seats.map { it.lastAction })
        assertEquals(before.trick, during.trick)

        // A screen created after the reconnect (late joiner on this device) shows the same.
        val fresh = solo.viewModel().uiState.value
        assertEquals(before.trick, fresh.trick)
        assertEquals(before.seats, fresh.seats)
        // And it equals what the projection alone gives.
        assertEquals(TrickDisplay.of(solo.table!!.view!!), after.trick)
    }

    /** Audit-fix #38: a seat that passed earlier in the trick still shows its pass after a later play. */
    @Test fun anEarlierPassStaysShownAfterALaterPlay() {
        fun single(seat: cn.wpgd.app.core.state.Seat, id: String) =
            cn.wpgd.app.core.state.PublicPlay(seat, listOf(cn.wpgd.app.card(id)), emptyList(), cn.wpgd.app.core.rules.CombinationType.SINGLE)
        val s = cn.wpgd.app.core.state.Seat.SOUTH
        // SOUTH leads, EAST passes, NORTH beats; WEST to act.
        val view = cn.wpgd.app.playingView(hand = listOf("0S5"), turn = cn.wpgd.app.core.state.Seat.WEST, trick = listOf(single(s, "0C5"), single(cn.wpgd.app.core.state.Seat.NORTH, "0C9")))
        val actions = TrickDisplay.of(view).lastActions
        assertEquals(SeatLastAction.Passed, actions.getValue(cn.wpgd.app.core.state.Seat.EAST))
        assertTrue(actions.getValue(s) is SeatLastAction.Played)
        assertTrue(actions.getValue(cn.wpgd.app.core.state.Seat.NORTH) is SeatLastAction.Played)
        assertEquals(SeatLastAction.None, actions.getValue(cn.wpgd.app.core.state.Seat.WEST))

        // A seat that has played out is skipped by the turn, so it did not pass.
        val out = cn.wpgd.app.playingView(hand = listOf("0S5"), turn = cn.wpgd.app.core.state.Seat.WEST, trick = listOf(single(s, "0C5"), single(cn.wpgd.app.core.state.Seat.NORTH, "0C9")), finish = listOf(cn.wpgd.app.core.state.Seat.EAST))
        assertEquals(SeatLastAction.Finished(1), TrickDisplay.of(out).lastActions.getValue(cn.wpgd.app.core.state.Seat.EAST))
    }
}
