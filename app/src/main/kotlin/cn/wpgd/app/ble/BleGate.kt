package cn.wpgd.app.ble

/** Why Bluetooth is being opened: hosting needs the radio to advertise; joining does not. */
enum class BlePurpose { HOST, JOIN }

/**
 * The runtime permissions a Bluetooth room needs (Android 12+, API 31), all in the "Nearby devices"
 * group, so the system shows one prompt for the three. BLUETOOTH_SCAN is declared with
 * `neverForLocation`: scans are never used to derive location, so no location permission exists in
 * this app, on any Android version.
 */
object BlePermissions {
    const val SCAN = "android.permission.BLUETOOTH_SCAN"
    const val CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
    val RUNTIME: Set<String> = setOf(SCAN, CONNECT, ADVERTISE)

    /**
     * Android 12. Below it a BLE scan requires a location permission (ACCESS_FINE_LOCATION on 10–11,
     * COARSE on 8–9), which this app never requests; Bluetooth rooms are not offered there.
     */
    const val MIN_SDK = 31
}

/**
 * What the platform reports right now (Android: `AndroidBleEnvironment`). Plain data, so the gate is
 * decided by JVM-tested code ([BleGate]).
 */
data class BleEnvironment(
    val sdkInt: Int,
    /** The device has Bluetooth LE at all. */
    val hasBle: Boolean,
    /** Of [BlePermissions.RUNTIME], those not granted. */
    val missing: Set<String>,
    /** The system would still show its prompt for a missing permission (`shouldShowRequestPermissionRationale`). */
    val rationale: Boolean,
    /**
     * This app has asked before (stored by the app): Android reports "never asked" and "denied for good"
     * the same way, so the first request is told apart by this flag.
     */
    val askedBefore: Boolean,
    val radioOn: Boolean,
    /** The controller can advertise (required to host); null while unknown (radio off). */
    val canAdvertise: Boolean?,
) {
    init {
        require(missing.all { it in BlePermissions.RUNTIME }) { "unknown permission" }
    }
}

/** Where the Bluetooth entry stands (B3, B4, B6 and the unsupported cases). */
enum class BleGateState {
    /** Android 8–11: Bluetooth rooms would need location access; offered: Wi-Fi, solo. */
    UNSUPPORTED_ANDROID,

    /** No Bluetooth LE on this device. */
    NO_BLUETOOTH,

    /** Not asked yet: explain first (B3), then the system prompt. */
    NEEDS_PERMISSION,

    /** Refused, and the system will ask again (B4 with "try again"). */
    DENIED,

    /** Refused for good, or the system no longer prompts: the app's settings page (B4). */
    PERMANENTLY_DENIED,

    /** The radio is off (B6); continues by itself once it is on. */
    RADIO_OFF,

    /** The radio cannot advertise, so this phone cannot host (it can still join). */
    CANNOT_HOST,

    READY,
}

/** What each non-ready screen offers. Every one offers a way out: the same Wi-Fi, or solo play. */
enum class BleGateAction { REQUEST_PERMISSION, OPEN_APP_SETTINGS, ENABLE_RADIO, USE_WIFI, PLAY_SOLO }

/**
 * The Bluetooth gate (GDN-004): decided only when the user picks a Bluetooth room. Solo play and
 * LAN rooms never reach it, so they never need, check or request a Bluetooth permission.
 */
object BleGate {
    fun state(env: BleEnvironment, purpose: BlePurpose): BleGateState = when {
        env.sdkInt < BlePermissions.MIN_SDK -> BleGateState.UNSUPPORTED_ANDROID
        !env.hasBle -> BleGateState.NO_BLUETOOTH
        env.missing.isNotEmpty() -> when {
            !env.askedBefore -> BleGateState.NEEDS_PERMISSION
            env.rationale -> BleGateState.DENIED
            else -> BleGateState.PERMANENTLY_DENIED
        }
        !env.radioOn -> BleGateState.RADIO_OFF
        purpose == BlePurpose.HOST && env.canAdvertise == false -> BleGateState.CANNOT_HOST
        else -> BleGateState.READY
    }

    /**
     * The actions a state's screen offers, the fix first. Empty only for [BleGateState.READY]. Solo play
     * is a new table: not offered while [newTables] is off (the tribute-profile kill switch, round 3 #20).
     */
    fun actions(state: BleGateState, newTables: Boolean = cn.wpgd.app.room.Features.TRIBUTE_PROFILE): List<BleGateAction> {
        val fix = when (state) {
            BleGateState.NEEDS_PERMISSION, BleGateState.DENIED -> BleGateAction.REQUEST_PERMISSION
            BleGateState.PERMANENTLY_DENIED -> BleGateAction.OPEN_APP_SETTINGS
            BleGateState.RADIO_OFF -> BleGateAction.ENABLE_RADIO
            BleGateState.READY -> return emptyList()
            BleGateState.UNSUPPORTED_ANDROID, BleGateState.NO_BLUETOOTH, BleGateState.CANNOT_HOST -> null
        }
        return listOfNotNull(fix, BleGateAction.USE_WIFI, BleGateAction.PLAY_SOLO.takeIf { newTables })
    }
}
