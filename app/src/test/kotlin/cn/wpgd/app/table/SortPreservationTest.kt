package cn.wpgd.app.table

import cn.wpgd.app.TestProfiles
import cn.wpgd.app.VirtualSolo
import cn.wpgd.app.card
import cn.wpgd.app.core.state.StandardDeck
import cn.wpgd.app.drive
import cn.wpgd.app.human
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** GDN-075, audit A24 (no manual arrangement): sorting keeps card ids and the selection; manual groups survive accepted unrelated plays. */
class SortPreservationTest {
    private fun hand(seed: Int) = StandardDeck.cards.shuffled(Random(seed)).take(27).map { card(it.id) }

    @Test fun everySortKeepsExactlyTheHandsCardIds() {
        repeat(200) { seed ->
            val hand = hand(seed)
            val level = 2 + seed % 13
            val manual = listOf(hand.take(3).map { it.id }, listOf(hand[5].id))
            for (sort in HandSort.entries) {
                val groups = HandArrangement.arrange(hand, level, sort, manual)
                val ids = groups.flatMap { g -> g.cards.map { it.id } }
                assertEquals(ids.size, ids.toSet().size)
                assertEquals(hand.map { it.id }.toSet(), ids.toSet())
                assertEquals(manual, groups.filter { it.kind == GroupKind.MANUAL }.map { g -> g.cards.map { it.id } })
                val rows = HandLayoutA.rows(groups)
                assertTrue(rows.size <= 2)
                assertEquals(groups, rows.flatten())
            }
        }
    }

    @Test fun aWildcardSitsAtTheBottomOfItsRankColumn() {
        val hand = listOf("0S7", "0H7", "1D7", "0C2").map(::card)
        val column = HandArrangement.arrange(hand, 7, HandSort.RANK).single { it.key == "r:7" }
        assertEquals(listOf("0S7", "1D7", "0H7"), column.cards.map { it.id })
        // Level cards (power 15) sort above the ace, jokers last.
        val order = HandArrangement.arrange(listOf("0SA", "0S7", "0BJ", "0D3").map(::card), 7, HandSort.RANK).map { it.key }
        assertEquals(listOf("r:3", "r:14", "r:7", "j"), order)
    }

    @Test fun manualGroupsAreMadeFromTheSelectionAndLoseOnlyPlayedCards() {
        val hand = hand(1)
        val groups = HandArrangement.group(listOf(listOf(hand[0].id, hand[1].id)), listOf(hand[1].id, hand[2].id), hand)
        assertEquals(listOf(listOf(hand[0].id), listOf(hand[1].id, hand[2].id)), groups)
        val afterPlay = HandArrangement.pruneGroups(groups, hand.drop(1).map { it.id }.toSet())
        assertEquals(listOf(listOf(hand[1].id, hand[2].id)), afterPlay)
    }

    @Test fun onTheTableSortingKeepsTheSelectionAndGroupsSurviveAnUnrelatedPlay() {
        val solo = VirtualSolo(seed = 41).start()
        val vm = solo.viewModel()
        assertTrue(solo.drive(solo.human(vm)) { vm.uiState.value.let { it.turn.key == TurnKey.YOU_LEAD && it.controlsEnabled } })
        val view = solo.table!!.view!!
        val input = TestProfiles.memberInputs.of(view)!!
        val single = input.choices.plays.first { it.cards.size == 1 }.cards.single().id
        val others = view.deal!!.hand.map { it.id }.filter { it != single }.take(3)

        // Group three cards, then select others and change the sort: ids and selection stay.
        others.forEach(vm::tap)
        vm.groupSelection()
        val chosen = view.deal!!.hand.map { it.id }.filter { it != single && it !in others }.take(2)
        chosen.forEach(vm::tap)
        for (sort in HandSort.entries) {
            vm.updatePreferences { it.copy(handSort = sort) }
            val ui = vm.uiState.value
            assertEquals(chosen.toSet(), ui.hand.selectedIds.toSet())
            assertEquals(chosen.toSet(), ui.hand.rows.flatten().flatMap { it.cards }.filter { it.selected }.map { it.id }.toSet())
            assertEquals(others, ui.hand.rows.flatten().first { it.kind == GroupKind.MANUAL }.cards.map { it.id })
        }

        // Play an unrelated single: the manual group survives the accepted play.
        vm.clearSelection()
        vm.tap(single)
        vm.play()
        assertTrue(solo.runUntil(10_000) { !vm.uiState.value.pending })
        assertEquals(null, vm.uiState.value.rejection)
        assertTrue(vm.uiState.value.hand.rows.flatten().flatMap { it.cards }.none { it.id == single })
        assertEquals(others, vm.uiState.value.hand.rows.flatten().first { it.kind == GroupKind.MANUAL }.cards.map { it.id })
    }
}
