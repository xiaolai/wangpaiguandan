package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.transport.LinkFaults
import cn.wpgd.app.session.wire.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GDN-028, audit A17: delayed and reordered writes never regress a member's revision. */
class OrderedDeliveryTest {
    @Test fun heavilyReorderedSnapshotsNeverRegressTheRevision() {
        val reorder = LinkFaults(latencyMs = 2, jitterMs = 60, reorderRate = 0.5, reorderDelayMs = 300, duplicateRate = 0.1)
        val t = Table(seed = 21)
        val players = Seat.entries.associateWith { t.player(it, reorder) }
        val seen = players.mapValues { ArrayList<Long>() }
        for ((seat, p) in players) {
            val prev = p.client.onUpdate
            p.client.onUpdate = { prev?.invoke(); seen.getValue(seat) += p.client.revision }
        }
        val bots = players.values.map { Bot(t, it) }
        t.startDeal()
        bots.forEach { it.poke() }
        t.until(10 * 60_000L) { t.host.revision >= 60 }
        bots.forEach { it.enabled = false }
        t.until(30_000) { players.values.all { it.client.revision == t.host.revision } }

        for ((seat, list) in seen) assertEquals(list.sorted(), list, "$seat revision went backwards")
        val outOfOrder = players.values.sumOf { p ->
            val revs = p.received.filterIsInstance<Snapshot>().map { it.revision }
            revs.zipWithNext().count { (a, b) -> b < a }
        }
        assertTrue(outOfOrder > 0, "the faults must actually deliver snapshots out of order")
        assertTrue(players.values.sumOf { it.client.metrics.olderSnapshotsIgnored } > 0, "older snapshots must have been ignored")
        for ((seat, p) in players) assertEquals(t.memberBytes(seat), p.client.viewJson)
    }

    @Test fun anOlderSnapshotInjectedAfterANewerOneIsIgnored() {
        val t = Table(seed = 22)
        val players = t.fourPlayers()
        t.startDeal()
        val south = players.getValue(Seat.SOUTH).client
        val current = south.revision
        val currentView = south.viewJson
        val old = players.getValue(Seat.SOUTH).received.filterIsInstance<Snapshot>().first { it.revision < current }
        south.onMessage(old)
        assertEquals(current, south.revision)
        assertEquals(currentView, south.viewJson)
        assertEquals(1, south.metrics.olderSnapshotsIgnored)
    }
}
