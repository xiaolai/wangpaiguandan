package cn.wpgd.app.ble

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.wpgd.app.BLE_LOG_TAG
import cn.wpgd.app.BuildConfig
import cn.wpgd.app.appGraph
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.Nickname
import cn.wpgd.app.data.NicknameResult
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.owner.SessionOwner
import cn.wpgd.app.room.LobbyUi
import cn.wpgd.app.room.RoomMedium
import cn.wpgd.app.room.RoomSettings
import cn.wpgd.app.session.wire.Role
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Bluetooth room between two real Android Bluetooth stacks (two emulators sharing the emulator's
 * virtual radio, or two phones): the joiner scans, reads the advert, connects over GATT, is approved,
 * readies; the host deals; the joiner receives its 27-card hand. Every byte crosses the Android glue
 * ([AndroidBleRadio]): MTU exchange, discovery, notifications, writes with response.
 *
 * Opt-in, one role per device, started at the same time:
 *
 * ```
 * adb -s <host>   shell am instrument -w -e class cn.wpgd.app.ble.BleTwoDeviceTest -e wpgdBleRole host cn.wpgd.app.test/androidx.test.runner.AndroidJUnitRunner
 * adb -s <joiner> shell am instrument -w -e class cn.wpgd.app.ble.BleTwoDeviceTest -e wpgdBleRole join cn.wpgd.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * An emulator radio says nothing about phone radios: no capacity or timing figure comes from here.
 */
@RunWith(AndroidJUnit4::class)
class BleTwoDeviceTest {
    private val tableName = "双机测试"
    private var owner: SessionOwner? = null

    @After fun tearDown() = appGraph.closeTable()

    private fun nickname(raw: String) = (Nickname.parse(raw) as NicknameResult.Valid).nickname

    private fun until(what: String, ms: Long = 120_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!done()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what; state ${owner?.state?.value}" }
            Thread.sleep(100)
        }
    }

    @Test fun aRoomCrossesTwoBluetoothStacks() {
        val role = InstrumentationRegistry.getArguments().getString("wpgdBleRole")
        assumeTrue("opt-in only", role == "host" || role == "join")
        assumeTrue(BuildConfig.BLE_TRANSPORT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (p in BlePermissions.RUNTIME) instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, p)
        appGraph.closeTable()
        if (role == "host") host() else join()
    }

    private fun host() {
        val o = appGraph.roomOwner(RoomMedium.BLUETOOTH).also { owner = it }
        assertTrue(o.hostRoom(nickname("阿梅"), RoomSettings(tableName)))
        until("the room") { o.state.value.table?.roomView != null }
        until("a joiner", 180_000) { o.state.value.table?.hostSide?.pending?.isNotEmpty() == true }
        o.approve(o.state.value.table!!.hostSide!!.pending.first().connectionId)
        o.setAiSeat(Seat.NORTH)
        o.setAiSeat(Seat.WEST)
        until("everyone ready") { LobbyUi.of(o.state.value, null)?.canStart == true }
        o.startMatch()
        until("deal 1") { o.state.value.table?.view?.match?.dealNumber == 1 }
        // Keep serving while the joiner checks its hand.
        Thread.sleep(20_000)
        log(o)
    }

    private fun join() {
        val finder = appGraph.finder(RoomMedium.BLUETOOTH)
        finder.start()
        val advert = try {
            until("the Bluetooth room in the list", 120_000) { finder.state.value.rooms.any { it.tableName == tableName } }
            finder.state.value.rooms.first { it.tableName == tableName }
        } finally {
            finder.stop()
        }
        val o = appGraph.roomOwner(RoomMedium.BLUETOOTH).also { owner = it }
        assertTrue(o.joinRoom(advert, Role.PLAYER, Seat.EAST, nickname("老周")))
        until("admission") { o.state.value.table?.join?.phase == JoinPhase.ADMITTED && o.state.value.table?.roomView != null }
        assertEquals(RoomMedium.BLUETOOTH, o.state.value.table!!.info!!.medium)
        o.setReady(true)
        until("my hand", 180_000) { o.state.value.table?.view?.let { it.match.dealNumber == 1 && it.deal != null } == true }
        val hand = o.state.value.table!!.view!!.deal!!.hand
        assertEquals(27, hand.size)
        log(o)
    }

    private fun log(o: SessionOwner) {
        BleReport.lines(appGraphBle(), o.metrics.receiptMs).forEach { Log.i(BLE_LOG_TAG, "two-device $it") }
    }

    private fun appGraphBle(): BleRoomNetwork = appGraph.bluetoothNetworkForTest
}
