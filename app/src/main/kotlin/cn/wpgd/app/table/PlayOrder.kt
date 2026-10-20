package cn.wpgd.app.table

import cn.wpgd.app.core.state.ViewCard

/**
 * Display order for the cards of a played combination: low rank first.
 *
 * The payload owns the order the cards were sent in, and that order stays untouched; this only
 * decides how the combination reads. Without it a straight the AI assembled as 4 3 7 5 6 is shown in
 * that order, which is unreadable next to the prompt "压过上家的顺子".
 */
object PlayOrder {
    fun byRank(cards: List<ViewCard>): List<ViewCard> = cards.sortedBy { it.rank }
}
