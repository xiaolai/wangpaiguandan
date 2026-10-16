package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.SplitMix64
import cn.wpgd.app.core.state.StandardDeck
import cn.wpgd.app.core.state.shuffledWith
import cn.wpgd.app.session.wire.CommandBody
import cn.wpgd.app.session.wire.Receipt
import cn.wpgd.app.session.wire.Snapshot
import cn.wpgd.app.session.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * P1 for receipts: two worlds that differ only in EAST's and WEST's hidden cards give SOUTH and
 * NORTH byte-identical receipts and snapshots. The receipt digest covers the recipient's own
 * projection, never the full state; hashing full state would make these bytes differ.
 */
class ReceiptPrivacyTest {
    private val cutCard = "0S3"

    private class World(val bytes: Map<Seat, List<String>>, val receipts: Map<Seat, Int>)

    private fun deck(swap: Boolean): List<String> {
        val base = StandardDeck.cards.shuffledWith(SplitMix64(7)).map { it.id }.toMutableList()
        if (!swap) return base
        // Position i goes to seat i mod 4: 1 = EAST, 3 = WEST. Swap one EAST card with one WEST card.
        val p = base.indices.first { it % 4 == 1 && base[it] != cutCard }
        val q = base.indices.first { it % 4 == 3 && base[it] != cutCard && StandardDeck[base[it]]!!.rank != StandardDeck[base[p]]!!.rank }
        val tmp = base[p]; base[p] = base[q]; base[q] = tmp
        return base
    }

    private fun world(swap: Boolean, swapped: Set<String>): World {
        val t = Table(seed = 161, dealRandom = { ScriptedDeal(deck(swap), cutCard) })
        val players = t.fourPlayers()
        t.startDeal()
        repeat(40) {
            val seat = t.turn()
            val p = players.getValue(seat)
            val body: CommandBody = simpleBody(p.client.memberView!!, exclude = swapped)
            t.command(p, body)
            t.until(5_000) { players.values.all { it.client.revision == t.host.revision } }
        }
        val bytes = players.mapValues { (_, p) -> p.received.filter { it is Receipt || it is Snapshot }.map { WireCodec.encodeToString(it) } }
        return World(bytes, players.mapValues { (_, p) -> p.received.count { it is Receipt } })
    }

    @Test fun changingHiddenOpponentCardsLeavesEveryReceiptByteIdentical() {
        val a = deck(false)
        val b = deck(true)
        val swapped = a.indices.filter { a[it] != b[it] }.map { a[it] }.toSet()
        assertEquals(2, swapped.size)
        val worldA = world(false, swapped)
        val worldB = world(true, swapped)
        for (seat in listOf(Seat.SOUTH, Seat.NORTH)) {
            assertTrue(worldA.receipts.getValue(seat) > 0, "$seat must have issued commands")
            assertEquals(worldA.bytes.getValue(seat), worldB.bytes.getValue(seat), "$seat's receipts or snapshots depend on hidden cards")
        }
        // Sanity: the worlds really differ for the seats that hold the swapped cards.
        assertNotEquals(worldA.bytes.getValue(Seat.EAST), worldB.bytes.getValue(Seat.EAST))
        assertNotEquals(worldA.bytes.getValue(Seat.WEST), worldB.bytes.getValue(Seat.WEST))
    }
}
