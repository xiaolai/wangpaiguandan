package cn.wpgd.app.core.ai

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchProjectionCodec
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.SplitMix64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * GDN-062, audit A09: hints come from the same generator and policy as the AI; the room decides
 * whether they are offered (PROVISIONAL G4 default: friendly yes, competitive no); a spectator never
 * gets one.
 */
class HintPolicyTest {
    private val hints = HintService()
    private val a06 = playing(2, mapOf(S to listOf("0H2", "0S7"), E to listOf("0S3"), N to listOf("0S4"), W to listOf("0S5")), turn = S, trick = listOf(tp(E, "0S6", "0C6")))

    @Test fun competitiveRoomsGiveNoHints() {
        val input = hostInput(a06, S)
        assertEquals(HintResult.DisabledByRoom, hints.hint(HintAvailability.DISABLED_BY_ROOM, HintRequester.Player(S), input))
    }

    @Test fun friendlyRoomsGiveTheAiAnswerFromTheSameGeneratorAndPolicy() {
        val fromView = AiProfiles.memberInputs.of(AiProfiles.projector.memberView(a06, S))!!
        val offered = assertIs<HintResult.Offered>(hints.hint(HintAvailability.OFFERED, HintRequester.Player(S), fromView))
        assertEquals(BaselineHeuristicPolicy.id, offered.policy)
        assertEquals(setOf("0H2", "0S7"), offered.action.playedIds(), "the A06 pair of sevens")
        assertEquals(decide(a06, S).action, offered.action, "hint equals the AI's decision")
    }

    @Test fun spectatorsNeverGetHints() {
        // Even holding a followed player's private view, in a friendly room.
        val followed = AiProfiles.memberInputs.of(AiProfiles.projector.memberView(a06, S))
        for (availability in HintAvailability.entries) {
            assertEquals(HintResult.NotAPlayer, hints.hint(availability, HintRequester.Spectator, followed))
            assertEquals(HintResult.NotAPlayer, hints.hint(availability, HintRequester.Spectator, null))
        }
    }

    @Test fun nothingToDecideWhenItIsNotTheSeatsTurn() {
        val view = AiProfiles.projector.memberView(a06, N)
        assertEquals(null, AiProfiles.memberInputs.of(view))
        assertEquals(HintResult.NothingToDecide, hints.hint(HintAvailability.OFFERED, HintRequester.Player(N), null))
    }

    /** A hint changes nothing anyone else sees: the public view and other seats' views are byte-identical. */
    @Test fun aHintIsNotPartOfAnyProjection() {
        val before = Seat.entries.map { MatchProjectionCodec.encode(AiProfiles.projector.memberView(a06, it)).decodeToString() } +
            MatchProjectionCodec.encode(AiProfiles.projector.spectatorView(a06)).decodeToString()
        hints.hint(HintAvailability.OFFERED, HintRequester.Player(S), hostInput(a06, S))
        val after = Seat.entries.map { MatchProjectionCodec.encode(AiProfiles.projector.memberView(a06, it)).decodeToString() } +
            MatchProjectionCodec.encode(AiProfiles.projector.spectatorView(a06)).decodeToString()
        assertEquals(before, after)
        assertTrue(after.none { "hint" in it.lowercase() }, "no projection field carries a hint")
    }

    /**
     * The member-side choices (what a hint is computed from: the member's own view plus the profiles)
     * equal the host's choices for an AI seat, in every phase, over seeded matches.
     */
    @Test fun memberChoicesEqualHostChoices() {
        val reducer = AiProfiles.reducer
        var compared = 0
        val phases = HashSet<MatchPhase>()
        for (seed in 1L..25L) {
            var state = reducer.newMatch()
            val choosers = Seat.entries.associateWith { aiChooser(seed) }
            val stats = RunStats()
            var guard = 0
            while (state.phase != MatchPhase.OVER && state.dealNumber <= 2 && guard++ < 2_000) {
                for (seat in Seat.entries) {
                    val host = hostInput(state, seat).takeIf { it.hasChoice }
                    val member = AiProfiles.memberInputs.of(AiProfiles.projector.memberView(state, seat))?.takeIf { it.hasChoice }
                    assertEquals(host?.choices?.canonical(), member?.choices?.canonical(), "seed $seed r${state.revision} $seat ${state.phase}")
                    if (host != null) { compared++; phases += state.phase }
                }
                state = step(reducer, state, { SplitMix64(seed * 13 + it) }, choosers, stats)
            }
        }
        assertTrue(compared >= 500 && phases.containsAll(listOf(MatchPhase.PLAYING, MatchPhase.TRIBUTE, MatchPhase.RETURN)), "compared $compared in $phases")
    }
}
