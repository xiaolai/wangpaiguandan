package cn.wpgd.app.ble

import cn.wpgd.app.AppGraph
import cn.wpgd.app.BluetoothPlatform
import cn.wpgd.app.RoomPlatform
import cn.wpgd.app.TestProfiles
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.LocalStores
import cn.wpgd.app.nickname
import cn.wpgd.app.room.InProcessDirectory
import cn.wpgd.app.room.LanRoomNetwork
import cn.wpgd.app.room.RoomMedium
import cn.wpgd.app.room.RoomSettings
import cn.wpgd.app.room.RoomStore
import cn.wpgd.app.session.storage.InMemoryStorage
import cn.wpgd.app.session.transport.VirtualScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.Collections

/**
 * WI-10 (`bleTransport` flag, GDN-004): with the flag off the app graph has no Bluetooth at all;
 * with it on, Bluetooth is built only when a Bluetooth room is opened — solo play and Wi-Fi rooms
 * never construct it, so they never need a Bluetooth permission.
 */
class BluetoothEntryTest {
    private val graphs = ArrayList<AppGraph>()
    private var built = 0
    private val reported = Collections.synchronizedList(ArrayList<String>())
    private val air = FakeAir(VirtualScheduler(), 1)
    private val roomStore = InMemoryKeyValueStore()

    @After fun tearDown() = graphs.forEach { it.closeTable() }

    private fun graph(bluetooth: Boolean): AppGraph = AppGraph(
        LocalStores(InMemoryKeyValueStore(), InMemoryKeyValueStore(), InMemoryKeyValueStore(), InMemoryKeyValueStore(), roomStore),
        profiles = { TestProfiles.profiles },
        platform = { RoomPlatform(LanRoomNetwork(InProcessDirectory(), bindAddress = InetAddress.getLoopbackAddress()), InMemoryStorage()) },
        bluetooth = if (!bluetooth) null else {
            {
                built++
                BluetoothPlatform(BleRoomNetwork(air.radio(), VirtualScheduler())) { reported += it }
            }
        },
        bleReportMs = 20,
    ).also { graphs += it }

    private fun waitFor(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(5)
        }
    }

    @Test fun flagOffMeansNoBluetoothAnywhere() {
        val g = graph(bluetooth = false)
        assertFalse(g.bluetoothEnabled)
        assertTrue(runCatching { g.finder(RoomMedium.BLUETOOTH).start() }.isFailure)
        // A table saved by a Bluetooth build comes back over Wi-Fi: the kill switch keeps saves.
        RoomStore(roomStore).saveMedium("t1", RoomMedium.BLUETOOTH)
        assertEquals(RoomMedium.LAN, g.savedMedium("t1"))
    }

    @Test fun soloAndWifiRoomsNeverBuildBluetooth() {
        val g = graph(bluetooth = true)
        assertTrue(g.bluetoothEnabled)
        assertTrue(g.owner().startSolo(nickname()))
        waitFor { g.owner().state.value.table?.view != null }
        g.closeTable()
        val lan = g.roomOwner(RoomMedium.LAN)
        assertTrue(lan.hostRoom(nickname(), RoomSettings("周末掼蛋")))
        waitFor { lan.state.value.table?.roomView != null }
        assertEquals(RoomMedium.LAN, lan.state.value.table!!.info!!.medium)
        g.finder(RoomMedium.LAN).start()
        g.finder(RoomMedium.LAN).stop()
        assertEquals("Bluetooth was built", 0, built)
    }

    @Test fun aBluetoothRoomIsHostedOverBluetoothAndReportsWithoutAddresses() {
        val g = graph(bluetooth = true)
        val owner = g.roomOwner(RoomMedium.BLUETOOTH)
        assertTrue(owner.hostRoom(nickname(), RoomSettings("周末掼蛋")))
        waitFor { owner.state.value.table?.roomView != null }
        assertEquals(1, built)
        assertEquals(RoomMedium.BLUETOOTH, owner.state.value.table!!.info!!.medium)
        assertEquals(RoomMedium.BLUETOOTH, g.savedMedium(owner.state.value.table!!.tableId))
        waitFor { reported.any { it.startsWith("ble server connected=") } }
        val lines = synchronized(reported) { reported.toList() }
        assertTrue(lines.any { it.startsWith("ble radio ") })
        assertTrue(lines.any { it.startsWith("ble table receipt_ms_n=") })
        assertTrue("no Bluetooth address in diagnostics", lines.none { Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}").containsMatchIn(it) })
        assertTrue("no room id in diagnostics", lines.none { owner.state.value.table!!.tableId in it })
    }

    @Test fun closingTheBluetoothTableStopsItsDiagnosticsReport() {
        val g = graph(bluetooth = true)
        val owner = g.roomOwner(RoomMedium.BLUETOOTH)
        assertTrue(owner.hostRoom(nickname(), RoomSettings("周末掼蛋")))
        waitFor { reported.any { it.startsWith("ble server connected=") } }
        assertTrue(g.bleReportingForTest)
        g.closeTable()
        assertFalse("the report keeps no closed owner and no timer", g.bleReportingForTest)
        // Replacing a finished Bluetooth table with another owner stops it too.
        val again = g.roomOwner(RoomMedium.BLUETOOTH)
        assertTrue(g.bleReportingForTest)
        again.shutdown()
        waitFor { again.terminated }
        g.owner()
        assertFalse(g.bleReportingForTest)
    }
}
