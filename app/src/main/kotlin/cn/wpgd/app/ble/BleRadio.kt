package cn.wpgd.app.ble

/**
 * The Bluetooth radio as the room layer needs it, with no Android type in it: the Android glue
 * ([AndroidBleRadio]) implements it with GATT, and tests use an in-process fake. One GATT service
 * ([BleGattSpec]) carries a room: a readable advert, a write characteristic towards the host and a
 * notify characteristic towards each joiner.
 *
 * Every callback may arrive on any thread, and late (after a close); the code above binds callbacks
 * to the object they came from.
 */
interface BleRadio {
    /**
     * Opens the GATT server of one hosted room. Each central that connects and subscribes to the
     * notify characteristic becomes a [BlePort] handed to [onPeer], already ready to send.
     */
    fun openServer(onPeer: (BlePort) -> Unit): BleServer

    /** A port to the host at [address]; it connects when opened, and reports ready or disconnected. */
    fun connect(address: String): BlePort

    /** Scans for hosts of the room service and reads their adverts. */
    fun scanner(): BleScanner

    /** Radio-level counters for the device spike (no payload, no addresses). */
    val stats: BleRadioStats
}

/** Counters of discovery and connection set-up. */
class BleRadioStats {
    @Volatile var scanStarts: Long = 0
    @Volatile var scanFailures: Long = 0
    @Volatile var scanResults: Long = 0
    @Volatile var advertReads: Long = 0
    @Volatile var advertReadFailures: Long = 0
    @Volatile var connectFailures: Long = 0
    @Volatile var notifyTimeouts: Long = 0

    fun summary(): String = "scan_starts=$scanStarts scan_failures=$scanFailures scan_results=$scanResults " +
        "advert_reads=$advertReads advert_read_failures=$advertReadFailures connect_failures=$connectFailures notify_timeouts=$notifyTimeouts"
}

/** One hosted room's GATT server and advertisement. */
interface BleServer {
    /**
     * Registers [failed], called at most once, on any thread, when the server cannot host (no GATT
     * server, the service was refused, advertising failed); at once if it already failed.
     */
    fun onFailure(failed: () -> Unit) = Unit

    /** Serves [advert] on the advert characteristic and advertises the service; null stops advertising. */
    fun setAdvert(advert: ByteArray?)

    /** Stops advertising, disconnects every central and closes the server. Idempotent. */
    fun close()

    /** Counters for the device spike (no payload). */
    val stats: BleServerStats
}

/** Server counters: what the G5 spike reads for "simultaneous connections". */
class BleServerStats {
    @Volatile var connected: Int = 0
    @Volatile var maxConnected: Int = 0
    @Volatile var subscribed: Int = 0
    @Volatile var refused: Long = 0
    @Volatile var advertiseFailures: Long = 0
    @Volatile var advertising: Boolean = false

    fun onConnected(now: Int) {
        connected = now
        if (now > maxConnected) maxConnected = now
    }
}

/**
 * One GATT connection seen as a packet pipe. One packet in flight at a time: after [send] returns
 * true, the next [send] waits for [BlePortEvents.onSent] (Android allows one outstanding write or
 * notification per connection).
 */
interface BlePort {
    /** Connection address for diagnostics (no secret: a Bluetooth address, possibly random). */
    val address: String

    /** Starts the port: connects (joiner) and reports through [events]. Called once. */
    fun open(events: BlePortEvents)

    /** Starts sending [packet]; false when it cannot be sent now (not ready, closed, or one in flight). */
    fun send(packet: ByteArray): Boolean

    /** Disconnects. Idempotent; [BlePortEvents.onDisconnected] may still follow. */
    fun close()
}

interface BlePortEvents {
    /** The connection can carry packets (joiner: MTU exchanged, services found, notifications on). */
    fun onReady(attPayload: Int)

    /** The ATT payload changed (an MTU exchange completed). */
    fun onAttPayload(attPayload: Int)

    fun onPacket(packet: ByteArray)

    /** The packet in flight completed; [ok] false when the stack reported a failure. */
    fun onSent(ok: Boolean)

    fun onDisconnected()
}

/** Scanning for hosted rooms and reading one host's advert at a time. */
interface BleScanner {
    /** Starts scanning; [onSeen] gets each host address every time it is seen. */
    fun start(onSeen: (address: String) -> Unit)

    fun stop()

    /** Reads the advert characteristic of [address]; [onResult] gets its bytes, or null on any failure or timeout. */
    fun readAdvert(address: String, onResult: (ByteArray?) -> Unit)
}

/**
 * The GATT layout of a room. Fixed UUIDs (random, version 4), shared with the iPhone client (WI-11).
 * Bluetooth pairing is not used (G2): the session seals its own frames.
 */
object BleGattSpec {
    const val SERVICE = "5f1c0001-6b2e-4f7a-9c3d-2a8e6b4d1f07"

    /** Read: the room advert ([BleAdvertCodec]), at most [BleAdvertCodec.MAX_BYTES]. */
    const val ADVERT = "5f1c0002-6b2e-4f7a-9c3d-2a8e6b4d1f07"

    /** Write (with response): packets from a joiner to the host. */
    const val TO_HOST = "5f1c0003-6b2e-4f7a-9c3d-2a8e6b4d1f07"

    /** Notify: packets from the host to one joiner. */
    const val TO_PEER = "5f1c0004-6b2e-4f7a-9c3d-2a8e6b4d1f07"

    /** Client characteristic configuration descriptor (Bluetooth assigned number 0x2902). */
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    /** The MTU a joiner asks for; the stack settles on the smaller of both ends' maximum. */
    const val REQUESTED_MTU = 517
}
