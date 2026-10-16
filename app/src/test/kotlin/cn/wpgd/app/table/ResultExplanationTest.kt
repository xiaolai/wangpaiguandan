package cn.wpgd.app.table

import cn.wpgd.app.TestProfiles
import cn.wpgd.app.core.match.DealResult
import cn.wpgd.app.core.match.MatchSettings
import cn.wpgd.app.core.match.Settlement
import cn.wpgd.app.core.match.Team
import cn.wpgd.app.core.match.TributeKind
import cn.wpgd.app.core.rules.MatchOptions
import cn.wpgd.app.core.rules.RuleProfileLoader
import cn.wpgd.app.core.state.Seat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** GDN-082: the result explains A failures and the next tribute; spectators get neutral wording. */
class ResultExplanationTest {
    private val profileSettings = MatchSettings.of(TestProfiles.profiles.match)
    private val limited = MatchSettings.of(
        RuleProfileLoader.parse(
            File("../rules/profiles/M1-match-v1.json").readText().replace("\"passingA.maxFailures\": null", "\"passingA.maxFailures\": 3"),
            MatchOptions.registry,
        ).getOrThrow(),
    )

    private fun settle(order: List<Seat>, ns: Int, ew: Int, dealLevel: Int, nsFailures: Int = 0, settings: MatchSettings = profileSettings): DealResult =
        Settlement.settle(4, dealLevel, order, mapOf(Team.NORTH_SOUTH to ns, Team.EAST_WEST to ew), mapOf(Team.NORTH_SOUTH to nsFailures, Team.EAST_WEST to 0), settings)

    @Test fun anAFailureIsExplainedToEachTeamInItsOwnWords() {
        val r = settle(listOf(Seat.EAST, Seat.SOUTH, Seat.WEST), ns = 14, ew = 5, dealLevel = 14)
        val south = ResultExplanation.of(r, Viewer.Player(Seat.SOUTH), profileSettings.maxAFailures)
        assertEquals(ResultHeadline.OWN_TEAM_LOST_DEAL, south.headline)
        assertTrue(south.lines.contains(ResultLine.AFailed(TeamLabel.Own, 1, null)))
        val east = ResultExplanation.of(r, Viewer.Player(Seat.EAST), profileSettings.maxAFailures)
        assertEquals(ResultHeadline.OWN_TEAM_WON_DEAL, east.headline)
        assertTrue(east.lines.contains(ResultLine.AFailed(TeamLabel.Opponents, 1, null)))
        assertTrue(east.lines.any { it is ResultLine.LevelChange && it.team == TeamLabel.Own && it.from == 5 })
    }

    @Test fun reachingTheFailureLimitExplainsTheReset() {
        val r = settle(listOf(Seat.EAST, Seat.SOUTH, Seat.WEST), ns = 14, ew = 5, dealLevel = 14, nsFailures = 2, settings = limited)
        val lines = ResultExplanation.of(r, Viewer.Player(Seat.NORTH), limited.maxAFailures).lines
        assertTrue(lines.contains(ResultLine.AFailed(TeamLabel.Own, 3, 3)))
        assertTrue(lines.contains(ResultLine.AReset(TeamLabel.Own, 2)))
    }

    @Test fun theNextTributeStepIsNamedForEveryone() {
        // Double-up by East-West: double tribute, both losers give.
        val double = settle(listOf(Seat.EAST, Seat.WEST), ns = 3, ew = 3, dealLevel = 3)
        val south = ResultExplanation.of(double, Viewer.Player(Seat.SOUTH), null).lines
        val t = south.filterIsInstance<ResultLine.TributeNext>().single()
        assertEquals(TributeKind.DOUBLE, t.kind)
        assertEquals(TributeRole.YOU_GIVE, t.yourRole)
        assertTrue(south.contains(ResultLine.PlacesInferred))
        assertEquals(TributeRole.YOU_RECEIVE, ResultExplanation.of(double, Viewer.Player(Seat.WEST), null).lines.filterIsInstance<ResultLine.TributeNext>().single().yourRole)

        // South first, North third: single tribute from West (last) to South.
        val single = settle(listOf(Seat.SOUTH, Seat.EAST, Seat.NORTH), ns = 3, ew = 3, dealLevel = 3)
        fun role(s: Seat) = ResultExplanation.of(single, Viewer.Player(s), null).lines.filterIsInstance<ResultLine.TributeNext>().single().yourRole
        assertEquals(TributeRole.YOU_RECEIVE, role(Seat.SOUTH))
        assertEquals(TributeRole.YOU_GIVE, role(Seat.WEST))
        assertEquals(TributeRole.PARTNER_GIVES, role(Seat.EAST))
        assertEquals(TributeRole.PARTNER_RECEIVES, role(Seat.NORTH))
    }

    @Test fun whenFirstAndLastArePartnersTheOtherTeamHasNoTributeRole() {
        // South first, East second, West third: North (South's partner) is last and gives to South.
        val r = settle(listOf(Seat.SOUTH, Seat.EAST, Seat.WEST), ns = 3, ew = 3, dealLevel = 3)
        fun role(s: Seat) = ResultExplanation.of(r, Viewer.Player(s), null).lines.filterIsInstance<ResultLine.TributeNext>().single().yourRole
        assertEquals(TributeRole.YOU_RECEIVE, role(Seat.SOUTH))
        assertEquals(TributeRole.YOU_GIVE, role(Seat.NORTH))
        assertNull(role(Seat.EAST))
        assertNull(role(Seat.WEST))
    }

    @Test fun spectatorsGetNeutralWording() {
        val results = listOf(
            settle(listOf(Seat.EAST, Seat.SOUTH, Seat.WEST), ns = 14, ew = 5, dealLevel = 14),
            settle(listOf(Seat.EAST, Seat.WEST), ns = 3, ew = 3, dealLevel = 3),
            settle(listOf(Seat.SOUTH, Seat.NORTH), ns = 14, ew = 9, dealLevel = 14),
        )
        for (r in results) {
            val e = ResultExplanation.of(r, Viewer.Spectator, null)
            assertTrue(e.headline == ResultHeadline.TEAM_WON_DEAL || e.headline == ResultHeadline.TEAM_WON_MATCH)
            assertTrue(e.winner is TeamLabel.Named)
            val text = e.toString()
            assertTrue(text, "Own" !in text && "Opponents" !in text && "Relative" !in text && "YOU_" !in text && "PARTNER_" !in text)
            e.lines.filterIsInstance<ResultLine.TributeNext>().forEach { assertNull(it.yourRole) }
            assertTrue(e.lines.filterIsInstance<ResultLine.Placing>().all { it.seat is SeatLabel.Named })
        }
        val failed = ResultExplanation.of(results[0], Viewer.Spectator, null)
        assertTrue(failed.lines.contains(ResultLine.AFailed(TeamLabel.Named(Team.NORTH_SOUTH), 1, null)))
    }

    @Test fun passingAEndsTheMatchWithNoTributeLine() {
        val r = settle(listOf(Seat.SOUTH, Seat.NORTH), ns = 14, ew = 9, dealLevel = 14)
        val e = ResultExplanation.of(r, Viewer.Player(Seat.NORTH), null)
        assertEquals(ResultHeadline.OWN_TEAM_WON_MATCH, e.headline)
        assertTrue(e.lines.contains(ResultLine.APassed(TeamLabel.Own)))
        assertTrue(e.lines.none { it is ResultLine.TributeNext })
        assertEquals(ResultHeadline.OWN_TEAM_LOST_MATCH, ResultExplanation.of(r, Viewer.Player(Seat.EAST), null).headline)
    }
}
