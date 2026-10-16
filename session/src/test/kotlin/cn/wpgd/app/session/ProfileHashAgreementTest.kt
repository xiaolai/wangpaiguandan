package cn.wpgd.app.session

import cn.wpgd.app.core.match.MatchProjectionCodec
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.wire.HelloRejectReason
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.session.wire.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GDN-029: host and every client agree on both profile hashes; a mismatch is refused at handshake. */
class ProfileHashAgreementTest {
    @Test fun mismatchedBaseOrMatchHashIsRejectedBeforeAnyGameData() {
        val t = Table(seed = 141)
        val other = "0".repeat(64)
        for (cfg in listOf(
            t.config(Role.PLAYER, "p1", Seat.SOUTH).copy(baseProfileHash = other),
            t.config(Role.PLAYER, "p2", Seat.SOUTH).copy(matchProfileHash = other),
        )) {
            val p = t.joiner(cfg)
            t.until(5_000) { p.client.phase == ClientPhase.REJECTED }
            assertEquals(HelloRejectReason.PROFILE_MISMATCH, p.client.helloRejection?.reason)
            assertEquals(1, p.received.size, "nothing but the rejection")
        }
    }

    @Test fun everyClientCarriesTheHostsHashesAndRefusesAViewWithAnother() {
        val t = Table(seed = 142)
        val players = t.fourPlayers()
        val o = t.observer("o1")
        t.startDeal()
        for (p in players.values) {
            assertEquals(t.host.state.baseProfileHash, p.client.memberView!!.match.baseProfileHash)
            assertEquals(t.host.state.matchProfileHash, p.client.memberView!!.match.matchProfileHash)
        }
        assertEquals(t.host.state.matchProfileHash, o.client.spectatorView!!.match.matchProfileHash)
        assertEquals(TestProfiles.match.hash, t.host.state.matchProfileHash)

        // A view carrying another hash is not applied by the client.
        val south = players.getValue(Seat.SOUTH).client
        val forgedView = south.memberView!!.let { v -> v.copy(match = v.match.copy(matchProfileHash = "f".repeat(64))) }
        val revision = south.revision
        south.onMessage(Snapshot(south.epoch!!, revision + 1, MatchProjectionCodec.encode(forgedView).decodeToString()))
        assertEquals(revision, south.revision)
        assertTrue(south.metrics.profileHashMismatches == 1L)
    }
}
