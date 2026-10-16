package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.transport.LinkFaults
import cn.wpgd.app.session.wire.PassBody
import cn.wpgd.app.session.wire.Receipt
import cn.wpgd.app.session.wire.RejectionReceipt
import cn.wpgd.app.session.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** GDN-028: a retried command applies once and returns the original receipt. */
class IdempotentCommandTest {
    @Test fun retryReturnsTheOriginalReceiptAndAppliesOnce() {
        val t = Table(seed = 31)
        val players = t.fourPlayers()
        t.startDeal()
        val actor = players.getValue(t.turn())
        val id = checkNotNull(actor.client.submit(simpleBody(checkNotNull(actor.client.memberView))))
        t.until(5_000) { id in actor.client.replies }
        val original = assertIs<Receipt>(actor.client.replies.getValue(id))
        val revision = t.host.revision
        val commits = t.host.metrics.commits

        repeat(3) {
            assertTrue(actor.client.resend(id))
            t.run(200)
        }
        assertEquals(revision, t.host.revision, "a retry must not apply again")
        assertEquals(commits, t.host.metrics.commits)
        assertEquals(3, t.host.metrics.duplicateCommands)
        val repeats = actor.client.repeatedReplies.filterIsInstance<Receipt>().filter { it.commandId == id }
        assertEquals(3, repeats.size)
        for (r in repeats) assertContentEquals(WireCodec.encode(original), WireCodec.encode(r), "the retry must return the original receipt bytes")
    }

    @Test fun retriedRejectionReturnsTheOriginalRejection() {
        val t = Table(seed = 32)
        val players = t.fourPlayers()
        t.startDeal()
        val idle = players.getValue(t.turn().next)
        val id = checkNotNull(idle.client.submit(PassBody)) // not its turn
        t.until(5_000) { id in idle.client.replies }
        val original = assertIs<RejectionReceipt>(idle.client.replies.getValue(id))
        // Even after the game moves on, the same id returns the same answer.
        val actor = players.getValue(t.turn())
        t.command(actor, simpleBody(checkNotNull(actor.client.memberView)))
        idle.client.resend(id)
        t.run(200)
        val again = idle.client.repeatedReplies.filterIsInstance<RejectionReceipt>().single { it.commandId == id }
        assertContentEquals(WireCodec.encode(original), WireCodec.encode(again))
    }

    @Test fun networkDuplicatesAndClientRetriesNeverApplyACommandTwice() {
        val dupes = LinkFaults(latencyMs = 150, duplicateRate = 0.5, reorderRate = 0.2, reorderDelayMs = 200)
        val t = Table(seed = 33)
        val players = Seat.entries.associateWith { t.player(it, dupes) }
        val bots = players.values.map { Bot(t, it) }
        t.startDeal()
        val before = t.host.metrics.commits
        bots.forEach { it.poke() }
        t.until(10 * 60_000L) { t.host.revision >= 40 }
        bots.forEach { it.enabled = false }
        t.until(30_000) { players.values.all { it.client.pendingCommands == 0 } }
        val accepted = bots.sumOf { b -> b.gameCommands.count { b.client.replies[it] is Receipt } }
        assertEquals(accepted.toLong(), t.host.metrics.commits - before, "commits equal distinct accepted command ids")
        assertTrue(t.host.metrics.duplicateCommands > 0, "the faults must produce duplicates (retries over 300 ms round trips)")
    }
}
