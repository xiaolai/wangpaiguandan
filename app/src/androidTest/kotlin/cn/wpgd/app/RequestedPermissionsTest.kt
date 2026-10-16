package cn.wpgd.app

import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.wpgd.app.ble.BlePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WI-09, WI-10: what the installed app actually requests, checked against the platform. Every build:
 * INTERNET, a normal install-time permission (no prompt), so nothing permission-gated stands between
 * the user and solo play or a Wi-Fi room. Only a build with the bleTransport flag on adds the three
 * "Nearby devices" runtime permissions, BLUETOOTH_SCAN flagged neverForLocation, and nothing else
 * (no location permission).
 */
@RunWith(AndroidJUnit4::class)
class RequestedPermissionsTest {
    @Test fun onlyTheAllowedPermissionsAreRequested() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = ctx.packageManager
        val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toList()
        val expected = listOf("android.permission.INTERNET") + if (BuildConfig.BLE_TRANSPORT) BlePermissions.RUNTIME.toList() else emptyList()
        assertEquals(expected.sorted(), requested.sorted())
        assertEquals(PermissionInfo.PROTECTION_NORMAL, pm.getPermissionInfo("android.permission.INTERNET", 0).protection)
        if (BuildConfig.BLE_TRANSPORT) {
            for (p in BlePermissions.RUNTIME) assertEquals("$p is a runtime permission", PermissionInfo.PROTECTION_DANGEROUS, pm.getPermissionInfo(p, 0).protection)
            val scan = requested.indexOf(BlePermissions.SCAN)
            val flags = info.requestedPermissionsFlags!![scan]
            assertTrue("BLUETOOTH_SCAN is neverForLocation", flags and PackageInfoFlags.NEVER_FOR_LOCATION != 0)
        }
    }

    private object PackageInfoFlags {
        /** `PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION` (API 31). */
        const val NEVER_FOR_LOCATION = 0x00010000
    }
}
