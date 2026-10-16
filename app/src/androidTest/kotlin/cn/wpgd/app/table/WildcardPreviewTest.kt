package cn.wpgd.app.table

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.R
import cn.wpgd.app.ScreenUnderTest
import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.rules.CombinationType
import cn.wpgd.app.core.rules.WildcardAssignment
import cn.wpgd.app.deviceView
import cn.wpgd.app.session.wire.PlayBody
import cn.wpgd.app.session.wire.WireAssignment
import cn.wpgd.app.showTable
import cn.wpgd.app.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GDN-076: a wildcard play with several readings shows each one; nothing can be played until one
 * is chosen; the preview names exactly the reading, and the command sent is exactly that preview.
 */
@RunWith(AndroidJUnit4::class)
class WildcardPreviewTest {
    @get:Rule val rule = createComposeRule()

    // Level 2: ♥2 is the wildcard. ♥2 + ♠5 6 7 8 reads as 4–8 or 5–9, straight or straight flush.
    private val selection = listOf("0H2", "0S5", "0S6", "0S7", "0S8")

    private fun text(tag: String): String =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    @Test fun theChosenPreviewIsExactlyWhatIsSent() {
        val screen = ScreenUnderTest(deviceView(selection + listOf("0D9", "1CK")))
        rule.showTable(screen.vm)
        selection.forEach { rule.onNodeWithTag("card:$it", useUnmergedTree = true).performScrollTo().performClick() }
        rule.waitForIdle()

        val preview = screen.vm.state().preview as PlayPreview.Options
        assertEquals(4, preview.options.size)
        val optionNodes = rule.onAllNodes(SemanticsMatcher("an option") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("option:") == true }, useUnmergedTree = true)
        assertEquals(4, optionNodes.fetchSemanticsNodes().size)
        rule.onNodeWithTag("button:play").assertIsNotEnabled()

        val pick = preview.options.single { it.type == CombinationType.STRAIGHT && it.declaration == listOf(WildcardAssignment(9)) }
        rule.onNodeWithTag("option:${pick.key}", useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()

        // The preview line names the type, every card and what the wildcard stands for.
        val shown = text("preview:payload")
        assertTrue(shown, shown.contains(str(R.string.combo_straight)))
        assertTrue(shown, shown.contains(str(R.string.preview_wild_as, "♥2", "9")))
        assertTrue(shown, !shown.contains(str(R.string.combo_straight_flush)))

        rule.onNodeWithTag("button:play").assertIsEnabled().performClick()
        rule.waitForIdle()
        val sent = screen.session.submitted.single() as PlayBody
        assertEquals(pick.payload, sent)
        assertEquals(PlayBody(selection, listOf(WireAssignment(9, null)), CombinationType.STRAIGHT), sent)
    }

    @Test fun aStraightFlushReadingNamesItsSuit() {
        val screen = ScreenUnderTest(deviceView(selection))
        rule.showTable(screen.vm)
        selection.forEach { rule.onNodeWithTag("card:$it", useUnmergedTree = true).performScrollTo().performClick() }
        rule.waitForIdle()
        val preview = screen.vm.state().preview as PlayPreview.Options
        val pick = preview.options.single { it.type == CombinationType.STRAIGHT_FLUSH && it.declaration == listOf(WildcardAssignment(4, Suit.SPADE)) }
        rule.onNodeWithTag("option:${pick.key}", useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
        val shown = text("preview:payload")
        assertTrue(shown, shown.contains(str(R.string.combo_straight_flush)))
        assertTrue(shown, shown.contains(str(R.string.preview_wild_as, "♥2", "♠4")))
        rule.onNodeWithTag("button:play").performClick()
        rule.waitForIdle()
        assertEquals(pick.payload, screen.session.submitted.single())
    }

    @Test fun aDoubleTapOnPlaySendsOneCommand() {
        val screen = ScreenUnderTest(deviceView(listOf("0S5", "0S6")))
        rule.showTable(screen.vm)
        rule.onNodeWithTag("card:0S5", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        val play = rule.onNodeWithTag("button:play")
        play.performClick()
        play.performClick()
        rule.waitForIdle()
        assertEquals("the screen tried to send more than one command", 1, screen.session.attempts.size)
        // Pending: the button stays disabled and the selection is kept until the receipt.
        play.assertIsNotEnabled()
        rule.onNodeWithTag("pending", useUnmergedTree = true).assertExists()
        assertEquals(listOf("0S5"), screen.vm.state().hand.selectedIds)
    }
}
