package cn.wpgd.app.table

import cn.wpgd.app.FakeSession
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.playingView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** GDN-074, audit A24 (tap-only selection): tap and drag select a six-card sequence without touching neighbouring cards. */
class DragSelectionTest {
    private val hand = listOf("0S3", "0S4", "0D5", "0C6", "1S7", "0H8", "0S9", "1DJ", "0CQ")
    private val all = hand.toSet()

    @Test fun aDragOverSixCardsSelectsExactlyThoseSix() {
        val path = listOf("0S4", "0D5", "0C6", "1S7", "0H8", "0S9")
        var s = HandSelection().dragStart(path.first(), all)
        // The finger wobbles back over cards already passed: each changes once only.
        for (id in path.drop(1) + listOf("0C6", "0D5", "0S9")) s = s.dragOver(id, all)
        s = s.dragEnd()
        assertEquals(path.toSet(), s.ids)
        assertTrue("neighbours untouched", "0S3" !in s.ids && "1DJ" !in s.ids)
        assertEquals(null, s.drag)
    }

    @Test fun aDragStartingOnASelectedCardDeselects() {
        val s = HandSelection(setOf("0S4", "0D5", "0C6")).dragStart("0D5", all).dragOver("0C6", all).dragOver("1S7", all).dragEnd()
        assertEquals(setOf("0S4"), s.ids)
    }

    @Test fun tapTogglesAndIgnoresCardsNotInTheHand() {
        val s = HandSelection().tap("0S3", all).tap("0S4", all).tap("0S3", all).tap("1BJ", all)
        assertEquals(setOf("0S4"), s.ids)
        assertEquals(s, s.dragOver("0S9", all))
    }

    @Test fun theScreenSelectsTheSameWayAndALongPressTakesTheColumn() {
        val vm = TableViewModel(FakeSession(playingView(hand = hand + listOf("1S4"))), PreferencesRepository(InMemoryKeyValueStore()), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        vm.dragStart("0S4")
        listOf("0D5", "0C6", "1S7", "0H8", "0S9").forEach(vm::dragOver)
        vm.dragEnd()
        assertEquals(setOf("0S4", "0D5", "0C6", "1S7", "0H8", "0S9"), vm.uiState.value.hand.selectedIds.toSet())
        vm.clearSelection()
        vm.selectColumn("1S4")
        assertEquals(setOf("0S4", "1S4"), vm.uiState.value.hand.selectedIds.toSet())
    }

    /** Audit-fix #39: selection intents are ignored while controls are disabled; a drag still ends. */
    @Test fun selectionIsKeptAsItIsWhileControlsAreDisabled() {
        val session = FakeSession(playingView(hand = hand))
        val vm = TableViewModel(session, PreferencesRepository(InMemoryKeyValueStore()), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        vm.tap("0S3")
        vm.dragStart("0S4")
        session.update { it.copy(connection = cn.wpgd.app.owner.ConnectionState.STALE) }
        vm.tap("0S3")
        vm.dragOver("0D5")
        vm.selectColumn("0C6")
        vm.clearSelection()
        assertEquals(setOf("0S3", "0S4"), vm.state().hand.selectedIds.toSet())
        vm.dragEnd()
        session.update { it.copy(connection = cn.wpgd.app.owner.ConnectionState.CONNECTED) }
        vm.dragOver("0D5")
        assertEquals("the drag ended while disabled", setOf("0S3", "0S4"), vm.state().hand.selectedIds.toSet())
    }

    /** Audit-fix #39 (round 2): Group and Clear are disabled, and grouping is ignored, while controls are disabled. */
    @Test fun groupingNeitherClearsTheSelectionNorIsOfferedWhileControlsAreDisabled() {
        val session = FakeSession(playingView(hand = hand))
        val vm = TableViewModel(session, PreferencesRepository(InMemoryKeyValueStore()), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        vm.tap("0S3")
        vm.tap("0S4")
        assertTrue(vm.state().selectionToolsEnabled)
        session.update { it.copy(connection = cn.wpgd.app.owner.ConnectionState.STALE) }
        assertEquals("Group and Clear are offered while controls are disabled", false, vm.state().selectionToolsEnabled)
        vm.groupSelection()
        assertEquals("grouping cleared the selection while controls were disabled", setOf("0S3", "0S4"), vm.state().hand.selectedIds.toSet())
        assertTrue("grouping applied while controls were disabled", vm.state().hand.rows.none { r -> r.any { it.kind == GroupKind.MANUAL } })
        session.update { it.copy(connection = cn.wpgd.app.owner.ConnectionState.CONNECTED) }
        vm.groupSelection()
        assertEquals(emptyList<String>(), vm.state().hand.selectedIds)
        assertTrue(vm.state().hand.rows.any { r -> r.any { it.kind == GroupKind.MANUAL } })
    }

    /** Audit-fix #43: a drag or tap never selects a card the current step makes ineligible. */
    @Test fun ineligibleCardsAreNeverSelected() {
        val tribute = cn.wpgd.app.core.match.TributeView(
            cn.wpgd.app.core.match.TributeKind.SINGLE, listOf(cn.wpgd.app.core.state.Seat.SOUTH), listOf(cn.wpgd.app.core.state.Seat.EAST),
            emptyList(), emptyList(), emptyList(), null, emptyList(),
        )
        val session = FakeSession(playingView(hand = listOf("0S3", "0S4", "0SA"), phase = cn.wpgd.app.core.match.MatchPhase.TRIBUTE, tribute = tribute))
        val vm = TableViewModel(session, PreferencesRepository(InMemoryKeyValueStore()), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val eligible = vm.state().hand.rows.flatten().flatMap { it.cards }.filter { it.eligible }.map { it.id }
        assertEquals(listOf("0SA"), eligible)
        vm.dragStart("0S3")
        vm.dragOver("0S4")
        vm.dragEnd()
        vm.tap("0S4")
        assertEquals(emptyList<String>(), vm.state().hand.selectedIds)
        vm.dragStart("0SA")
        vm.dragOver("0S3")
        vm.dragEnd()
        assertEquals(listOf("0SA"), vm.state().hand.selectedIds)
        assertTrue(vm.state().actions.giveTribute)
    }
}
