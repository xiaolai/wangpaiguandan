package cn.wpgd.app.table

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.ScreenUnderTest
import cn.wpgd.app.TableFixtures
import cn.wpgd.app.deviceView
import cn.wpgd.app.showTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GDN-073 and the risk "large text breaks the 27-card hand": every card of a full 27-card hand can
 * be reached and selected by tap at 320 dp width and font scale 2.0 (and at the default scale),
 * with no sideways scrolling.
 */
@RunWith(AndroidJUnit4::class)
class CardSelectionTest {
    @get:Rule val rule = createComposeRule()

    private fun selectEveryCard(fontScale: Float) {
        val screen = ScreenUnderTest(deviceView(TableFixtures.FULL_HAND))
        rule.showTable(screen.vm, width = 320.dp, fontScale = fontScale)
        assertEquals(27, TableFixtures.FULL_HAND.size)
        for (id in TableFixtures.FULL_HAND) {
            val node = rule.onNodeWithTag("card:$id", useUnmergedTree = true)
            node.performScrollTo().assertIsNotSelected().performClick()
            rule.waitForIdle()
            node.assertIsSelected()
        }
        assertEquals(TableFixtures.FULL_HAND.toSet(), screen.vm.state().hand.selectedIds.toSet())

        // No card lies outside the 320 dp column: the hand never scrolls sideways.
        val root = rule.onNodeWithTag("table", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        for (id in TableFixtures.FULL_HAND) {
            val b = rule.onNodeWithTag("card:$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("card $id at $b leaves the table $root", b.left >= root.left - 0.5f && b.right <= root.right + 0.5f)
        }

        // A second tap deselects exactly that card.
        val first = TableFixtures.FULL_HAND.first()
        rule.onNodeWithTag("card:$first", useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("card:$first", useUnmergedTree = true).assertIsNotSelected()
        assertEquals(26, screen.vm.state().hand.selectedIds.size)
        val selectedNodes = TableFixtures.FULL_HAND.count {
            rule.onNodeWithTag("card:$it", useUnmergedTree = true).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false }
        }
        assertEquals(26, selectedNodes)
    }

    @Test fun all27CardsSelectableAt320dpAndFontScale2() = selectEveryCard(2.0f)

    @Test fun all27CardsSelectableAt320dpAndDefaultFontScale() = selectEveryCard(1.0f)
}
