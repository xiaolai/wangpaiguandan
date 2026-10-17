package cn.wpgd.app.table

import cn.wpgd.app.FakeSession
import cn.wpgd.app.TestProfiles
import cn.wpgd.app.VirtualSolo
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.playingView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.owner.ConnectionState
import cn.wpgd.app.owner.RejectionKey
import cn.wpgd.app.owner.RejectionKeys
import cn.wpgd.app.owner.RoomCondition
import cn.wpgd.app.owner.SubmitRefusal
import cn.wpgd.app.owner.SubmitResult
import cn.wpgd.app.session.wire.PlayBody
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.untilMyPlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** GDN-050 (pending feedback), GDN-088 (stale-state controls), audit A20 (rejection reaches the issuer). */
class TableViewModelTest {
    /** Selects one legal play of the member's own hand through the screen; returns its card ids. */
    private fun selectLegalPlay(solo: VirtualSolo, vm: TableViewModel): List<String> {
        val input = TestProfiles.memberInputs.of(solo.table!!.view!!)!!
        val play = input.choices.plays.maxBy { it.cards.size }
        vm.clearSelection()
        play.cards.forEach { vm.tap(it.id) }
        val preview = vm.uiState.value.preview as PlayPreview.Options
        if (preview.needsChoice) vm.chooseReading(preview.options.first { it.type == play.combination.type && it.declaration == play.declaration.orEmpty() }.key)
        assertTrue(vm.uiState.value.actions.play)
        return play.cards.map { it.id }
    }

    @Test fun aPendingCommandKeepsTheSelectionUntilTheReceipt() {
        val solo = VirtualSolo().start()
        val vm = solo.viewModel()
        assertTrue(solo.untilMyPlay(vm))
        val ids = selectLegalPlay(solo, vm)

        assertTrue(vm.play() is SubmitResult.Sent)
        val pending = vm.uiState.value
        assertTrue(pending.pending)
        assertFalse(pending.controlsEnabled)
        assertEquals(ids.toSet(), pending.hand.selectedIds.toSet())
        assertTrue(pending.hand.rows.flatten().flatMap { it.cards }.filter { it.id in ids }.all { it.selected })

        assertTrue(solo.runUntil(10_000) { !vm.uiState.value.pending })
        val after = vm.uiState.value
        assertNull(after.rejection)
        assertTrue("played cards left the hand and the selection", after.hand.selectedIds.isEmpty())
        assertTrue(after.hand.rows.flatten().flatMap { it.cards }.none { it.id in ids })
    }

    @Test fun aRuleRejectionExplainsWhyAndKeepsTheCardsSelected() {
        val solo = VirtualSolo().start()
        val vm = solo.viewModel()
        assertTrue(solo.untilMyPlay(vm))
        // Two cards that form no combination: the screen will not offer to play them...
        val deal = solo.table!!.view!!.deal!!
        val natural = deal.hand.filter { !HandArrangement.isWildcard(it, deal.table.level) && it.rank <= 14 }
        val a = natural.first()
        val b = natural.first { it.rank != a.rank }
        vm.tap(a.id); vm.tap(b.id)
        assertEquals(PlayPreview.NotPlayable(PreviewProblem.NOT_A_COMBINATION), vm.uiState.value.preview)
        assertFalse(vm.uiState.value.actions.play)
        assertEquals(null, vm.play())
        // ...so send them past the screen, as a modified or racing client would: the host refuses.
        assertTrue(solo.owner.submit(PlayBody(listOf(a.id, b.id), emptyList(), null)) is SubmitResult.Sent)
        assertTrue(solo.runUntil(10_000) { !vm.uiState.value.pending })

        val ui = vm.uiState.value
        assertEquals(RejectionKey.NOT_A_COMBINATION, ui.rejection)
        assertEquals(setOf(a.id, b.id), ui.hand.selectedIds.toSet())
        assertTrue(ui.controlsEnabled)
        vm.dismissRejection()
        assertNull(vm.uiState.value.rejection)
        assertEquals(setOf(a.id, b.id), vm.uiState.value.hand.selectedIds.toSet())
    }

    @Test fun aPausedRoomRefusesAndTheRefusalIsExplainedWithTheCardsKept() {
        val solo = VirtualSolo().start()
        val vm = solo.viewModel()
        assertTrue(solo.untilMyPlay(vm))
        val ids = selectLegalPlay(solo, vm)
        assertTrue(vm.play() is SubmitResult.Sent)
        // The host pauses after the command left this screen and before it arrived.
        solo.owner.pause()
        assertTrue(solo.runUntil(10_000) { !vm.uiState.value.pending })
        val ui = vm.uiState.value
        assertEquals(RejectionKey.ROOM_PAUSED, ui.rejection)
        assertEquals(RoomCondition.PAUSED, ui.room)
        assertEquals(ids.toSet(), ui.hand.selectedIds.toSet())
        assertFalse("controls stay disabled while the room is paused", ui.controlsEnabled)
        assertEquals(ActionsUi.NONE, ui.actions)
        assertNull(vm.play())

        solo.owner.resume()
        solo.run(100)
        assertTrue(vm.uiState.value.controlsEnabled)
        assertTrue(vm.uiState.value.actions.play)
    }

    @Test fun aDoubleTapSendsExactlyOneCommand() {
        val solo = VirtualSolo().start()
        val vm = solo.viewModel()
        assertTrue(solo.untilMyPlay(vm))
        val revision = solo.table!!.revision
        selectLegalPlay(solo, vm)
        val payload = (vm.uiState.value.preview as PlayPreview.Options).payload!!

        assertTrue(vm.play() is SubmitResult.Sent)
        assertNull("the second tap finds the button disabled", vm.play())
        assertEquals(SubmitResult.Refused(SubmitRefusal.ALREADY_PENDING), solo.owner.submit(payload))
        assertTrue(solo.runUntil(10_000) { !vm.uiState.value.pending })
        solo.run(2_000)

        assertEquals(1L, solo.owner.metrics.commandsSent.get())
        assertNull(vm.uiState.value.rejection)
        assertTrue(solo.table!!.revision > revision)
    }

    @Test fun staleOrLostConnectionDisablesEveryControlUntilRejoined() {
        val solo = VirtualSolo().start()
        val vm = solo.viewModel()
        assertTrue(solo.untilMyPlay(vm))
        selectLegalPlay(solo, vm)
        assertTrue(vm.uiState.value.controlsEnabled)

        solo.owner.blackholeLink(true)
        assertTrue("liveness limit (5 s) detects the dead link", solo.runUntil(8_000) { vm.uiState.value.connection != ConnectionState.CONNECTED })
        val ui = vm.uiState.value
        assertFalse(ui.controlsEnabled)
        assertEquals(ActionsUi.NONE, ui.actions)
        assertNull(vm.play())
        assertNull(vm.pass())
        assertEquals(SubmitResult.Refused(SubmitRefusal.NOT_CONNECTED), solo.owner.submit(PlayBody(emptyList(), emptyList(), null)))

        solo.owner.blackholeLink(false)
        solo.owner.reconnect()
        assertTrue(solo.runUntil(10_000) { vm.uiState.value.connection == ConnectionState.CONNECTED })
        assertTrue(solo.runUntil(10_000) { vm.uiState.value.controlsEnabled })
        assertEquals(MatchPhase.PLAYING, vm.uiState.value.phase)
    }

    @Test fun aCommandLostWithItsLinkIsReportedAsUnknownNotLeftPending() {
        val solo = VirtualSolo().start()
        val vm = solo.viewModel()
        assertTrue(solo.untilMyPlay(vm))
        val ids = selectLegalPlay(solo, vm)
        val revision = solo.table!!.revision
        assertTrue(vm.play() is SubmitResult.Sent)
        // The radio dies before the command arrives: no receipt and no rejection will ever come.
        solo.owner.blackholeLink(true)
        assertTrue(solo.runUntil(10_000) { !vm.uiState.value.pending })
        val ui = vm.uiState.value
        assertEquals(RejectionKey.OUTCOME_UNKNOWN, ui.rejection)
        assertEquals(ids.toSet(), ui.hand.selectedIds.toSet())
        assertFalse(ui.controlsEnabled)

        solo.owner.blackholeLink(false)
        solo.owner.reconnect()
        assertTrue(solo.runUntil(10_000) { vm.uiState.value.controlsEnabled })
        // The table shows the truth: the command never reached the host.
        assertEquals(revision, solo.table!!.revision)
        assertTrue(vm.uiState.value.turn.youAct)
    }

    @Test fun everyCoreRuleRejectionHasItsOwnMessageKey() {
        // The rule names the host sends are the simple names of the core rejection types.
        val core = File("../core/src/main/kotlin/cn/wpgd/app/core")
        val deal = Regex("""(?:data class|data object) (\w+)""").findAll(File(core, "state/Command.kt").readText().substringAfter("sealed interface Rejection {").substringBefore("sealed interface ReduceResult"))
            .map { "Deal." + it.groupValues[1] }.toSet()
        val match = Regex("""(?:data class|data object) (\w+)""").findAll(File(core, "match/MatchCommand.kt").readText().substringAfter("sealed interface MatchRejection {").substringBefore("sealed interface MatchReduceResult"))
            .map { it.groupValues[1] }.filter { it != "Deal" }.toSet()
        assertTrue(deal.size >= 10 && match.size >= 15)
        // Setup-only rejections (card moves and BeginPlay are sent by the match layer, never by a player) have no message.
        assertEquals("rule rejections without a message key", emptySet<String>(), (deal + match - "Deal.SelfMove" - "Deal.EmptyHand") - RejectionKeys.ruleNames)
        assertEquals(RejectionKey.DOES_NOT_BEAT, RejectionKeys.of(RejectionCode.RULE, "Deal.DoesNotBeatTrick"))
        assertEquals(RejectionKey.TABLE_MOVED_ON, RejectionKeys.of(RejectionCode.STALE_REVISION, "now 12"))
        assertEquals(RejectionKey.REFUSED_BY_HOST, RejectionKeys.of(RejectionCode.RULE, "SomethingNew"))
    }

    @Test fun aCardThatLeavesTheHandAndComesBackIsNotSelectedAgain() {
        val session = FakeSession(playingView(hand = listOf("0S3", "0S4", "1CK")))
        val vm = TableViewModel(session, PreferencesRepository(InMemoryKeyValueStore()), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        vm.tap("1CK")
        assertEquals(listOf("1CK"), vm.state().hand.selectedIds)
        // The card leaves the hand (given as tribute), then comes back in the same deal (returned).
        session.update { it.copy(view = playingView(hand = listOf("0S3", "0S4"))) }
        assertTrue(vm.uiState.value.hand.selectedIds.isEmpty())
        session.update { it.copy(view = playingView(hand = listOf("0S3", "0S4", "1CK"))) }
        assertTrue("a card came back selected without a touch", vm.state().hand.selectedIds.isEmpty())
        assertTrue(vm.uiState.value.hand.selectedIds.isEmpty())
    }
}
