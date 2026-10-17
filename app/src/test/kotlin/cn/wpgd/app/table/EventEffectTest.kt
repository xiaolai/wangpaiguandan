package cn.wpgd.app.table

import cn.wpgd.app.VirtualSolo
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.drive
import cn.wpgd.app.human
import cn.wpgd.app.owner.ConnectionState
import cn.wpgd.app.playingView
import cn.wpgd.app.untilMyPlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** GDN-080, GDN-078, audit A26: cues are keyed to event ids; a reconnect, a resync or a new screen never replays one. */
class EventEffectTest {
    private fun collect(solo: VirtualSolo): MutableList<TableCue> {
        val cues = ArrayList<TableCue>()
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).launch { solo.owner.cues.collect { cues += it } }
        return cues
    }

    @Test fun reconnectAndResyncDoNotReplayCues() {
        val solo = VirtualSolo().start()
        val cues = collect(solo)
        val vm = solo.viewModel()
        val human = solo.human(vm)
        // Play a while so plays, passes and turns have been cued.
        assertTrue(solo.drive(human) { cues.count { it.kind == CueKind.PLAY || it.kind == CueKind.BOMB } >= 8 && vm.uiState.value.turn.youAct })
        assertTrue(solo.untilMyPlay(vm))
        val before = cues.toList()
        assertTrue(before.any { it.kind == CueKind.YOUR_TURN })
        assertEquals("ids are unique", before.size, before.map { it.id }.toSet().size)

        // Reconnect at the same revision: the snapshot is delivered again, no cue is.
        solo.owner.reconnect()
        assertTrue(solo.runUntil(10_000) { vm.uiState.value.connection == ConnectionState.CONNECTED })
        solo.run(3_000)
        assertEquals(before, cues)

        // A new screen subscribes to effects: nothing old arrives.
        val replayed = ArrayList<EffectOutput>()
        val vm2 = solo.viewModel()
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).launch { vm2.effects.collect { replayed += it } }
        solo.run(1_000)
        assertEquals(emptyList<EffectOutput>(), replayed)

        // New events after the reconnect get new ids only.
        assertTrue(solo.drive(human) { cues.size > before.size + 3 })
        val fresh = cues.drop(before.size)
        assertTrue(fresh.none { f -> before.any { it.id == f.id } })
        assertEquals(cues.size, cues.map { it.id }.toSet().size)
        assertTrue(replayed.isNotEmpty())
    }

    @Test fun theTrackerNeverCuesTheSameOrAnOlderRevisionTwice() {
        val t = EffectTracker()
        val lead = playingView(hand = listOf("0S5", "0S6"), turn = Seat.WEST, revision = 10)
        val east = playingView(hand = listOf("0S5", "0S6"), turn = Seat.SOUTH, revision = 12,
            trick = listOf(cn.wpgd.app.core.state.PublicPlay(Seat.WEST, listOf(cn.wpgd.app.card("0C9")), emptyList(), cn.wpgd.app.core.rules.CombinationType.SINGLE)))
        assertEquals("the first view is a baseline", emptyList<TableCue>(), t.observe(1, 10, lead))
        val cues = t.observe(1, 12, east)
        assertEquals(listOf(CueKind.PLAY, CueKind.YOUR_TURN), cues.map { it.kind })
        assertEquals(emptyList<TableCue>(), t.observe(1, 12, east))
        assertEquals("a resync to an older view", emptyList<TableCue>(), t.observe(1, 10, lead))
        assertEquals(emptyList<TableCue>(), t.observe(1, 12, east))
        assertEquals("a new epoch starts from a baseline", emptyList<TableCue>(), t.observe(2, 1, lead))
        assertEquals(cues.first().id, "e1:r12:PLAY:WEST:0")
    }

    /** Audit-fix #32: the pass that closes a trick commits with the trick's end, and is still cued. */
    @Test fun thePassThatClosesATrickIsCued() {
        val t = EffectTracker()
        val east = cn.wpgd.app.core.state.PublicPlay(Seat.EAST, listOf(cn.wpgd.app.card("0C9")), emptyList(), cn.wpgd.app.core.rules.CombinationType.SINGLE)
        val open = playingView(hand = listOf("0S5"), turn = Seat.SOUTH, trick = listOf(east), passed = listOf(Seat.NORTH, Seat.WEST), revision = 10)
        val closed = playingView(hand = listOf("0S5"), turn = Seat.EAST, revision = 11)
        t.observe(1, 10, open)
        val cues = t.observe(1, 11, closed)
        assertEquals(listOf(CueKind.PASS to Seat.SOUTH), cues.map { it.kind to it.seat })
    }

    /**
     * Audit #24: after skipped revisions the trick that closed may have been won by a later play, so the
     * previous view's top cannot be assumed to have won and no closing pass is guessed.
     */
    @Test fun noClosingPassIsGuessedAcrossSkippedRevisions() {
        val t = EffectTracker()
        val east = cn.wpgd.app.core.state.PublicPlay(Seat.EAST, listOf(cn.wpgd.app.card("0C9")), emptyList(), cn.wpgd.app.core.rules.CombinationType.SINGLE)
        val open = playingView(hand = listOf("0S5"), turn = Seat.SOUTH, trick = listOf(east), passed = listOf(Seat.NORTH, Seat.WEST), revision = 10)
        // Unseen: SOUTH beat EAST (11), then the others passed (12..14) and the trick closed; SOUTH leads.
        val closed = playingView(hand = listOf("0S5"), turn = Seat.SOUTH, revision = 14)
        t.observe(1, 10, open)
        val cues = t.observe(1, 14, closed)
        assertEquals(emptyList<Pair<CueKind, Seat?>>(), cues.filter { it.kind == CueKind.PASS }.map { it.kind to it.seat })
    }

    @Test fun aRejectionIsCuedOncePerCommand() {
        val t = EffectTracker()
        val first = t.rejected(1, 5, "abc-1")
        assertEquals(CueKind.COMMAND_REJECTED, first?.kind)
        assertEquals(null, t.rejected(1, 5, "abc-1"))
    }
    /** Round 3 #17: a rematch returns everyone to the lobby (deal 0): no deal starts; its first deal does. */
    @Test fun aRematchLobbyIsNoDealStartAndTheNextMatchsFirstDealIs() {
        val t = EffectTracker()
        val m1d1 = playingView(hand = listOf("0S5"), revision = 10).let { v -> v.copy(match = v.match.copy(matchNumber = 1, dealNumber = 1)) }
        val lobby = m1d1.copy(deal = null, match = m1d1.match.copy(matchNumber = 2, dealNumber = 0, phase = cn.wpgd.app.core.match.MatchPhase.AWAITING_DEAL))
        t.observe(1, 10, m1d1)
        assertTrue(t.observe(1, 11, lobby).none { it.kind == CueKind.DEAL_STARTED })

        // The same deal number in the next match is a new deal, even when the lobby view was skipped.
        val t2 = EffectTracker()
        val m2d1 = m1d1.copy(match = m1d1.match.copy(matchNumber = 2, dealNumber = 1))
        t2.observe(1, 10, m1d1)
        assertEquals(1, t2.observe(1, 20, m2d1).count { it.kind == CueKind.DEAL_STARTED })
    }
}
