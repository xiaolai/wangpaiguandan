package cn.wpgd.app.room

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.lan.LanEndpoint
import cn.wpgd.app.nickname
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.session.wire.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** H2 and C9: a lost host is rejoined automatically into the same seat; an unreachable room fails plainly. */
class HostLostTest {
    @Test fun aJoinedPlayerRejoinsByItselfWhenTheHostComesBack() {
        val rig = RoomRig(seed = 141)
        val host = rig.host("阿梅")
        val zhou = rig.phone("老周")
        rig.startedRoom(host, mapOf(Seat.EAST to zhou))
        val hand = zhou.table!!.view!!.deal!!.hand.map { it.id }.toSet()

        rig.network.dropAll(rig.advertOf(host).endpoint)
        rig.until { zhou.table?.join?.phase == JoinPhase.HOST_LOST }
        assertEquals("the table stays on screen meanwhile", hand, zhou.table!!.view!!.deal!!.hand.map { it.id }.toSet())
        rig.until { zhou.table?.join?.phase == JoinPhase.ADMITTED }
        assertEquals(Seat.EAST, zhou.table!!.seat)
        assertTrue(zhou.owner.metrics.rejoinAttempts.get() >= 1)
        assertTrue("no approval was needed", host.table!!.hostSide!!.pending.isEmpty())
    }

    @Test fun requestsLostWithTheirConnectionAreNotKeptForever() {
        val rig = RoomRig(seed = 143)
        val host = rig.host("阿梅")
        val zhou = rig.join(rig.phone("老周"), host, Seat.EAST)
        repeat(3) { round ->
            zhou.owner.setReady(round % 2 == 0)
            rig.run(0) // handed to the client; the reply has not arrived
            assertEquals(1, zhou.owner.sessionRequestsInFlight)
            rig.network.dropAll(rig.advertOf(host).endpoint)
            rig.until { zhou.table?.join?.phase == JoinPhase.HOST_LOST }
            assertEquals("no reply can come over a closed connection", 0, zhou.owner.sessionRequestsInFlight)
            rig.until { zhou.table?.join?.phase == JoinPhase.ADMITTED }
        }
        assertEquals(0, zhou.owner.sessionRequestsInFlight)
    }

    @Test fun anUnreachableRoomFailsPlainly() {
        val rig = RoomRig(seed = 142)
        val host = rig.host("阿梅")
        val gone = rig.advertOf(host).copy(endpoint = LanEndpoint("mem", 1))
        val zhou = rig.phone("老周")
        assertTrue(zhou.owner.joinRoom(gone, Role.PLAYER, Seat.EAST, nickname("老周")))
        rig.until { zhou.table?.join?.phase == JoinPhase.FAILED }
        rig.run(30_000)
        assertEquals("never admitted, so no automatic retry", JoinPhase.FAILED, zhou.table!!.join!!.phase)
    }
}
