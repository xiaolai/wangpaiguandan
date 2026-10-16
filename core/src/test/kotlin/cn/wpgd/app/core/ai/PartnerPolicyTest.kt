package cn.wpgd.app.core.ai

import cn.wpgd.app.core.match.CountReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** GDN-057, audit A08: the AI knows its partner, the trick owner and the public counts. */
class PartnerPolicyTest {
    private val south = listOf("0SJ", "0HQ", "0CK", "0D3", "1D4", "0S9", "1S9")
    private val others = mapOf(E to listOf("0C3", "1C3"), N to listOf("0C4", "1C4"), W to listOf("0C5", "1C5"))

    /** Partner (NORTH) holds the trick with a single 10; SOUTH could beat it but passes. */
    @Test fun doesNotOvertakeAPartnersSingle() {
        val state = playing(2, others + (S to south), turn = S, trick = listOf(tp(N, "0S10")), passed = listOf(E))
        assertTrue(hostInput(state, S).choices.plays.isNotEmpty(), "SOUTH can beat the 10")
        assertEquals(AiAction.Pass, decide(state, S).action)
    }

    /** Partner's pair of fives; SOUTH holds a pair of nines and passes. */
    @Test fun doesNotOvertakeAPartnersPair() {
        val state = playing(2, others + (S to south), turn = S, trick = listOf(tp(N, "0D5", "1D5")), passed = listOf(E))
        assertEquals(AiAction.Pass, decide(state, S).action)
    }

    /** Partner led and an opponent overtook: now the trick belongs to the opponent, and SOUTH contests it. */
    @Test fun contestsAnOpponentWhoOvertookThePartner() {
        val state = playing(2, others + (S to south), turn = S, trick = listOf(tp(N, "0S8"), tp(E, "0S10")))
        assertNotEquals(AiAction.Pass, decide(state, S).action)
    }

    /** The same table with the 10 played by an opponent: SOUTH beats it. Only the owner of the trick changed. */
    @Test fun beatsTheSamePlayFromAnOpponent() {
        val state = playing(2, others + (S to south), turn = S, trick = listOf(tp(W, "0S10")), passed = listOf(N, E))
        assertNotEquals(AiAction.Pass, decide(state, S).action)
    }

    /** The documented exception: overtaking the partner is allowed when it plays out the whole hand. */
    @Test fun overtakesThePartnerOnlyToGoOut() {
        val state = playing(2, others + (S to listOf("0SK")), turn = S, trick = listOf(tp(N, "0S10")), passed = listOf(E))
        assertEquals(setOf("0SK"), decide(state, S).action.playedIds())
    }

    /** A finished partner owns nothing: following its last play, the opponent's later play is contested. */
    @Test fun partnerWhoPlayedOutIsNotProtectedAfterAnOpponentPlay() {
        val state = playing(2, mapOf(S to south, E to listOf("0C3", "1C3"), W to listOf("0C5", "1C5")), turn = S, trick = listOf(tp(W, "0S10")), finishOrder = listOf(N))
        assertNotEquals(AiAction.Pass, decide(state, S).action)
    }

    /**
     * Uses the counts the table made public, and only those: when the partner has reported one card
     * left, SOUTH leads a low single the partner can beat (feeding); without the report it does not.
     */
    @Test fun feedsAPartnerWhoReportedOneCard() {
        val hand = listOf("0D3", "1D3", "0S9", "1S9", "0C9", "0HJ", "1HJ", "0SQ", "0HQ", "1CQ", "0CK", "1CK")
        val quiet = playing(2, others + (S to hand), turn = S)
        val reported = playing(2, others + (S to hand), turn = S, reports = listOf(CountReport(N, 1)))
        val withReport = assertIs<AiAction.Play>(decide(reported, S).action)
        assertEquals(1, withReport.action.cards.size, "feeds a single")
        assertTrue(withReport.action.combination.key <= 10, "a low single the partner can beat")
        val without = assertIs<AiAction.Play>(decide(quiet, S).action)
        assertNotEquals(withReport.action, without.action, "the report changed the lead")
    }

    /** An opponent with one reported card makes SOUTH avoid leading a single it could follow. */
    @Test fun avoidsLeadingASingleIntoAnOpponentWithOneCard() {
        val hand = listOf("0D3", "0S9", "1S9", "0HJ", "1HJ")
        val state = playing(2, others + (S to hand), turn = S, reports = listOf(CountReport(E, 1)))
        val play = assertIs<AiAction.Play>(decide(state, S).action)
        assertTrue(play.action.cards.size >= 2, "led ${play.action.combination.type}")
    }
}
