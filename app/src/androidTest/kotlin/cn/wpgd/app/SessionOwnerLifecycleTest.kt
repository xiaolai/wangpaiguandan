package cn.wpgd.app

import android.app.UiAutomation
import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GDN-089: the table lives in the application-scoped owner, so recreating the activity and rotating
 * the screen keep the same table, the same hand on screen and the player's selection.
 */
@RunWith(AndroidJUnit4::class)
class SessionOwnerLifecycleTest {
    @get:Rule val rule: AppRule = createAndroidComposeRule<MainActivity>()

    @Before fun noTable() = appGraph.closeTable()

    @After fun closeTable() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        appGraph.closeTable()
    }

    /**
     * Rotates the display (a real configuration change) and waits for the activity to see it
     * before syncing. Not `requestedOrientation`: on the API 36 emulator the Compose test's idle
     * check intermittently never returns after an activity-requested orientation change, even on
     * the plain menu (2 of 12 probe runs; 0 of 15 with this method; the app itself stayed responsive).
     */
    private fun rotate(rotation: Int, orientation: Int) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.setRotation(rotation)
        rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == orientation }
        rule.waitForIdle()
    }

    private fun handIds(): List<String> = runningOwner.state.value.table!!.view!!.deal!!.hand.map { it.id }

    private fun assertSameTable(owner: cn.wpgd.app.owner.SessionOwner, tableId: String, hand: List<String>, selected: String) {
        rule.waitUntil(15_000) { rule.exists("card:$selected") }
        assertSame("a new owner was created", owner, appGraph.runningOwner())
        assertEquals(tableId, owner.state.value.table!!.tableId)
        assertEquals(1L, owner.metrics.connections.get())
        // The hand only changes by the player's own actions; nobody acted.
        assertEquals(hand, handIds())
        for (id in hand) assertTrue("card $id is not on screen", rule.exists("card:$id"))
        rule.onNodeWithTag("card:$selected", useUnmergedTree = true).performScrollTo().assertIsSelected()
    }

    @Test fun recreationAndRotationKeepTheTableTheHandAndTheSelection() {
        rule.startSoloFromMenu()
        val owner = runningOwner
        rule.waitUntil(30_000) { owner.state.value.table?.view?.deal?.hand?.size == 27 }
        val tableId = owner.state.value.table!!.tableId
        val hand = handIds()
        val selected = hand.first()
        // A tap is ignored while the controls are disabled (TableViewModel.select): wait until they are
        // enabled, and check the tap took, so the assertions below test recreation and nothing else.
        rule.waitUntil(15_000) {
            val s = owner.state.value
            val t = s.table
            s.lifecycle == cn.wpgd.app.owner.OwnerLifecycle.RUNNING && t != null && t.pending == null &&
                t.connection == cn.wpgd.app.owner.ConnectionState.CONNECTED && t.room == cn.wpgd.app.owner.RoomCondition.ACTIVE
        }
        rule.onNodeWithTag("card:$selected", useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("card:$selected", useUnmergedTree = true).assertIsSelected()

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertSameTable(owner, tableId, hand, selected)

        rotate(UiAutomation.ROTATION_FREEZE_90, Configuration.ORIENTATION_LANDSCAPE)
        assertSameTable(owner, tableId, hand, selected)

        rotate(UiAutomation.ROTATION_FREEZE_0, Configuration.ORIENTATION_PORTRAIT)
        assertSameTable(owner, tableId, hand, selected)
    }

    @Test fun leavingTheTableShutsTheOwnerDown() {
        rule.startSoloFromMenu()
        val owner = runningOwner
        rule.onNodeWithTag("button:menu").performClick()
        rule.onNodeWithTag("menu:leave", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithTag("menu:leave_confirm", useUnmergedTree = true).performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.exists("menu:solo") }
        rule.waitUntil(10_000) { owner.terminated }
        assertEquals(null, appGraph.runningOwner())
        assertEquals(0, owner.scheduledTasks)
    }
}
