package cn.wpgd.app.table

import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.state.ViewCard
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GDN-076: a played combination is displayed in rank order, whatever order the payload carried.
 * Found by the 2026-10-20 screenshot sweep: the table showed 顺子 4 3 7 5 6.
 */
class PlayOrderTest {
    private fun card(id: String, rank: Int, suit: Suit = Suit.SPADE) = ViewCard(id, suit, rank)

    @Test fun aStraightReadsLowToHigh() {
        val payload = listOf(card("a", 4), card("b", 3), card("c", 7), card("d", 5), card("e", 6))
        assertEquals(listOf(3, 4, 5, 6, 7), PlayOrder.byRank(payload).map { it.rank })
    }

    @Test fun aTripleWithPairGroupsItsRanks() {
        val payload = listOf(card("a", 4), card("b", 3), card("c", 4), card("d", 3), card("e", 4))
        assertEquals(listOf(3, 3, 4, 4, 4), PlayOrder.byRank(payload).map { it.rank })
    }

    @Test fun thePayloadOrderIsNotModified() {
        val payload = listOf(card("a", 9), card("b", 2), card("c", 5))
        PlayOrder.byRank(payload)
        assertEquals(listOf(9, 2, 5), payload.map { it.rank })
    }

    @Test fun equalRanksKeepTheirRelativeOrder() {
        val payload = listOf(card("spade", 7, Suit.SPADE), card("heart", 7, Suit.HEART))
        assertEquals(listOf("spade", "heart"), PlayOrder.byRank(payload).map { it.id })
    }
}
