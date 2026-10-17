package cn.wpgd.app.table

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.ScreenUnderTest
import cn.wpgd.app.TableFixtures
import cn.wpgd.app.deviceView
import cn.wpgd.app.showTable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Round 3 #30: while the table menu is open, a screen reader cannot reach the hand or the play controls behind it. */
@RunWith(AndroidJUnit4::class)
class MenuModalSemanticsTest {
    @get:Rule val rule = createComposeRule()

    // The merged tree is what accessibility services read; nodes under clearAndSetSemantics are absent from it.
    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test fun theOpenMenuHidesTheTableFromAccessibility() {
        val screen = ScreenUnderTest(deviceView(TableFixtures.FULL_HAND))
        rule.showTable(screen.vm)
        rule.waitForIdle()
        assertEquals(1, count("card:0H2"))
        assertEquals(1, count("button:menu"))

        rule.onNodeWithTag("button:menu").performClick()
        rule.waitForIdle()
        assertEquals("the menu is reachable", 1, count("menu:close"))
        assertEquals("a card behind the menu is not", 0, count("card:0H2"))
        assertEquals("the menu button behind the menu is not", 0, count("button:menu"))

        rule.onNodeWithTag("menu:close").performClick()
        rule.waitForIdle()
        assertEquals("closing the menu gives the table back", 1, count("card:0H2"))
    }
}
