package cn.wpgd.app.table

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.DeviceProfiles
import cn.wpgd.app.R
import cn.wpgd.app.ScreenUnderTest
import cn.wpgd.app.TableFixtures
import cn.wpgd.app.core.rules.CombinationType
import cn.wpgd.app.core.state.PublicPlay
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.showTable
import cn.wpgd.app.spokenText
import cn.wpgd.app.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GDN-077: who owns the trick and who acts next are stated in visible text and in the semantics
 * of the seats, by relative seat name (上家, 下家, 对家), never by colour or position alone.
 */
@RunWith(AndroidJUnit4::class)
class TurnClarityTest {
    @get:Rule val rule = createComposeRule()

    private fun turnText() = rule.onNodeWithTag("turn", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun seatSpeech(seat: Seat) = rule.onNodeWithTag("seat:${seat.name}", useUnmergedTree = true).fetchSemanticsNode().spokenText().joinToString(" ")

    private val pairOfSixes = PublicPlay(Seat.WEST, listOf("0S6", "0H6").map(TableFixtures::card), emptyList(), CombinationType.PAIR)

    @Test fun youFollowThePreviousSeatsPair() {
        val view = TableFixtures.playingView(DeviceProfiles.profiles, hand = listOf("0S7", "0H7", "0C9"), turn = Seat.SOUTH, trick = listOf(pairOfSixes))
        rule.showTable(ScreenUnderTest(view).vm)
        assertEquals(str(R.string.turn_you_beat_or_pass, str(R.string.seat_previous), str(R.string.combo_pair)), turnText())
        assertTrue(seatSpeech(Seat.WEST), seatSpeech(Seat.WEST).contains(str(R.string.seat_trick_owner)))
        assertTrue(seatSpeech(Seat.SOUTH), seatSpeech(Seat.SOUTH).contains(str(R.string.seat_acting)))
        for (s in listOf(Seat.EAST, Seat.NORTH)) {
            assertFalse(seatSpeech(s), seatSpeech(s).contains(str(R.string.seat_acting)))
            assertFalse(seatSpeech(s), seatSpeech(s).contains(str(R.string.seat_trick_owner)))
        }
    }

    @Test fun theNextSeatFollowsYourPlay() {
        val yours = PublicPlay(Seat.SOUTH, listOf("0S8", "0H8").map(TableFixtures::card), emptyList(), CombinationType.PAIR)
        val view = TableFixtures.playingView(DeviceProfiles.profiles, hand = listOf("0C9"), turn = Seat.EAST, trick = listOf(pairOfSixes, yours))
        rule.showTable(ScreenUnderTest(view).vm)
        assertEquals(str(R.string.turn_other_follows, str(R.string.seat_next), str(R.string.seat_self), str(R.string.combo_pair)), turnText())
        assertTrue(seatSpeech(Seat.EAST).contains(str(R.string.seat_acting)))
        assertTrue(seatSpeech(Seat.SOUTH).contains(str(R.string.seat_trick_owner)))
        assertFalse(seatSpeech(Seat.WEST).contains(str(R.string.seat_trick_owner)))
        // Each seat states its last action in words.
        assertTrue(seatSpeech(Seat.WEST).contains(str(R.string.seat_last_played, str(R.string.combo_pair) + " 6 6")))
    }

    @Test fun thePartnerLeadsAfterEveryonePassed() {
        val view = TableFixtures.playingView(DeviceProfiles.profiles, hand = listOf("0C9"), turn = Seat.NORTH)
        rule.showTable(ScreenUnderTest(view).vm)
        assertEquals(str(R.string.turn_other_leads, str(R.string.seat_partner)), turnText())
        assertTrue(seatSpeech(Seat.NORTH).contains(str(R.string.seat_acting)))
    }
}
