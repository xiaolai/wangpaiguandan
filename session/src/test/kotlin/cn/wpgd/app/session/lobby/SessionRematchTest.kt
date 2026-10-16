package cn.wpgd.app.session.lobby

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.Table
import cn.wpgd.app.session.ai.AiDriverConfig
import cn.wpgd.app.session.ai.AiSeatDriver
import cn.wpgd.app.session.ai.ScriptedHuman
import cn.wpgd.app.session.ai.driveUntil
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.wire.Receipt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * GDN-017 at the host: after a match ends, a rematch keeps the members, their seats, capabilities and
 * connections (nobody searches, joins or is approved again), continues the revision order and returns
 * the room to the lobby with every ready flag cleared.
 */
class SessionRematchTest {
    @Test fun aRematchKeepsSeatsMembersAndConnections() {
        val t = Table(seed = 931)
        val driver = AiSeatDriver(t.scheduler, AiDriverConfig(tieBreakSeed = 931))
        val south = t.player(Seat.SOUTH)
        val watcher = t.observer("watcher")
        for (s in Seat.entries.drop(1)) assertTrue(t.host.setAi(s, driver.policy))
        assertFalse(t.host.rematch(), "no rematch before the match is over")
        assertIs<Receipt>(t.ready(south))
        assertIs<SessionHost.StartResult.Started>(t.host.start())
        ScriptedHuman(t, south)
        t.driveUntil { it.state.phase == MatchPhase.OVER }
        t.run(500)
        val over = t.host.revision
        val members = t.host.members()
        val capability = south.client.capability
        val admitted = t.host.metrics.admitted
        val connections = t.host.connectionCount
        assertEquals(MatchPhase.OVER, south.client.memberView!!.match.phase)

        assertTrue(t.host.rematch())
        t.until(5_000) { south.client.revision == over + 1 && south.client.room?.lobby == true && watcher.client.revision == over + 1 }
        assertEquals(MatchPhase.AWAITING_DEAL, south.client.memberView!!.match.phase)
        assertEquals(0, south.client.memberView!!.match.dealNumber)
        assertEquals(members, t.host.members(), "same members in the same seats")
        assertEquals(capability, south.client.capability, "same capability: nobody joins again")
        assertEquals(admitted, t.host.metrics.admitted, "nobody was approved again")
        assertEquals(connections, t.host.connectionCount, "same connections: no rediscovery")
        assertEquals(ClientPhase.ADMITTED, watcher.client.phase)
        assertEquals(over + 1, watcher.client.revision, "the watcher follows into the new match")
        assertTrue(south.client.room!!.seats.none { it.ready }, "everyone readies again")
        assertEquals(0L, south.client.metrics.digestMismatches)

        assertIs<Receipt>(t.ready(south))
        assertIs<SessionHost.StartResult.Started>(t.host.start())
        t.until(5_000) { south.client.memberView?.match?.dealNumber == 1 }
    }
}
