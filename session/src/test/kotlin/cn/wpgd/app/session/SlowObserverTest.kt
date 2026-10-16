package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.transport.LinkFaults
import cn.wpgd.app.session.wire.FollowRequestBody
import cn.wpgd.app.session.wire.GrantStatus
import cn.wpgd.app.session.wire.Receipt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GDN-071: a slow observer cannot delay player receipts; its queue stays bounded. */
class SlowObserverTest {
    private class Run(val latencies: List<Long>, val observerQueueMax: Int, val converged: Boolean)

    private fun run(observerLink: LinkFaults): Run {
        val t = Table(seed = 101)
        val players = t.fourPlayers()
        val o1 = t.observer("o1", observerLink)
        val o2 = t.observer("o2", observerLink)
        // o1 follows SOUTH, so it also carries follow frames.
        t.command(o1, FollowRequestBody(Seat.SOUTH))
        val south = players.getValue(Seat.SOUTH)
        t.until(60_000) { south.client.grants.any { it.status == GrantStatus.PENDING } }
        t.command(south, cn.wpgd.app.session.wire.ConsentBody(south.client.grants.single().grantId, true))
        t.startDeal()

        val latencies = ArrayList<Long>()
        var queueMax = 0
        repeat(60) {
            val actor = players.getValue(t.turn())
            val sentAt = t.scheduler.now
            val id = checkNotNull(actor.client.submit(simpleBody(actor.client.memberView!!)))
            t.until(10_000) {
                queueMax = maxOf(queueMax, t.host.queuedFor(o1.client.memberId!!), t.host.queuedFor(o2.client.memberId!!))
                id in actor.client.replies
            }
            check(actor.client.replies[id] is Receipt)
            latencies += t.scheduler.now - sentAt
            t.until(10_000) { players.values.all { it.client.revision == t.host.revision } }
        }
        val converged = t.scheduler.runUntil(120_000) { o1.client.revision == t.host.revision && o2.client.revision == t.host.revision }
        check(t.host.metrics.closed.isEmpty()) { "a peer was closed: ${t.host.metrics.closed}" }
        return Run(latencies, queueMax, converged)
    }

    @Test fun playerReceiptLatencyIsTheSameWithAFastOrASlowObserver() {
        val fast = run(LinkFaults.RELIABLE)
        val slow = run(LinkFaults(latencyMs = 800, window = 2))
        assertEquals(fast.latencies, slow.latencies, "a slow observer changed player receipt latency")
        assertTrue(slow.latencies.all { it <= 10 }, "player receipts stay at link latency: ${slow.latencies.max()} ms")
        assertTrue(slow.observerQueueMax <= 64, "observer queue must stay within its bound: ${slow.observerQueueMax}")
        assertTrue(slow.observerQueueMax > fast.observerQueueMax, "the slow link must actually back up")
        assertTrue(slow.converged, "the slow observer catches up once play pauses")
        println("SlowObserver: player receipt latency max ${slow.latencies.max()} ms; slow observer queue max ${slow.observerQueueMax}")
    }
}
