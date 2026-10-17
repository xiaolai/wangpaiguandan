package cn.wpgd.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.table.TableScreen
import cn.wpgd.app.table.TableUiState
import cn.wpgd.app.table.TableViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GDN-005: a solo deal (one human, three AI seats, production timings) played to the result screen
 * entirely through the UI: menu button, card taps, the tribute, pass and play buttons, and the
 * next-deal prompt. The test only reads the state to decide; every action is a click.
 */
@RunWith(AndroidJUnit4::class)
class SoloDealEndToEndUiTest {
    @get:Rule val rule: AppRule = createAndroidComposeRule<MainActivity>()

    @Before fun noTable() = appGraph.closeTable()

    @After fun closeTable() = appGraph.closeTable()

    /**
     * A second, read-only view model over the same owner: the same mapping the screen uses, so the
     * test decides from what the screen shows. It never sends anything.
     */
    private var probe: TableViewModel? = null

    private fun screenState(): TableUiState? {
        val owner = appGraph.runningOwner() ?: return null
        val vm = probe ?: TableViewModel(owner, appGraph.preferences, CoroutineScope(SupervisorJob() + Dispatchers.Default)).also { probe = it }
        return vm.state()
    }

    @Test fun aSoloDealReachesTheResultScreenThroughTheUi() {
        rule.startSoloFromMenu()
        var clicks = 0
        val deadline = System.currentTimeMillis() + 8 * 60_000
        while (screenState()?.screen != TableScreen.DEAL_RESULT) {
            check(System.currentTimeMillis() < deadline) { "the deal did not finish; last state ${screenState()}" }
            val ui = screenState()
            val decision = ui?.let(SimpleHuman::decide)
            if (decision == null) {
                Thread.sleep(100)
                continue
            }
            val (cards, button) = decision
            if (rule.exists("button:clear") && rule.isEnabled("button:clear")) rule.onNodeWithTag("button:clear").performClick()
            cards.forEach { rule.onNodeWithTag("card:$it", useUnmergedTree = true).performScrollTo().performClick() }
            rule.waitUntil(5_000) { rule.isEnabled(button) }
            rule.onNodeWithTag(button).performClick()
            clicks++
            rule.waitUntil(15_000) { screenState()?.let { !it.pending } ?: true }
        }
        rule.waitUntil(5_000) { rule.exists("result") }
        assertTrue("the human never acted", clicks > 0)
        assertTrue(rule.exists("button:next_deal"))
        assertTrue(rule.isEnabled("button:next_deal"))
        rule.onNodeWithTag("button:next_deal").performClick()
        rule.waitUntil(30_000) { runningOwner.state.value.table?.view?.match?.dealNumber == 2 }
    }
}
