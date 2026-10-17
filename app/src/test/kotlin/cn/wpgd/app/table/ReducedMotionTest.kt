package cn.wpgd.app.table

import cn.wpgd.app.VirtualSolo
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.TablePreferences
import cn.wpgd.app.drive
import cn.wpgd.app.human
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** GDN-080, audit A26 (no reduced-motion mode): reduced motion removes bomb flashes and card motion, keeping a static highlight. */
class ReducedMotionTest {
    @Test fun reducedMotionRemovesEveryFlashAndMotion() {
        val reduced = TablePreferences(reducedMotion = true)
        for (kind in CueKind.entries) {
            val out = EffectRouter.route(TableCue("id", kind, Seat.NORTH, 3), reduced)
            assertTrue(kind.name, out.none { it is EffectOutput.Flash || it is EffectOutput.Motion })
        }
        assertEquals(listOf(EffectOutput.Highlight("id", Seat.NORTH)), EffectRouter.route(TableCue("id", CueKind.BOMB, Seat.NORTH, 3), reduced).filterIsInstance<EffectOutput.Highlight>())
        assertTrue(EffectRouter.route(TableCue("id", CueKind.BOMB, Seat.NORTH, 3), TablePreferences()).any { it is EffectOutput.Flash })
    }

    @Test fun theScreensEffectsFollowTheSwitchAtOnce() {
        val solo = VirtualSolo().start()
        val vm = solo.viewModel()
        val outputs = ArrayList<EffectOutput>()
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).launch { vm.effects.collect { outputs += it } }
        vm.updatePreferences { it.copy(reducedMotion = true) }
        assertEquals(MotionMode.REDUCED, vm.uiState.value.motion)
        val human = solo.human(vm)
        assertTrue(solo.drive(human) { outputs.count { it is EffectOutput.Highlight } >= 5 })
        assertTrue(outputs.none { it is EffectOutput.Flash || it is EffectOutput.Motion })

        vm.updatePreferences { it.copy(reducedMotion = false) }
        val mark = outputs.size
        assertTrue(solo.drive(human) { outputs.drop(mark).any { it is EffectOutput.Motion } })
        assertEquals(MotionMode.FULL, vm.uiState.value.motion)
    }
}
