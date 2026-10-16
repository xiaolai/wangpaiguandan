package cn.wpgd.app.table

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.DeviceProfiles
import cn.wpgd.app.R
import cn.wpgd.app.ScreenUnderTest
import cn.wpgd.app.TableFixtures
import cn.wpgd.app.allNodes
import cn.wpgd.app.core.match.CountReport
import cn.wpgd.app.core.rules.CombinationType
import cn.wpgd.app.core.state.PublicPlay
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.showTable
import cn.wpgd.app.spokenText
import cn.wpgd.app.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GDN-079: another seat's card count is hidden. Nothing in the semantics tree (text, content or
 * state descriptions, on any node) states how many cards another seat holds, unless the profile
 * made that count public (a threshold report), and then only that count, on that seat.
 */
@RunWith(AndroidJUnit4::class)
class HiddenCountSemanticsTest {
    @get:Rule val rule = createComposeRule()

    private val hand = listOf("0S3", "0H4", "0C5", "0D6", "0S7", "0H8", "0C9", "0D10", "0SJ", "0HQ")

    // Opponents and partner have played, so their counts (27 − played) are derivable by the host only.
    private val trick = listOf(
        PublicPlay(Seat.EAST, listOf("1S9", "1H9").map(TableFixtures::card), emptyList(), CombinationType.PAIR),
        PublicPlay(Seat.NORTH, listOf("1SK", "1HK").map(TableFixtures::card), emptyList(), CombinationType.PAIR),
    )

    /** (nearest seat tag of the node or its ancestors, number) for every "N 张" anywhere in the tree. */
    private fun counts(): List<Pair<String, String>> {
        val countPattern = Regex("""(\d+)\s*张""")
        val out = ArrayList<Pair<String, String>>()
        fun walk(n: SemanticsNode, seat: String) {
            val tag = n.config.getOrElse(SemanticsProperties.TestTag) { "" }
            val here = if (tag.startsWith("seat:")) tag else seat
            n.spokenText().forEach { t -> countPattern.findAll(t).forEach { out += here to it.groupValues[1] } }
            n.children.forEach { walk(it, here) }
        }
        walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode(), "")
        return out
    }

    @Test fun noOtherSeatsCountIsInTheSemanticsTree() {
        val view = TableFixtures.playingView(DeviceProfiles.profiles, hand = hand, turn = Seat.WEST, trick = trick)
        rule.showTable(ScreenUnderTest(view).vm)
        val numbers = counts().map { it.second }.toSet()
        assertEquals("only the own hand's count may appear: ${counts()}", setOf(hand.size.toString()), numbers)
        // The checker reads the tree it claims to read.
        assertTrue(rule.allNodes().any { n -> n.spokenText().any { it.contains(str(R.string.hand_count, hand.size)) } })
    }

    @Test fun aPublicReportShowsOnlyThatCountOnThatSeat() {
        val view = TableFixtures.playingView(DeviceProfiles.profiles, hand = hand, turn = Seat.WEST, trick = trick, reports = listOf(CountReport(Seat.EAST, 5)))
        rule.showTable(ScreenUnderTest(view).vm)
        val found = counts()
        assertEquals(setOf(hand.size.toString(), "5"), found.map { it.second }.toSet())
        assertTrue("the reported count belongs to the east seat: $found", found.filter { it.second == "5" }.all { it.first == "seat:EAST" })
        assertTrue("the own hand count is not on a seat: $found", found.filter { it.second == hand.size.toString() }.all { it.first == "" })
    }
}
