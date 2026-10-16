package cn.wpgd.app.table

import cn.wpgd.app.VirtualSolo
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.drive
import cn.wpgd.app.human
import cn.wpgd.app.owner.RoomCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GDN-100, audit A27 (screen always kept awake): hold the screen awake only while seated and playing; the menu and a completed table release it. */
class WakeLockTest {
    private val playing = WakeInputs(seated = true, phase = MatchPhase.PLAYING, room = RoomCondition.ACTIVE, tableVisible = true)

    @Test fun holdOnlyWhileSeatedAndPlaying() {
        assertTrue(WakeLockPolicy.shouldHold(playing))
        for (phase in listOf(MatchPhase.DEALT, MatchPhase.TRIBUTE, MatchPhase.RETURN, MatchPhase.READY, MatchPhase.DEAL_ENDED)) {
            assertTrue(phase.name, WakeLockPolicy.shouldHold(playing.copy(phase = phase)))
        }
        assertFalse("spectator", WakeLockPolicy.shouldHold(playing.copy(seated = false)))
        assertFalse("menu or settings in front", WakeLockPolicy.shouldHold(playing.copy(tableVisible = false)))
        assertFalse("result between deals", WakeLockPolicy.shouldHold(playing.copy(phase = MatchPhase.AWAITING_DEAL)))
        assertFalse("completed table", WakeLockPolicy.shouldHold(playing.copy(phase = MatchPhase.OVER)))
        assertFalse("no table", WakeLockPolicy.shouldHold(playing.copy(phase = null)))
        for (room in RoomCondition.entries - RoomCondition.ACTIVE) assertFalse(room.name, WakeLockPolicy.shouldHold(playing.copy(room = room)))
    }

    @Test fun theControllerTellsTheSinkOnlyOnChange() {
        val calls = ArrayList<Boolean>()
        val c = WakeLockController { calls += it }
        c.update(playing.copy(seated = false))
        c.update(playing)
        c.update(playing)
        c.update(playing.copy(tableVisible = false))
        c.update(playing)
        c.release()
        c.release()
        assertEquals(listOf(true, false, true, false), calls)
    }

    @Test fun theTableScreenKeepsTheScreenOnOnlyDuringTheDeal() {
        val solo = VirtualSolo().start()
        val vm = solo.viewModel()
        assertTrue(vm.uiState.value.keepScreenOn)
        vm.setTableVisible(false)
        assertFalse(vm.uiState.value.keepScreenOn)
        vm.setTableVisible(true)
        assertTrue(vm.uiState.value.keepScreenOn)
        val human = solo.human(vm)
        assertTrue(solo.drive(human) { solo.table?.view?.match?.lastResult != null })
        assertEquals(MatchPhase.AWAITING_DEAL, vm.uiState.value.phase)
        assertFalse("the result screen releases it", vm.uiState.value.keepScreenOn)
        solo.owner.shutdown()
        assertFalse(vm.uiState.value.keepScreenOn)
    }
}
