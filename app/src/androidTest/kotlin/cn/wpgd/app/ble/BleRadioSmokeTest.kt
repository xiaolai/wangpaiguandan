package cn.wpgd.app.ble

import cn.wpgd.app.session.wire.PROTOCOL_VERSION
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.wpgd.app.BuildConfig
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.owner.ExecutorSessionLoop
import cn.wpgd.app.room.RoomAdvert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Android GATT glue on one device (WI-10). Opt-in: runs only in a bleTransport build with the
 * instrumentation argument `wpgdBleSmoke=true`, because it grants the Bluetooth permissions, which the
 * default suite asserts are not granted (`SoloNeedsNoBluetoothPermissionTest`):
 *
 * ```
 * ./gradlew -Pwpgd.bleTransport=true :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=cn.wpgd.app.ble.BleRadioSmokeTest \
 *   -Pandroid.testInstrumentationRunnerArguments.wpgdBleSmoke=true
 * ```
 *
 * It proves only that the server opens, its service registers and advertising starts, and that a scan
 * starts, on this device's stack. One device cannot connect to itself; links between phones are the
 * device spike's job (dev-docs/ble-device-spike.md).
 */
@RunWith(AndroidJUnit4::class)
class BleRadioSmokeTest {
    @Test fun theServerAdvertisesAndAScanStarts() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("opt-in only", args.getString("wpgdBleSmoke") == "true")
        assumeTrue(BuildConfig.BLE_TRANSPORT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        for (p in BlePermissions.RUNTIME) instrumentation.uiAutomation.grantRuntimePermission(ctx.packageName, p)

        val radio = AndroidBleRadio(ctx)
        val timers = ExecutorSessionLoop("smoke-timers") { throw it }
        val network = BleRoomNetwork(radio, timers)
        val listener = network.listen({ it() }) { }
        val advert = RoomAdvert("tsmoke01", "冒烟测试", "阿梅", PROTOCOL_VERSION, PROTOCOL_VERSION, "a".repeat(64), "b".repeat(64), "K".repeat(43), setOf(Seat.EAST), BleEndpoint.LOCAL)
        network.announce(advert)
        val server = network.stats().server!!
        val deadline = System.currentTimeMillis() + 10_000
        while (!server.advertising && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("advertising started (failures=${server.advertiseFailures})", server.advertising)
        assertEquals(0L, server.advertiseFailures)

        val browser = network.browser()
        val first = CountDownLatch(1)
        browser.start { first.countDown() }
        assertTrue(first.await(2, TimeUnit.SECONDS))
        Thread.sleep(1_500)
        assertEquals("scan started without failure", 0L, radio.stats.scanFailures)
        assertTrue(radio.stats.scanStarts >= 1)
        browser.stop()

        network.withdraw(advert.roomId)
        listener.close()
        Thread.sleep(500)
        assertTrue(!server.advertising)
        timers.shutdown()
    }
}
