package cn.wpgd.app.core.match

import cn.wpgd.app.core.state.SplitMix64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * GDN-017 (WI-09): a rematch starts a new match under the same profiles at the next revision, so a
 * room keeps its seats, members and revision order. Only a finished match can be restarted.
 */
class RematchTest {
    private val r = m1Reducer()

    private fun finished(seed: Long = 11): MatchState = playMatch(r, seed).also { assertEquals(MatchPhase.OVER, it.phase) }

    @Test fun aFinishedMatchRestartsAtTheNextRevision() {
        val over = finished()
        val step = r.reduce(over, MatchCommand.Rematch).accepted()
        val s = step.state
        assertEquals(over.revision + 1, s.revision, "revision keeps increasing")
        assertEquals(MatchPhase.AWAITING_DEAL, s.phase)
        assertEquals(0, s.dealNumber)
        assertEquals(MatchState.FIRST_LEVEL, s.dealLevel)
        assertEquals(Team.entries.associateWith { MatchState.FIRST_LEVEL }, s.levels)
        assertEquals(Team.entries.associateWith { 0 }, s.aFailures)
        assertNull(s.winner)
        assertNull(s.previous)
        assertEquals(over.baseProfileHash, s.baseProfileHash)
        assertEquals(over.matchProfileHash, s.matchProfileHash)
        val e = assertIs<MatchEvent.MatchRestarted>(step.events.single())
        assertEquals(s.revision, e.revision)
    }

    @Test fun theRestartedMatchIsPlayable() {
        val s = r.reduce(finished(), MatchCommand.Rematch).accepted().state
        val dealt = r.reduce(s, MatchCommand.DealNext(SplitMix64(5))).accepted().state
        assertEquals(1, dealt.dealNumber)
        assertEquals(dealt.deal?.hands?.values?.sumOf { it.size }, 108, "a full deck is dealt again")
    }

    @Test fun onlyAFinishedMatchCanBeRestarted() {
        val fresh = r.newMatch()
        assertEquals(MatchPhase.AWAITING_DEAL, r.reduce(fresh, MatchCommand.Rematch).rejectedWith<MatchRejection.WrongPhase>().phase)
        val dealt = r.reduce(fresh, MatchCommand.DealNext(SplitMix64(1))).accepted().state
        r.reduce(dealt, MatchCommand.Rematch).rejectedWith<MatchRejection.WrongPhase>()
    }

    @Test fun theEventsAloneReproduceTheRestartAndTheCodecRoundTrips() {
        val initial = r.newMatch()
        val log = ArrayList<MatchEvent>()
        val over = playMatch(r, 12) { log += it.events }
        val restart = r.reduce(over, MatchCommand.Rematch).accepted()
        log += restart.events
        assertEquals(restart.state, MatchEvents.replay(initial, log))
        val encoded = MatchEventCodec.encode(restart.events)
        assertEquals(restart.events, MatchEventCodec.decode(encoded))
        assertEquals("match_restarted", encoded.single().toString().substringAfter("\"t\":\"").substringBefore('"'))
    }

    @Test fun replayRefusesARestartOfAMatchThatIsNotOver() {
        val bad = MatchEvent.MatchRestarted(1)
        kotlin.test.assertFailsWith<IllegalArgumentException> { MatchEvents.apply(r.newMatch(), bad) }
    }

    /** Audit finding 17: every match of a room has its own number, so deal numbers restarting at 1 stay distinguishable. */
    @Test fun eachRematchHasTheNextMatchNumber() {
        assertEquals(1, r.newMatch().matchNumber)
        val second = r.reduce(finished(), MatchCommand.Rematch).accepted().state
        assertEquals(2, second.matchNumber)
        val third = r.reduce(playMatch(r, 13, from = second), MatchCommand.Rematch).accepted().state
        assertEquals(3, third.matchNumber)
        assertEquals(5, r.newMatch(matchNumber = 5).matchNumber)
        kotlin.test.assertFailsWith<IllegalArgumentException> { r.newMatch(matchNumber = 0) }
    }

    /** The number reaches every member and spectator, and survives the state codec; it is part of the host's state hash. */
    @Test fun theMatchNumberIsInEveryViewAndInTheSavedState() {
        val second = r.reduce(finished(), MatchCommand.Rematch).accepted().state
        val dealt = r.reduce(second, MatchCommand.DealNext(SplitMix64(5))).accepted().state
        val projector = MatchProjector(p1, MatchProfiles.m1)
        for (seat in cn.wpgd.app.core.state.Seat.entries) assertEquals(2, projector.memberView(dealt, seat).match.matchNumber)
        assertEquals(2, projector.spectatorView(dealt).match.matchNumber)
        assertEquals(dealt, MatchStateCodec.decode(MatchStateCodec.encode(dealt)))
        assertEquals(2, MatchStateCodec.encode(dealt)["matchNumber"].toString().toInt())
        val renumbered = MatchStateCodec.decode(MatchStateCodec.encode(dealt).let { kotlinx.serialization.json.JsonObject(it + ("matchNumber" to kotlinx.serialization.json.JsonPrimitive(3))) })
        kotlin.test.assertNotEquals(MatchStateCodec.hash(dealt), MatchStateCodec.hash(renumbered))
    }

    /** Pre-release reset (state format 2): a format-1 state, which had no match number, is refused rather than guessed. */
    @Test fun aStateWithoutAMatchNumberIsRefused() {
        val encoded = MatchStateCodec.encode(r.newMatch())
        val v1 = kotlinx.serialization.json.JsonObject(encoded - "matchNumber" + ("format" to kotlinx.serialization.json.JsonPrimitive(1)))
        kotlin.test.assertFailsWith<IllegalArgumentException> { MatchStateCodec.decode(v1) }
    }
}
