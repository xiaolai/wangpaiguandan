package cn.wpgd.app.room

import cn.wpgd.app.ble.BleRoomNetwork
import cn.wpgd.app.ble.FakeAir
import cn.wpgd.app.session.transport.VirtualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A hosted room whose network stops hosting it (Bluetooth advertising or GATT failed) says so in the lobby. */
class HostingFailedTest {
    @Test fun theHostSeesThatNearbyDevicesCannotFindTheRoom() {
        val rig = RoomRig(seed = 151)
        val host = rig.host("阿梅")
        assertFalse(host.table!!.hostSide!!.hostingFailed)
        assertFalse(LobbyUi.of(host.state, null)!!.hostingFailed)
        rig.network.failHosting(rig.advertOf(host).endpoint)
        rig.until { host.table?.hostSide?.hostingFailed == true }
        assertTrue(LobbyUi.of(host.state, null)!!.hostingFailed)
    }

    @Test fun aFailedDiscoveryRegistrationReachesTheHost() {
        // Round 3 #4: a LAN room whose NSD registration failed is running but cannot be found.
        val rig = RoomRig(seed = 152)
        rig.network.failAnnouncements = true
        val host = rig.host("阿梅")
        rig.until { host.table?.hostSide?.hostingFailed == true }
        assertTrue(LobbyUi.of(host.state, null)!!.hostingFailed)
    }

    @Test fun aBluetoothServerFailureReachesTheRoomListener() {
        val scheduler = VirtualScheduler()
        val air = FakeAir(scheduler, 1)
        val radio = air.radio()
        val listener = BleRoomNetwork(radio, scheduler).listen({ it() }) { }
        var failed = 0
        listener.onFailure { failed++ }
        air.servers.getValue(radio.address).failHosting()
        air.servers.getValue(radio.address).failHosting()
        assertEquals("reported once", 1, failed)
        listener.onFailure { failed++ }
        assertEquals("a late registration still hears it", 2, failed)
        listener.close()
    }
}
