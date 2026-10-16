package cn.wpgd.app.owner

import cn.wpgd.app.VirtualSolo
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.drive
import cn.wpgd.app.human
import cn.wpgd.app.table.TableScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GDN-005, GDN-014 (solo half): a full solo deal, then a second one with tribute, driven end to end
 * through the SessionOwner by a scripted human acting only through the TableViewModel.
 */
class SoloDealEndToEndTest {
    @Test fun twoSoloDealsCompleteThroughTheOwnerAndTheScreen() {
        for (seed in listOf(7L, 11L, 23L)) {
            val solo = VirtualSolo(seed).start()
            val vm = solo.viewModel()
            val human = solo.human(vm, seed)
            assertEquals(Seat.SOUTH, solo.table?.seat)

            val first = solo.drive(human) { solo.table?.view?.match?.lastResult?.dealNumber == 1 }
            assertTrue("seed $seed: deal 1 did not complete: ${solo.owner.state.value}", first)
            val ui = vm.uiState.value
            assertEquals(TableScreen.DEAL_RESULT, ui.screen)
            assertTrue(ui.actions.continueToNextDeal)
            val result = ui.result!!
            assertTrue(result.lines.isNotEmpty())
            assertEquals(1, solo.history.entries().size)
            assertTrue("seed $seed: the human never acted", human.actions > 0)

            assertTrue(vm.continueToNextDeal())
            val second = solo.drive(human) { solo.table?.view?.match?.let { it.lastResult?.dealNumber == 2 || it.phase == MatchPhase.OVER } == true }
            assertTrue("seed $seed: deal 2 did not complete: ${solo.owner.state.value}", second)
            assertEquals(2, solo.history.entries().size)
            // The second deal had a tribute step (or a resistance), and many commits happened.
            val view = solo.table!!.view!!
            assertTrue(view.match.tribute != null || view.match.phase == MatchPhase.OVER)
            assertTrue("seed $seed: only ${solo.table!!.revision} revisions", solo.table!!.revision > 40)
            assertEquals(setOf("wpgd-ai"), solo.executor!!.threads.map { it.name }.toSet())

            val state = solo.owner.state.value
            assertEquals(OwnerLifecycle.RUNNING, state.lifecycle)
            assertNull(state.failure)
            assertEquals(1L, solo.owner.metrics.connections.get())
            solo.owner.shutdown()
        }
    }
}
