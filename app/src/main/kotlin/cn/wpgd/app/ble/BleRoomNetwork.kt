package cn.wpgd.app.ble

import cn.wpgd.app.room.AdvertCodec
import cn.wpgd.app.room.RoomAdvert
import cn.wpgd.app.room.RoomAnnouncer
import cn.wpgd.app.room.RoomBrowser
import cn.wpgd.app.room.RoomEndpoint
import cn.wpgd.app.room.RoomListener
import cn.wpgd.app.room.RoomMedium
import cn.wpgd.app.room.RoomNetwork
import cn.wpgd.app.session.transport.Cancellable
import cn.wpgd.app.session.transport.Scheduler
import cn.wpgd.app.session.transport.Transport
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** A Bluetooth host as discovery found it: its device address (possibly a random, rotating one). */
data class BleEndpoint(val address: String) : RoomEndpoint {
    override val medium: RoomMedium get() = RoomMedium.BLUETOOTH

    init {
        require(ADDRESS.matches(address)) { "bad Bluetooth address" }
    }

    companion object {
        private val ADDRESS = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")

        /** A hosted room's own listener: joiners learn the real address from their scan, never from here. */
        val LOCAL = BleEndpoint("00:00:00:00:00:00")
    }
}

/**
 * The room advert as the bytes of the GATT advert characteristic. The radio advertisement itself
 * carries only the service UUID (an iPhone host can advertise no more), so a joiner reads this after
 * finding the service. Same attributes and the same strict decoding as the LAN TXT record
 * ([AdvertCodec]):
 *
 * ```
 * u8 version (1)  u8 entries (<= 16)  then per entry: u8 keyLength (1..8), key (ASCII),
 * u8 valueLength, value (UTF-8)
 * ```
 */
object BleAdvertCodec {
    const val VERSION = 1
    const val MAX_BYTES = 512
    private const val MAX_ENTRIES = 16
    private const val MAX_KEY = 8

    fun encode(advert: RoomAdvert): ByteArray {
        val attrs = AdvertCodec.encode(advert)
        val out = java.io.ByteArrayOutputStream()
        out.write(VERSION)
        out.write(attrs.size)
        for ((k, v) in attrs) {
            val key = k.encodeToByteArray()
            val value = v.encodeToByteArray()
            check(key.size in 1..MAX_KEY && value.size <= 255) { "advert attribute $k does not fit" }
            out.write(key.size)
            out.write(key)
            out.write(value.size)
            out.write(value)
        }
        return out.toByteArray().also { check(it.size <= MAX_BYTES) { "advert of ${it.size} bytes" } }
    }

    /** The advert, or null when anything is malformed or out of bounds. Never throws on input. */
    fun decode(bytes: ByteArray, endpoint: BleEndpoint): RoomAdvert? {
        if (bytes.size < 2 || bytes.size > MAX_BYTES) return null
        if (bytes[0].toInt() and 0xFF != VERSION) return null
        val n = bytes[1].toInt() and 0xFF
        if (n > MAX_ENTRIES) return null
        val attrs = LinkedHashMap<String, String>()
        var p = 2
        repeat(n) {
            if (p >= bytes.size) return null
            val kl = bytes[p++].toInt() and 0xFF
            if (kl !in 1..MAX_KEY || p + kl >= bytes.size) return null
            val key = utf8(bytes, p, kl) ?: return null
            p += kl
            val vl = bytes[p++].toInt() and 0xFF
            if (p + vl > bytes.size) return null
            val value = utf8(bytes, p, vl) ?: return null
            p += vl
            if (key.any { it.code !in 0x21..0x7E } || attrs.put(key, value) != null) return null
        }
        if (p != bytes.size) return null
        return AdvertCodec.decode(attrs, endpoint)
    }

    private fun utf8(bytes: ByteArray, from: Int, length: Int): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, from, length))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

/** Live links and server counters of a Bluetooth room network, for diagnostics and the device spike. */
class BleNetworkStats(
    val server: BleServerStats?,
    /** Recent links (the last 16, open or closed), oldest first, with their address for tests only. */
    val links: List<Pair<String, BleLinkMetrics>>,
    val linksOpened: Long,
    val radio: BleRadioStats,
)

/**
 * Rooms over Bluetooth LE (WI-10): GATT links ([BleLink]) below the unchanged session protocol, the
 * room advert on a readable characteristic, discovery by scanning for the room service and reading
 * each host's advert ([BleRoomBrowser]). The same [RoomNetwork] contract as the LAN adapter; plain
 * Kotlin over a [BleRadio], so it runs in JVM tests over a fake radio and on Android over GATT.
 *
 * One hosted room at a time per device. [scheduler] runs link timers (linger) and browser timers;
 * it must accept calls from any thread.
 */
class BleRoomNetwork(
    private val radio: BleRadio,
    private val scheduler: Scheduler,
    private val limits: BleLimits = BleLimits(),
    private val browserConfig: BleBrowserConfig = BleBrowserConfig(),
) : RoomNetwork, RoomAnnouncer {
    override val medium = RoomMedium.BLUETOOTH

    private var server: BleServer? = null
    private var announcedRoom: String? = null
    private val recent = ArrayDeque<Pair<String, BleLink>>()
    private var opened = 0L

    @Synchronized
    override fun listen(post: (() -> Unit) -> Unit, onPeer: (Transport) -> Unit): RoomListener {
        check(server == null) { "a Bluetooth room is already being served on this device" }
        return Listener(post, onPeer)
    }

    /** A hosted room's listener. Tracks only live links, so reconnects cost no memory over time. */
    internal inner class Listener(post: (() -> Unit) -> Unit, onPeer: (Transport) -> Unit) : RoomListener {
        private var stopped = false
        private val links = LinkedHashSet<BleLink>()
        private val opened = radio.openServer { port ->
            val link = track(BleLink(port, post, scheduler, limits))
            val keep = synchronized(this@BleRoomNetwork) {
                if (stopped) {
                    false
                } else {
                    links.removeAll { it.disposed }
                    links.add(link)
                }
            }
            post {
                // Checked again when the loop runs this: the listener may have closed (and closed this
                // link) after the accept was queued; a disposed link is never handed to the room.
                val live = keep && synchronized(this@BleRoomNetwork) { !stopped } && !link.disposed
                if (live) onPeer(link) else link.close()
            }
        }

        init {
            server = opened
        }

        /** Links this listener still tracks (live ones, plus any disposed since the last accept). */
        internal val trackedLinks: Int get() = synchronized(this@BleRoomNetwork) { links.size }

        override val endpoint: RoomEndpoint = BleEndpoint.LOCAL

        override fun onFailure(failed: () -> Unit) = opened.onFailure(failed)

        override fun close() {
            val all = synchronized(this@BleRoomNetwork) {
                if (stopped) return
                stopped = true
                if (server === opened) {
                    server = null
                    announcedRoom = null
                }
                links.toList()
            }
            all.forEach { it.close() }
            opened.close()
        }
    }

    override fun connect(endpoint: RoomEndpoint, post: (() -> Unit) -> Unit): Transport {
        require(endpoint is BleEndpoint) { "the Bluetooth network cannot reach a ${endpoint.medium} endpoint" }
        return track(BleLink(radio.connect(endpoint.address), post, scheduler, limits))
    }

    override val announcer: RoomAnnouncer get() = this

    /** Bluetooth hosting failures reach the room's listener ([RoomListener.onFailure]), not [onFailed]. */
    @Synchronized
    override fun announce(advert: RoomAdvert, onFailed: () -> Unit) {
        val s = checkNotNull(server) { "announce before listen" }
        announcedRoom = advert.roomId
        s.setAdvert(BleAdvertCodec.encode(advert))
    }

    @Synchronized
    override fun withdraw(roomId: String) {
        if (announcedRoom != roomId) return
        announcedRoom = null
        server?.setAdvert(null)
    }

    override fun browser(): RoomBrowser = BleRoomBrowser(radio.scanner(), scheduler, browserConfig)

    @Synchronized
    private fun track(link: BleLink): BleLink {
        opened++
        recent.addLast(link.address to link)
        while (recent.size > RECENT_LINKS) recent.removeFirst()
        return link
    }

    /** What the device spike reads: the server's connection counts and every recent link's metrics. */
    @Synchronized
    fun stats(): BleNetworkStats = BleNetworkStats(server?.stats, recent.map { (a, l) -> a to l.metrics }, opened, radio.stats)

    /** True while a room is served or a link is open (the diagnostics report runs only then). */
    @Synchronized
    fun active(): Boolean = server != null || recent.any { !it.second.disposed }

    private companion object {
        const val RECENT_LINKS = 16
    }
}

/** Discovery timing (C1–C3 over Bluetooth). */
data class BleBrowserConfig(
    /** An advert is read again when its host is seen and the last read is older than this. */
    val refreshMs: Long = 10_000,
    /** A host not seen in a scan for this long is dropped from the list. */
    val lostMs: Long = 15_000,
    /** Hosts tracked at once; more are ignored (a crowd costs bounded memory and connections). */
    val maxHosts: Int = 16,
    /** A host whose last read gave no advert (failed or malformed) is read again after this long. */
    val retryMs: Long = 2_000,
) {
    init {
        require(refreshMs > 0 && lostMs > 0 && maxHosts >= 1 && retryMs > 0)
    }
}

/**
 * Finds Bluetooth rooms: scans for the room service and reads each host's advert, one host at a time
 * (one extra connection each, never in parallel). Reports only adverts that decode within bounds,
 * with the address the scan saw. One room id at two addresses (a host whose random address rotated)
 * keeps the newest. Scanner callbacks may arrive on any thread.
 */
class BleRoomBrowser(
    private val scanner: BleScanner,
    private val scheduler: Scheduler,
    private val config: BleBrowserConfig = BleBrowserConfig(),
) : RoomBrowser {
    private class Host(var lastSeen: Long) {
        var advert: RoomAdvert? = null

        /** When the last read completed; null before the first one. */
        var readAt: Long? = null
    }

    private val hosts = LinkedHashMap<String, Host>()
    private val queue = ArrayDeque<String>()
    private var reading: String? = null
    private var stopped = false
    private var onRooms: ((List<RoomAdvert>) -> Unit)? = null
    private var sweep: Cancellable? = null

    /**
     * Snapshots are taken under this object's lock and delivered outside it, from radio and scheduler
     * threads. Each one is numbered when taken; [delivery] serializes the deliveries and drops a snapshot
     * older than one already delivered, so a slow older read can never restore rooms a newer sweep removed.
     */
    private val delivery = Any()
    private var taken = 0L // guarded by this
    private var delivered = 0L // guarded by delivery

    private class Snapshot(val number: Long, val rooms: List<RoomAdvert>)

    /** Advert reads started (each one a short GATT connection), for diagnostics. */
    @Volatile var reads: Long = 0
        private set

    override fun start(onRooms: (List<RoomAdvert>) -> Unit) {
        synchronized(this) {
            check(this.onRooms == null) { "browser already started" }
            this.onRooms = onRooms
            scheduleSweep()
            Snapshot(++taken, emptyList())
        }.let(::deliver)
        if (synchronized(this) { stopped }) return
        scanner.start { address -> seen(address) }
        // A stop() during the first callback or racing this start ran its scanner.stop() before the
        // scanner started: stop it again now, after the start (scanner calls are ordered and idempotent).
        if (synchronized(this) { stopped }) scanner.stop()
    }

    override fun stop() {
        synchronized(this) {
            if (stopped) return
            stopped = true
            queue.clear()
            sweep?.cancel()
        }
        scanner.stop()
    }

    private fun seen(address: String) {
        val endpoint = runCatching { BleEndpoint(address) }.getOrNull() ?: return
        synchronized(this) {
            if (stopped) return
            val now = scheduler.now
            val host = hosts[endpoint.address] ?: run {
                if (hosts.size >= config.maxHosts) return
                Host(now).also { hosts[endpoint.address] = it }
            }
            host.lastSeen = now
            val last = host.readAt
            // Every read is a GATT connection: one that gave no advert waits [retryMs], not zero.
            val stale = last == null || now - last >= if (host.advert == null) config.retryMs else config.refreshMs
            if (stale && reading != endpoint.address && endpoint.address !in queue) queue.addLast(endpoint.address)
            readNextLocked()
        }
    }

    /** Starts the next advert read (callers hold the lock). */
    private fun readNextLocked() {
        if (reading != null || stopped) return
        val next = queue.removeFirstOrNull() ?: return
        reading = next
        reads++
        scanner.readAdvert(next) { bytes -> read(next, bytes) }
    }

    private fun read(address: String, bytes: ByteArray?) {
        val list = synchronized(this) {
            if (reading == address) reading = null
            if (stopped) return
            val host = hosts[address]
            val advert = bytes?.let { BleAdvertCodec.decode(it, BleEndpoint(address)) }
            val changed = if (host != null) {
                host.readAt = scheduler.now
                val before = host.advert
                if (advert != null) {
                    // The same room at an older address is the same host after an address rotation.
                    hosts.entries.filter { it.key != address && it.value.advert?.roomId == advert.roomId }.forEach { it.value.advert = null }
                    host.advert = advert
                } else if (bytes != null) {
                    host.advert = null // the host now serves something malformed: drop it
                }
                before != host.advert
            } else {
                false
            }
            readNextLocked()
            if (!changed) return
            currentLocked()
        }
        deliver(list)
    }

    private fun scheduleSweep() {
        sweep = scheduler.schedule(SWEEP_MS) {
            val list = synchronized(this) {
                if (stopped) return@schedule
                val now = scheduler.now
                val lost = hosts.filterValues { now - it.lastSeen > config.lostMs }.keys
                val hadRoom = lost.any { hosts[it]?.advert != null }
                lost.forEach { hosts.remove(it); queue.remove(it) }
                scheduleSweep()
                if (!hadRoom) return@schedule
                currentLocked()
            }
            deliver(list)
        }
    }

    private fun currentLocked(): Snapshot = Snapshot(++taken, hosts.values.mapNotNull { it.advert })

    private fun deliver(snapshot: Snapshot) {
        synchronized(delivery) {
            if (snapshot.number <= delivered) return
            delivered = snapshot.number
            onRooms?.invoke(snapshot.rooms)
        }
    }

    private companion object {
        const val SWEEP_MS = 1_000L
    }
}
