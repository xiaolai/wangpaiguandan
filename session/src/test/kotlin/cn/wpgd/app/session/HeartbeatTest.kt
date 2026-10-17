package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.wire.CloseReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GDN-088, audit A10: a blackholed peer is detected within the configured limit; others continue. */
class HeartbeatTest {
    @Test fun blackholedPlayerIsDetectedWithinTheLimitAndTheTableContinues() {
        val t = Table(seed = 111)
        val players = t.fourPlayers()
        t.startDeal()
        val victim = players.getValue(Seat.WEST)
        val limit = t.hostConfig.livenessTimeoutMs + t.hostConfig.heartbeatIntervalMs
        val start = t.scheduler.now
        victim.link.blackholed = true
        t.until(limit + 100) { t.host.members().single { it.seat == Seat.WEST }.connected.not() }
        val hostDetected = t.scheduler.now - start
        t.until(limit + 100) { victim.client.stale }
        val clientDetected = t.scheduler.now - start
        assertTrue(hostDetected <= limit, "host detected after $hostDetected ms (limit $limit)")
        assertTrue(clientDetected <= limit, "client detected after $clientDetected ms (limit $limit)")
        assertEquals(1L, t.host.metrics.closed[CloseReason.HEARTBEAT_TIMEOUT])
        assertEquals(ClientPhase.CLOSED, victim.client.phase)
        // The rest of the table stays connected and can act.
        assertTrue(players.filterKeys { it != Seat.WEST }.values.all { it.client.phase == ClientPhase.ADMITTED && !it.client.stale })
        if (t.turn() != Seat.WEST) {
            val actor = players.getValue(t.turn())
            assertTrue(t.command(actor, simpleBody(actor.client.memberView!!)) is cn.wpgd.app.session.wire.Receipt)
        }
        println("Heartbeat: host detected in $hostDetected ms, client in $clientDetected ms (limit $limit)")
    }

    @Test fun idleButLiveConnectionsAreNeverDropped() {
        val t = Table(seed = 112)
        val players = t.fourPlayers()
        t.observer("o1")
        t.run(120_000)
        assertTrue(t.host.members().all { it.connected })
        assertTrue(players.values.all { !it.client.stale && it.client.phase == ClientPhase.ADMITTED })
        assertEquals(null, t.host.metrics.closed[CloseReason.HEARTBEAT_TIMEOUT])
    }
}
