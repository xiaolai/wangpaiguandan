package cn.wpgd.app.room

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.lan.LanEndpoint
import cn.wpgd.app.nickname
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.session.storage.InMemoryStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GDN-085 ("changed host port does not change seat ownership"), H2: the host's process dies without
 * suspending, and the restored table listens on a new port (the LAN server binds an ephemeral one).
 * A joined player still waiting for the host follows the room to its new address by the room id it
 * was admitted to, and gets its own seat and cards back without approval.
 */
class HostPortChangeRejoinTest {
    @Test fun aLostPlayerFollowsItsRoomToTheHostsNewPort() {
        val rig = RoomRig(seed = 151)
        val storage = InMemoryStorage()
        val rooms = RoomStore(InMemoryKeyValueStore())
        val host = rig.host("阿梅", storage = storage, rooms = rooms)
        val zhou = rig.phone("老周")
        rig.startedRoom(host, mapOf(Seat.EAST to zhou))
        val id = host.table!!.tableId
        val oldEndpoint = rig.advertOf(host).endpoint as LanEndpoint
        val hand = zhou.table!!.view!!.deal!!.hand.map { it.id }.toSet()

        // The host's process dies: no suspend notice, the advert goes away, every link drops.
        host.owner.shutdown()
        rig.until { zhou.table?.join?.phase == JoinPhase.HOST_LOST }
        rig.run(5_000)
        assertEquals("nothing listens on the old port", JoinPhase.HOST_LOST, zhou.table!!.join!!.phase)

        // The host's device restores the table; it is announced on a new port.
        val again = rig.phone("阿梅", storage, rooms, host.identity)
        assertTrue(again.owner.restoreRoom(id, nickname("阿梅")))
        rig.until { again.table?.hostSide != null && rig.network.directory.announced.any { it.roomId == id } }
        assertNotEquals("the restored room listens on another port", oldEndpoint, rig.advertOf(again).endpoint)

        rig.until { zhou.table?.join?.phase == JoinPhase.ADMITTED && zhou.table?.view?.deal != null }
        assertEquals(Seat.EAST, zhou.table!!.seat)
        assertEquals(hand, zhou.table!!.view!!.deal!!.hand.map { it.id }.toSet())
        assertTrue("nobody had to approve the returning player", again.table!!.hostSide!!.pending.isEmpty())
    }

    @Test fun aRoomWithAnotherIdAtTheOldAddressIsNotFollowed() {
        val rig = RoomRig(seed = 152)
        val host = rig.host("阿梅")
        val zhou = rig.phone("老周")
        rig.startedRoom(host, mapOf(Seat.EAST to zhou))
        host.owner.shutdown()
        rig.until { zhou.table?.join?.phase == JoinPhase.HOST_LOST }

        // A different room appears nearby; the lost player must not wander into it.
        val other = rig.host("小王", RoomSettings("另一桌"))
        rig.run(10_000)
        assertEquals(JoinPhase.HOST_LOST, zhou.table!!.join!!.phase)
        assertTrue("the other host saw no join from the lost player", other.table!!.hostSide!!.pending.isEmpty())
    }

    /**
     * G2: while the host is lost, an advert with the same room id but another host key (a spoofer,
     * or anyone reusing the id) is not followed; the pinned host, restored on a new port, is.
     */
    @Test fun anAdvertWithTheRoomIdButAnotherHostKeyIsNotFollowed() {
        val rig = RoomRig(seed = 153)
        val storage = InMemoryStorage()
        val rooms = RoomStore(InMemoryKeyValueStore())
        val host = rig.host("阿梅", storage = storage, rooms = rooms)
        val zhou = rig.phone("老周")
        rig.startedRoom(host, mapOf(Seat.EAST to zhou))
        val id = host.table!!.tableId
        val real = rig.advertOf(host)
        assertEquals(real.hostKey, zhou.identity.rejoinTicket(id)!!.hostKey)
        host.owner.shutdown()
        rig.until { zhou.table?.join?.phase == JoinPhase.HOST_LOST }

        // Someone announces the same room id with another key at another address.
        val spoofKey = "S".repeat(43)
        rig.network.directory.announce(real.copy(hostKey = spoofKey, endpoint = LanEndpoint("10.9.9.9", 9999)))
        rig.run(10_000)
        assertEquals(JoinPhase.HOST_LOST, zhou.table!!.join!!.phase)
        assertEquals("the spoofed advert was not followed", 0L, zhou.owner.metrics.roomMoves.get())
        assertTrue(zhou.owner.metrics.roomMovesRefused.get() > 0)
        rig.network.directory.withdraw(id)

        // The real host comes back on a new port, with the same room key: followed, seat returned.
        val again = rig.phone("阿梅", storage, rooms, host.identity)
        assertTrue(again.owner.restoreRoom(id, nickname("阿梅")))
        rig.until { again.table?.hostSide != null && rig.network.directory.announced.any { it.roomId == id } }
        assertEquals(real.hostKey, rig.advertOf(again).hostKey)
        rig.until { zhou.table?.join?.phase == JoinPhase.ADMITTED && zhou.table?.view?.deal != null }
        assertEquals(1L, zhou.owner.metrics.roomMoves.get())
        assertEquals(Seat.EAST, zhou.table!!.seat)
    }
}
