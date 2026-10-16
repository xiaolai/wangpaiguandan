package cn.wpgd.app.table

import cn.wpgd.app.VirtualSolo
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.drive
import cn.wpgd.app.human
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GDN-079, section 8 P1 (UI half): the data given to the table UI holds no hidden card count and no
 * hidden card, so the 08b semantics tree, built only from [TableUiState], cannot leak one. A seat's
 * count appears only when the profile made it public (a threshold report or an answer).
 */
class HiddenCountSemanticsDataTest {
    @Test fun seatDataHasNoFieldThatCouldCarryAHandSize() {
        val fields = SeatUi::class.java.declaredFields.map { it.name }.filter { !it.startsWith("$") }.toSet()
        // WI-09 added who holds the seat (name, presence) and the away grace: none is a hand size.
        assertEquals(setOf("seat", "relation", "team", "acting", "lastAction", "finishPlace", "publicCount", "nickname", "presence", "graceSeconds"), fields)
        val table = TableUiState::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(table.none { it.contains("count", ignoreCase = true) || it.contains("remaining", ignoreCase = true) })
    }

    @Test fun throughAWholeDealTheUiStateHoldsNoHiddenCountAndNoHiddenCard() {
        val solo = VirtualSolo(seed = 19).start()
        val vm = solo.viewModel()
        val human = solo.human(vm)
        var checked = 0
        var hiddenNonzero = 0
        var publicSeen = 0
        solo.drive(human) {
            val ui = vm.uiState.value
            val view = solo.table?.view
            if (view != null && ui.phase == MatchPhase.PLAYING && ui.dealNumber == view.match.dealNumber) {
                var hands: Map<Seat, Set<String>>? = null
                solo.owner.withHost { hands = it.state.deal?.hands }
                solo.run(0)
                val h = checkNotNull(hands)
                val text = ui.toString()
                for (s in ui.seats.filter { it.relation != SeatRelation.SELF }) {
                    val hidden = h.getValue(s.seat)
                    // No hidden card of another seat appears anywhere in the UI data.
                    for (id in hidden) assertTrue("hidden card $id of ${s.seat} in UI state", !Regex("\\b$id\\b").containsMatchIn(text))
                    val public = (view.match.reports.filter { it.seat == s.seat }.map { it.count } + view.match.answers.filter { it.seat == s.seat }.map { it.count }).lastOrNull()
                    if (public == null) assertNull("${s.seat} count shown without a public report", s.publicCount) else { assertEquals(public, s.publicCount); publicSeen++ }
                    if (hidden.isNotEmpty()) hiddenNonzero++
                }
                checked++
            }
            view?.match?.lastResult?.dealNumber == 1
        }
        assertTrue("checked $checked states", checked > 30)
        assertTrue(hiddenNonzero > 30)
        // The match profile reports counts at its threshold; the check above saw them as public.
        assertTrue("a public report was seen and shown", publicSeen > 0)
    }
}
