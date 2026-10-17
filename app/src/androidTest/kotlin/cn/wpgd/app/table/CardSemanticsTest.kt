package cn.wpgd.app.table

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.R
import cn.wpgd.app.ScreenUnderTest
import cn.wpgd.app.TableFixtures
import cn.wpgd.app.core.Suit
import cn.wpgd.app.deviceView
import cn.wpgd.app.showTable
import cn.wpgd.app.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GDN-097, audit A25 (cards without deliberate semantics): every card speaks its rank, its suit and its wildcard role; its selected state is
 * announced and changes when the screen reader activates it (no gesture needed).
 */
@RunWith(AndroidJUnit4::class)
class CardSemanticsTest {
    @get:Rule val rule = createComposeRule()

    private fun suitName(s: Suit) = when (s) {
        Suit.SPADE -> str(R.string.suit_spade)
        Suit.HEART -> str(R.string.suit_heart)
        Suit.CLUB -> str(R.string.suit_club)
        Suit.DIAMOND -> str(R.string.suit_diamond)
        Suit.JOKER -> ""
    }

    private fun rankName(rank: Int) = when (rank) {
        11 -> "J"; 12 -> "Q"; 13 -> "K"; 14 -> "A"
        16 -> str(R.string.rank_small_joker); 17 -> str(R.string.rank_big_joker)
        else -> rank.toString()
    }

    @Test fun everyCardSpeaksRankSuitWildcardRoleAndSelection() {
        val screen = ScreenUnderTest(deviceView(TableFixtures.FULL_HAND))
        rule.showTable(screen.vm)
        for (id in TableFixtures.FULL_HAND) {
            val card = TableFixtures.card(id)
            val node = rule.onNodeWithTag("card:$id", useUnmergedTree = true).performScrollTo()
            val config = node.fetchSemanticsNode().config
            val description = config[SemanticsProperties.ContentDescription].joinToString(" ")
            assertTrue("$id: $description", description.contains(rankName(card.rank)))
            if (card.suit != Suit.JOKER) assertTrue("$id: $description", description.contains(suitName(card.suit)))
            val wild = card.suit == Suit.HEART && card.rank == 2
            assertEquals("$id wildcard role: $description", wild, description.contains(str(R.string.card_wildcard)))
            assertEquals(Role.Checkbox, config.getOrNull(SemanticsProperties.Role))
            assertEquals(str(R.string.card_not_selected), config[SemanticsProperties.StateDescription])
            assertFalse(config[SemanticsProperties.Selected])

            // Activation through the accessibility action, as TalkBack does it.
            node.performSemanticsAction(SemanticsActions.OnClick)
            rule.waitForIdle()
            val after = rule.onNodeWithTag("card:$id", useUnmergedTree = true).fetchSemanticsNode().config
            assertEquals(str(R.string.card_selected), after[SemanticsProperties.StateDescription])
            assertTrue(after[SemanticsProperties.Selected])
        }
        assertEquals(27, screen.vm.state().hand.selectedIds.size)
    }

    @Test fun theWildcardBadgeIsVisibleTextNotOnlyColour() {
        rule.showTable(ScreenUnderTest(deviceView(TableFixtures.FULL_HAND)).vm)
        for (id in listOf("0H2", "1H2")) {
            val badge = rule.onNodeWithTag("badge:$id", useUnmergedTree = true).fetchSemanticsNode()
            assertEquals(str(R.string.card_wildcard_badge), badge.config[SemanticsProperties.Text].joinToString("") { it.text })
        }
        val missing = runCatching { rule.onNodeWithTag("badge:0S2", useUnmergedTree = true).fetchSemanticsNode() }
        assertTrue("a natural 2 has no wildcard badge", missing.isFailure)
    }
}
