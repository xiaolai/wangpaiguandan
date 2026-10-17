package cn.wpgd.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import cn.wpgd.app.ble.AndroidBleRadio
import cn.wpgd.app.ble.BleRoomNetwork
import cn.wpgd.app.data.LocalStores
import cn.wpgd.app.diagnostics.AppDiagnostics
import cn.wpgd.app.diagnostics.CrashCounter
import cn.wpgd.app.data.SharedPreferencesKeyValueStore
import cn.wpgd.app.lan.NsdRoomDirectory
import cn.wpgd.app.owner.ExecutorSessionLoop
import cn.wpgd.app.release.BuildMode
import cn.wpgd.app.release.ReleaseGate
import cn.wpgd.app.room.Features
import cn.wpgd.app.room.LanRoomNetwork
import cn.wpgd.app.session.storage.FileStorage
import cn.wpgd.app.table.RuleProfiles

/** Holds the [AppGraph] for the life of the process, so the table never belongs to an Activity. */
class WpgdApplication : Application() {
    /** The packaged rule profiles (from rules/profiles/, see app/build.gradle.kts). */
    private val profiles: RuleProfiles by lazy { RuleProfiles.parse(asset("P1-base-v1.json"), asset("M1-match-v1.json")) }

    /** WI-12: crashes counted on this device (a number only), written before the process ends. */
    private val crashes: CrashCounter by lazy { CrashCounter(SharedPreferencesKeyValueStore(getSharedPreferences("diagnostics", Context.MODE_PRIVATE), synchronous = true)) }

    /** WI-12: the local diagnostics export the settings screen shows on request (nothing is sent). */
    val diagnostics: AppDiagnostics by lazy { AppDiagnostics(BuildMode.of(BuildConfig.RELEASE_GATE), BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, { profiles }, crashes) }

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(crashes.handler(Thread.getDefaultUncaughtExceptionHandler()))
        // WI-12 release gate (plan section 3): a release build with an unsigned rule profile stops here, loudly.
        ReleaseGate.check(BuildMode.of(BuildConfig.RELEASE_GATE)) { profiles }
    }

    val graph: AppGraph by lazy {
        AppGraph(
            LocalStores(
                preferences = store("preferences"),
                profile = store("profile"),
                history = store("history"),
                identity = store("identity"),
                rooms = store("rooms"),
            ),
            profiles = { profiles },
            platform = {
                // Rooms only: LAN sockets with NSD discovery, hosted tables under app-private files
                // (excluded from backup and transfer, data_extraction_rules.xml).
                RoomPlatform(LanRoomNetwork(NsdRoomDirectory(this)), FileStorage(filesDir.toPath().resolve("tables")))
            },
            // WI-10: only with the bleTransport flag on, and built only when a Bluetooth room is opened
            // after the permission gate (never for solo play or Wi-Fi rooms).
            bluetooth = if (Features.BLE_TRANSPORT) ({ bluetooth() }) else null,
        )
    }

    /** Bluetooth rooms (Android 12+ only; the gate never lets an older device get here). */
    private fun bluetooth(): BluetoothPlatform {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) throw IllegalStateException("Bluetooth rooms need Android 12")
        // Link and browser timers on their own thread; a failure there is a bug and crashes loudly.
        val timers = ExecutorSessionLoop("wpgd-ble-timers") { e -> Thread.currentThread().let { t -> t.uncaughtExceptionHandler?.uncaughtException(t, e) ?: throw e } }
        return BluetoothPlatform(BleRoomNetwork(AndroidBleRadio(this), timers)) { line -> Log.i(BLE_LOG_TAG, line) }
    }

    /** The end-to-end driver's seam; bound per build type ([automationHook]), [AutomationHook.None] in release. */
    val automation: AutomationHook get() = automationHook

    fun store(name: String) = SharedPreferencesKeyValueStore(getSharedPreferences(name, Context.MODE_PRIVATE))

    private fun asset(name: String): String = assets.open(name).use { it.readBytes().decodeToString() }
}

/** Logcat tag of the Bluetooth diagnostics lines (`adb logcat -s wpgd-ble`, dev-docs/ble-device-spike.md). */
const val BLE_LOG_TAG = "wpgd-ble"
