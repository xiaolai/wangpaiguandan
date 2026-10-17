package cn.wpgd.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.owner.RoomCondition
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GDN-100: the screen is kept on (window FLAG_KEEP_SCREEN_ON, no WAKE_LOCK permission) only while
 * seated at an active table in a deal; not on the menu, not while paused, not after leaving.
 */
@RunWith(AndroidJUnit4::class)
class WakeLockTest {
    @get:Rule val rule: AppRule = createAndroidComposeRule<MainActivity>()

    @Before fun noTable() = appGraph.closeTable()

    @After fun closeTable() = appGraph.closeTable()

    private fun flag(): Boolean {
        var on = false
        rule.activityRule.scenario.onActivity { on = keepScreenOn(it) }
        return on
    }

    @Test fun heldOnlyWhileSeatedAndPlaying() {
        rule.waitUntil(10_000) { rule.exists("menu:solo") }
        assertFalse("kept on at the menu", flag())

        rule.startSoloFromMenu()
        rule.waitUntil(30_000) { runningOwner.state.value.table?.view?.match?.phase == MatchPhase.PLAYING }
        rule.waitUntil(5_000) { flag() }

        rule.onNodeWithTag("button:menu").performClick()
        rule.onNodeWithTag("menu:pause", useUnmergedTree = true).performScrollTo().performClick()
        rule.waitUntil(5_000) { runningOwner.state.value.table?.room == RoomCondition.PAUSED }
        rule.waitUntil(5_000) { !flag() }

        rule.onNodeWithTag("menu:resume", useUnmergedTree = true).performScrollTo().performClick()
        rule.waitUntil(5_000) { runningOwner.state.value.table?.room == RoomCondition.ACTIVE }
        // The open menu covers the table, so resuming alone does not keep the screen on; closing it does.
        rule.waitForIdle()
        assertFalse("kept on while the menu covers the table", flag())
        rule.onNodeWithTag("menu:close", useUnmergedTree = true).performClick()
        rule.waitUntil(5_000) { flag() }

        rule.onNodeWithTag("button:menu").performClick()
        rule.onNodeWithTag("menu:leave", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithTag("menu:leave_confirm", useUnmergedTree = true).performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.exists("menu:solo") }
        rule.waitUntil(5_000) { !flag() }
    }
}
