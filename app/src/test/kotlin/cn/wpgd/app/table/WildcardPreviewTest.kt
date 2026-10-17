package cn.wpgd.app.table

import cn.wpgd.app.FakeSession
import cn.wpgd.app.TestProfiles
import cn.wpgd.app.card
import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.rules.CombinationType
import cn.wpgd.app.core.rules.Judgment
import cn.wpgd.app.core.rules.WildcardAssignment
import cn.wpgd.app.core.state.PublicPlay
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.playingView
import cn.wpgd.app.session.wire.PlayBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GDN-076: two valid interpretations are selectable, and the payload equals the preview. */
class WildcardPreviewTest {
    private val rules = TestProfiles.rules

    // Level 2: the heart 2s are wildcards. 0H2 + ♠5 6 7 8 reads as 4–8 or 5–9, straight or straight flush.
    private val selection = listOf("0H2", "0S5", "0S6", "0S7", "0S8")

    @Test fun everyReadingIsOfferedAndEachPayloadIsExactlyItsPreview() {
        val preview = PlayPreview.of(rules, selection.map(::card), 2, null, null) as PlayPreview.Options
        assertTrue(preview.needsChoice)
        assertNull(preview.payload)
        val readings = preview.options.map { it.type to it.declaration }.toSet()
        assertEquals(
            setOf(
                CombinationType.STRAIGHT to listOf(WildcardAssignment(4)),
                CombinationType.STRAIGHT to listOf(WildcardAssignment(9)),
                CombinationType.STRAIGHT_FLUSH to listOf(WildcardAssignment(4, Suit.SPADE)),
                CombinationType.STRAIGHT_FLUSH to listOf(WildcardAssignment(9, Suit.SPADE)),
            ),
            readings,
        )
        for (o in preview.options) {
            val p = o.payload
            assertEquals(selection, p.cardIds)
            assertEquals(o.declaration.map { it.rank to it.suit }, p.declaration!!.map { it.rank to it.suit })
            assertEquals(o.type, p.combinationType)
            // The host's own classifier judges the payload as exactly the previewed reading.
            val judged = rules.classifier(2).judge(p.cardIds.map(TableRules::card), p.declaration!!.map { WildcardAssignment(it.rank, it.suit) }, null) as Judgment.Readable
            assertTrue(judged.legal)
            assertEquals(setOf(o.type), judged.beating.map { it.type }.toSet())
            assertEquals(o.resolvedRanks, judged.beating.single().resolvedRanks)
            val chosen = PlayPreview.of(rules, selection.map(::card), 2, null, o.key) as PlayPreview.Options
            assertEquals(o, chosen.chosen)
            assertEquals(o.payload, chosen.payload)
        }
        assertEquals("distinct previews give distinct payloads", 4, preview.options.map { it.payload }.toSet().size)
    }

    @Test fun onlyReadingsThatBeatTheTableAreOffered() {
        val top = rules.topCombination(listOf(PublicPlay(Seat.WEST, listOf("0C4", "0C5", "0D6", "0C7", "0C8").map(::card), emptyList(), CombinationType.STRAIGHT)), 2)
        val preview = PlayPreview.of(rules, selection.map(::card), 2, top, null) as PlayPreview.Options
        // 4–8 does not beat 4–8; 5–9 does, and both straight flushes are bombs.
        assertEquals(3, preview.options.size)
        assertTrue(preview.options.none { it.type == CombinationType.STRAIGHT && it.declaration == listOf(WildcardAssignment(4)) })
    }

    @Test fun theScreenSendsTheChosenPreviewAndNothingElse() {
        val session = FakeSession(playingView(hand = selection + listOf("0D9", "1CK")))
        val vm = TableViewModel(session, PreferencesRepository(InMemoryKeyValueStore()), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        selection.forEach(vm::tap)
        val preview = vm.uiState.value.preview as PlayPreview.Options
        assertTrue(preview.needsChoice)
        assertTrue("no play until a reading is chosen", !vm.uiState.value.actions.play)
        assertNull(vm.play())

        val pick = preview.options.first { it.type == CombinationType.STRAIGHT && it.declaration == listOf(WildcardAssignment(9)) }
        vm.chooseReading(pick.key)
        assertEquals(pick, (vm.uiState.value.preview as PlayPreview.Options).chosen)
        vm.play()
        assertEquals(listOf<Any>(pick.payload), session.submitted)
        assertEquals(PlayBody(selection, pick.payload.declaration, CombinationType.STRAIGHT), session.submitted.single())

        // While the play is pending the selection is kept as it is (controls are disabled).
        vm.tap("0S8")
        assertEquals(pick.key, (vm.uiState.value.preview as PlayPreview.Options).chosenKey)
        // Nor can another reading be chosen: the preview stays the submitted payload.
        val other = preview.options.first { it.key != pick.key }
        vm.chooseReading(other.key)
        assertEquals(pick.key, (vm.state().preview as PlayPreview.Options).chosenKey)
        // Once the host has answered, changing the selection forgets the choice.
        session.accept()
        vm.tap("0S8")
        assertTrue((vm.uiState.value.preview as? PlayPreview.Options)?.chosenKey == null)
    }
}
