package cn.wpgd.app.table

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.ScreenUnderTest
import cn.wpgd.app.TableFixtures
import cn.wpgd.app.allNodes
import cn.wpgd.app.deviceView
import cn.wpgd.app.showTable
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** GDN-098, audit A25 (28 dp icons): every control on the table, including every card, has a touch target of at least 48 dp. */
@RunWith(AndroidJUnit4::class)
class TouchTargetTest {
    @get:Rule val rule = createComposeRule()

    private fun tooSmall(): List<String> {
        val density = rule.density
        val min = with(density) { 48.dp.toPx() } - 0.5f
        return rule.allNodes()
            .filter { SemanticsActions.OnClick in it.config }
            .filter { width(it) < min || height(it) < min }
            .map { describe(it, density) }
    }

    // A node scrolled out of view has clipped (empty) bounds in the root: then its own layout size counts.
    private fun width(n: SemanticsNode) = maxOf(n.touchBoundsInRoot.width, n.size.width.toFloat())

    private fun height(n: SemanticsNode) = maxOf(n.touchBoundsInRoot.height, n.size.height.toFloat())

    private fun describe(n: SemanticsNode, density: androidx.compose.ui.unit.Density): String {
        val tag = n.config.getOrElse(SemanticsProperties.TestTag) { "?" }
        val w: Dp = with(density) { width(n).toDp() }
        val h: Dp = with(density) { height(n).toDp() }
        return "$tag ${w}x$h"
    }

    private fun check(width: Dp) {
        val screen = ScreenUnderTest(deviceView(TableFixtures.FULL_HAND))
        rule.showTable(screen.vm, width = width)
        // Show the reading options too: select a wildcard straight.
        listOf("0H2", "0S3", "0D4", "0S5", "0S6").forEach { rule.onNodeWithTag("card:$it", useUnmergedTree = true).performScrollTo().performClick() }
        rule.waitForIdle()
        val clickable = rule.allNodes().count { SemanticsActions.OnClick in it.config }
        assertTrue("expected the 27 cards and the controls, found $clickable clickable nodes", clickable >= 27 + 4)
        val small = tooSmall()
        assertTrue("touch targets under 48 dp at $width: $small", small.isEmpty())

        rule.onNodeWithTag("button:menu").performClick()
        rule.waitForIdle()
        val smallInMenu = tooSmall()
        assertTrue("touch targets under 48 dp in the table menu at $width: $smallInMenu", smallInMenu.isEmpty())
    }

    @Test fun everyTargetIsAtLeast48dpAt320dp() = check(320.dp)

    @Test fun everyTargetIsAtLeast48dpAt411dp() = check(411.dp)
}
