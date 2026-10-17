package cn.wpgd.app.session

import cn.wpgd.app.core.match.MatchMemberView
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchProjectionCodec
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.StandardDeck
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.transport.LinkFaults
import cn.wpgd.app.session.wire.FollowRequestBody
import cn.wpgd.app.session.wire.FollowSnapshot
import cn.wpgd.app.session.wire.GrantStatus
import cn.wpgd.app.session.wire.PassBody
import cn.wpgd.app.session.wire.Receipt
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.session.wire.RejectionReceipt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Four players and two observers complete one deal (deal, play out, settle) over links that drop,
 * duplicate, reorder, truncate and delay chunks, with every session invariant checked at the end.
 * Observer O1 follows SOUTH with consent; O2 watches the public table only.
 */
class FullDealOverFaultyTransportTest {
    private val faults = LinkFaults(
        latencyMs = 4, jitterMs = 6, dropRate = 0.003, duplicateRate = 0.02,
        reorderRate = 0.05, reorderDelayMs = 40, truncateRate = 0.005,
    )

    @Test fun fourPlayersAndTwoObserversCompleteADealOverAFaultyTransport() {
        for (seed in listOf(11L, 12L, 13L)) playOneDeal(seed)
    }

    private fun playOneDeal(seed: Long) {
        val started = System.nanoTime()
        val t = Table(seed = seed)
        val players = Seat.entries.associateWith { t.player(it, faults) }
        val o1 = t.observer("o1", faults)
        val o2 = t.observer("o2", faults)
        val revisionsSeen = (players.values + o1 + o2).associate { p -> p.client to ArrayList<Long>() }
        for ((c, list) in revisionsSeen) {
            val prev = c.onUpdate
            c.onUpdate = { prev?.invoke(); list += c.revision }
        }
        val bots = players.mapValues { (seat, p) -> Bot(t, p, consents = seat == Seat.SOUTH) }

        // O1 asks to follow SOUTH; SOUTH's bot consents.
        t.command(o1, FollowRequestBody(Seat.SOUTH))
        t.until(30_000) { o1.client.grants.any { it.status == GrantStatus.ACTIVE } }

        assertTrue(t.host.advance() is SessionHost.AdvanceResult.Applied) // deal
        bots.values.forEach { it.poke() }
        var observerTried = false
        val done = t.scheduler.runUntil(maxMs = 30 * 60_000L) {
            when (t.host.state.phase) {
                MatchPhase.READY, MatchPhase.DEAL_ENDED -> { t.host.advance(); bots.values.forEach { it.poke() } }
                MatchPhase.PLAYING -> if (!observerTried && t.host.revision > 20) {
                    observerTried = true
                    o2.client.submit(PassBody)
                }
                else -> Unit
            }
            t.host.state.dealNumber == 1 && t.host.state.previous != null
        }
        assertTrue(done, "seed $seed: the deal did not finish (phase ${t.host.state.phase}, revision ${t.host.revision})")
        bots.values.forEach { it.enabled = false }
        // Quiesce: heartbeats reveal the final revision and every client resyncs to it.
        val converged = t.scheduler.runUntil(60_000) {
            (players.values + o1 + o2).all { it.client.revision == t.host.revision } && o1.client.follow?.revision == t.host.revision
        }
        assertTrue(converged, "seed $seed: not converged to ${t.host.revision}: " + (players.values + o1 + o2).joinToString { p ->
            "${p.client.memberId}:${p.client.phase}/${p.client.revision}/close=${p.client.closeReason}/follow=${p.client.follow?.revision}"
        } + " host closed ${t.host.metrics.closed}")
        t.run(3_000)

        // Converged, byte for byte, to the host's projections.
        for ((seat, p) in players) assertEquals(t.memberBytes(seat), p.client.viewJson, "seed $seed: $seat view")
        assertEquals(t.spectatorBytes(), o1.client.viewJson)
        assertEquals(t.spectatorBytes(), o2.client.viewJson)
        assertEquals(t.memberBytes(Seat.SOUTH), MatchProjectionCodec.encode(checkNotNull(o1.client.follow).view).decodeToString())

        // Revisions never regressed on any client.
        for ((c, seen) in revisionsSeen) assertEquals(seen.sorted(), seen, "revision regressed for ${c.memberId}")

        // Each accepted command applied once: commits = host steps + distinct accepted game commands.
        val accepted = bots.values.sumOf { b -> b.gameCommands.count { b.client.replies[it] is Receipt } }
        val hostSteps = 3 // deal, open play, settle
        assertEquals(hostSteps + accepted.toLong(), t.host.metrics.commits, "seed $seed: commits")
        assertEquals(t.host.revision, t.host.metrics.commits, "one revision per commit")

        // Card conservation and one profile hash throughout.
        val deal = checkNotNull(t.host.state.deal)
        val all = deal.hands.values.flatten() + deal.played
        assertEquals(108, all.size)
        assertEquals(StandardDeck.ids, all.toSet())
        for (p in players.values) {
            val v = checkNotNull(p.client.memberView)
            assertEquals(TestProfiles.base.hash, v.match.baseProfileHash)
            assertEquals(TestProfiles.match.hash, v.match.matchProfileHash)
        }

        // The observer's game command was refused, typed; no private frame reached the public-only observer.
        val o2Reply = o2.client.replies.values.single()
        assertTrue(o2Reply is RejectionReceipt && o2Reply.code == RejectionCode.NOT_A_PLAYER)
        assertTrue(o2.received.none { it is FollowSnapshot })
        assertTrue(o1.received.filterIsInstance<FollowSnapshot>().all { it.seat == Seat.SOUTH })
        assertTrue(o1.received.filterIsInstance<FollowSnapshot>().map { it.view }.none { v -> isOtherSeat(v) })

        // No divergence; receipts were actually checked.
        for (p in players.values) {
            assertEquals(0, p.client.metrics.digestMismatches, "digest mismatch at ${p.client.seat}")
        }
        assertTrue(players.values.sumOf { it.client.metrics.digestsVerified } > 50, "receipt digests were verified")
        val ms = (System.nanoTime() - started) / 1_000_000
        println(
            "FullDeal seed $seed: revision ${t.host.revision}, virtual ${t.scheduler.now} ms, wall $ms ms, " +
                "chunks ${t.network.captured.size}, duplicates ${t.host.metrics.duplicateCommands}, resyncs ${t.host.metrics.resyncs}, " +
                "violations ${t.host.metrics.violations}",
        )
        assertTrue(ms < 5 * 60_000, "hang guard: one deal took $ms ms of wall time")
    }

    private fun isOtherSeat(view: String): Boolean =
        cn.wpgd.app.session.wire.WireCodec.json.decodeFromString(MatchMemberView.serializer(), view).seat != Seat.SOUTH
}
