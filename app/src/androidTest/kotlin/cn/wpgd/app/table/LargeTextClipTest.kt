package cn.wpgd.app.table

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.DeviceProfiles
import cn.wpgd.app.ScreenUnderTest
import cn.wpgd.app.TableFixtures
import cn.wpgd.app.allNodes
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.TributeKind
import cn.wpgd.app.core.match.TributeView
import cn.wpgd.app.core.rules.CombinationType
import cn.wpgd.app.core.state.PublicPlay
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.owner.CommandRejection
import cn.wpgd.app.owner.RejectionKey
import cn.wpgd.app.showTable
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GDN-098: at 200 % system text on a 320 dp screen the table switches to its alternate layout and
 * no text is clipped: every text node lays out without overflow and stays inside the screen width.
 */
@RunWith(AndroidJUnit4::class)
class LargeTextClipTest {
    @get:Rule val rule = createComposeRule()

    /**
     * Text that does not fully show: taller than its node, more lines than allowed, or a line wider
     * than its node (a non-wrapping label squeezed by a fixed-size parent, such as a card). The
     * layout's own `hasVisualOverflow` is not used: for a text narrower than its constraints the
     * semantics layout reports the paragraph at the constraint width and flags overflow falsely.
     */
    private fun clipped(): List<String> {
        val root = rule.onNodeWithTag("table", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        return rule.allNodes().filter { SemanticsProperties.Text in it.config }.mapNotNull { n ->
            val text = n.config[SemanticsProperties.Text].joinToString("") { it.text }
            val layouts = ArrayList<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val layout = layouts.firstOrNull() ?: return@mapNotNull "\"$text\": no text layout"
            val widest = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f
            val b = n.boundsInRoot
            when {
                layout.didOverflowHeight -> "\"$text\" is cut off vertically (${layout.multiParagraph.height} in ${n.size})"
                layout.multiParagraph.didExceedMaxLines -> "\"$text\" has more lines than allowed"
                widest > n.size.width + 0.5f -> "\"$text\" is cut off sideways ($widest in ${n.size})"
                b.right > root.right + 0.5f || b.left < root.left - 0.5f -> "\"$text\" leaves the screen ($b, screen $root)"
                else -> null
            }
        }
    }

    private fun SemanticsNode.isText() = SemanticsProperties.Text in config

    @Test fun playingTableWithOptionsAndARejectionHasNoClippedText() {
        val trick = listOf(PublicPlay(Seat.WEST, listOf("1S10", "1H10").map(TableFixtures::card), emptyList(), CombinationType.PAIR))
        val view = TableFixtures.playingView(DeviceProfiles.profiles, hand = TableFixtures.FULL_HAND, trick = trick, passed = listOf(Seat.NORTH))
        val screen = ScreenUnderTest(view)
        rule.showTable(screen.vm, width = 320.dp, fontScale = 2f)
        listOf("0H2", "0S10").forEach { rule.onNodeWithTag("card:$it", useUnmergedTree = true).performScrollTo().performClick() }
        screen.session.reject(CommandRejection(1, null, RejectionKey.OUTCOME_UNKNOWN))
        rule.waitForIdle()
        assertTrue(rule.allNodes().count { it.isText() } > 30)
        val bad = clipped()
        assertTrue("clipped text at 200 %:\n" + bad.joinToString("\n"), bad.isEmpty())

        rule.onNodeWithTag("button:menu").performClick()
        rule.waitForIdle()
        val badMenu = clipped()
        assertTrue("clipped text in the table menu at 200 %:\n" + badMenu.joinToString("\n"), badMenu.isEmpty())
    }

    @Test fun tributeStepHasNoClippedText() {
        val tribute = TributeView(TributeKind.SINGLE, listOf(Seat.SOUTH), listOf(Seat.EAST), emptyList(), emptyList(), emptyList(), null, emptyList())
        val view = TableFixtures.playingView(DeviceProfiles.profiles, hand = TableFixtures.FULL_HAND, phase = MatchPhase.TRIBUTE, tribute = tribute)
        rule.showTable(ScreenUnderTest(view).vm, width = 320.dp, fontScale = 2f)
        val bad = clipped()
        assertTrue("clipped text at 200 %:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
