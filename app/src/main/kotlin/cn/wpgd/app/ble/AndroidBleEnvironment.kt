package cn.wpgd.app.ble

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import cn.wpgd.app.data.KeyValueStore

/**
 * Reads what [BleGate] decides on, on Android. Touches no Bluetooth API that needs a permission:
 * the adapter's on/off and advertising support need none on Android 12+, and nothing here runs
 * below it. Only the Bluetooth entry calls this (never solo play or Wi-Fi rooms).
 */
class AndroidBleEnvironment(private val activity: Activity, private val store: KeyValueStore) {
    fun read(): BleEnvironment {
        val sdk = Build.VERSION.SDK_INT
        val hasBle = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        if (sdk < BlePermissions.MIN_SDK || !hasBle) {
            return BleEnvironment(sdk, hasBle, BlePermissions.RUNTIME, rationale = false, askedBefore = asked(), radioOn = false, canAdvertise = null)
        }
        val missing = BlePermissions.RUNTIME.filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toSet()
        val rationale = missing.any { activity.shouldShowRequestPermissionRationale(it) }
        val adapter = activity.getSystemService(BluetoothManager::class.java)?.adapter
        val radioOn = try {
            adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }
        val canAdvertise = if (!radioOn) {
            null
        } else {
            try {
                adapter?.isMultipleAdvertisementSupported
            } catch (_: SecurityException) {
                null
            }
        }
        return BleEnvironment(sdk, hasBle, missing, rationale, asked(), radioOn, canAdvertise)
    }

    /** Called when the system prompt is shown, so a later refusal reads as "denied", not "never asked". */
    fun markAsked() = store.put(ASKED, "1")

    private fun asked(): Boolean = store.get(ASKED) == "1"

    private companion object {
        const val ASKED = "ble.permission.asked"
    }
}
