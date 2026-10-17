package cn.wpgd.app.room

import cn.wpgd.app.lan.LanEndpoint
import cn.wpgd.app.lan.LanLimits
import cn.wpgd.app.lan.LanServer
import cn.wpgd.app.lan.SocketTransport
import cn.wpgd.app.session.transport.Transport
import java.net.InetAddress

/** How phones reach each other for a room: the same Wi-Fi (WI-09) or Bluetooth (WI-10). */
enum class RoomMedium { LAN, BLUETOOTH }

/**
 * Where a room is reached: a LAN address and port ([LanEndpoint]) or a Bluetooth device
 * ([cn.wpgd.app.ble.BleEndpoint]). Comes from discovery, never from the advert's own claims.
 */
interface RoomEndpoint {
    val medium: RoomMedium
}

/** A room's open listening point. [close] stops accepting and closes every peer connection. */
interface RoomListener {
    val endpoint: RoomEndpoint
    fun close()

    /**
     * Registers [failed], called at most once, on any thread, if hosting stops working after [listen]
     * returned: nearby devices can no longer find or reach the room (Bluetooth: the GATT server,
     * its service or advertising failed; LAN: the listening socket stopped accepting). Called at once
     * if that already happened. Startup failures throw from [RoomNetwork.listen] instead.
     */
    fun onFailure(failed: () -> Unit) = Unit
}

/** A one-shot hosting failure ([RoomListener.onFailure]): [register] after [fail] still hears it. Thread-safe. */
internal class HostingFailure {
    private var failed = false
    private val listeners = ArrayList<() -> Unit>()

    fun register(listener: () -> Unit) {
        val now = synchronized(this) { failed.also { if (!it) listeners += listener } }
        if (now) listener()
    }

    fun fail() {
        val toCall = synchronized(this) {
            if (failed) return
            failed = true
            listeners.toList().also { listeners.clear() }
        }
        toCall.forEach { it() }
    }
}

/**
 * Announces hosted rooms to nearby devices (Android: NSD). Re-announcing a room id replaces its advert.
 * Thread-safe: an owner whose loop has stalled withdraws its room from another thread.
 */
interface RoomAnnouncer {
    /**
     * Announces [advert]. [onFailed] is called at most once, on any thread, if the announcement
     * fails after this returned and the room cannot be found (LAN: the NSD registration failed);
     * not for an announcement that a newer one of the same room replaced.
     */
    fun announce(advert: RoomAdvert, onFailed: () -> Unit = {})
    fun withdraw(roomId: String)
}

/** Finds announced rooms. [start] reports the full current list on every change, on any thread. */
interface RoomBrowser {
    fun start(onRooms: (List<RoomAdvert>) -> Unit)
    fun stop()
}

/**
 * How rooms reach each other: the LAN adapter ([LanRoomNetwork]), the Bluetooth adapter
 * ([cn.wpgd.app.ble.BleRoomNetwork], WI-10), an in-memory network in tests. Every callback a
 * transport makes is handed to the `post` function given here, which runs it on the owner's session
 * loop. Solo play never touches a [RoomNetwork] (GDN-005).
 */
interface RoomNetwork {
    /** The one medium this network uses; it reaches only endpoints of that medium. */
    val medium: RoomMedium

    /** Starts accepting peers for a hosted room; each one is handed to [onPeer] through [post]. */
    fun listen(post: (() -> Unit) -> Unit, onPeer: (Transport) -> Unit): RoomListener

    /**
     * Opens a connection to a room; failure arrives as the transport closing. [endpoint] must be of
     * this network's [medium] (a programming error otherwise).
     */
    fun connect(endpoint: RoomEndpoint, post: (() -> Unit) -> Unit): Transport

    val announcer: RoomAnnouncer

    fun browser(): RoomBrowser
}

/**
 * Rooms over TCP on the local network (GDN-021, GDN-023): [LanServer] and [SocketTransport] below
 * the unchanged session protocol, with discovery from [directory] (NSD on Android).
 * [bindAddress] null listens on every interface; tests bind to loopback.
 */
class LanRoomNetwork(
    private val directory: RoomDirectory,
    private val limits: LanLimits = LanLimits(),
    private val bindAddress: InetAddress? = null,
) : RoomNetwork {
    override val medium = RoomMedium.LAN

    override fun listen(post: (() -> Unit) -> Unit, onPeer: (Transport) -> Unit): RoomListener {
        val server = LanServer(post, onPeer, limits, bindAddress = bindAddress)
        val host = bindAddress?.hostAddress ?: "0.0.0.0"
        return object : RoomListener {
            override val endpoint = LanEndpoint(host, server.port)
            override fun close() = server.stop()

            // The server stopped accepting newcomers (not by close): the owner shows hosting failed.
            override fun onFailure(failed: () -> Unit) = server.onFailure(failed)
        }
    }

    override fun connect(endpoint: RoomEndpoint, post: (() -> Unit) -> Unit): Transport {
        require(endpoint is LanEndpoint) { "the LAN network cannot reach a ${endpoint.medium} endpoint" }
        return SocketTransport.connect(endpoint, post, limits)
    }

    override val announcer: RoomAnnouncer get() = directory

    override fun browser(): RoomBrowser = directory.browser()
}

/** Announcing and browsing through one discovery mechanism. */
interface RoomDirectory : RoomAnnouncer {
    fun browser(): RoomBrowser
}

/**
 * A directory inside one process: every announcer and browser made from it sees the same rooms.
 * Used by tests and by the on-device loopback tests (real sockets, no multicast).
 */
class InProcessDirectory : RoomDirectory {
    private val rooms = LinkedHashMap<String, RoomAdvert>()
    private val browsers = LinkedHashSet<(List<RoomAdvert>) -> Unit>()

    /**
     * Held while a snapshot is taken and delivered, so browsers receive snapshots in the order they
     * were taken: a newer list never arrives before an older one. Always taken before the state lock,
     * and callbacks run without the state lock held. A callback that changes the directory (the
     * monitor is reentrant) only queues its snapshot: the delivery in progress finishes first.
     */
    private val delivery = Any()

    /** Snapshots taken but not yet delivered, oldest first. Guarded by [delivery]. */
    private val pending = ArrayDeque<Pair<List<RoomAdvert>, List<(List<RoomAdvert>) -> Unit>>>()
    private var delivering = false

    override fun announce(advert: RoomAdvert, onFailed: () -> Unit) = changed { rooms[advert.roomId] = advert }

    override fun withdraw(roomId: String) = changed { rooms.remove(roomId) }

    val announced: List<RoomAdvert> @Synchronized get() = rooms.values.toList()

    override fun browser(): RoomBrowser = object : RoomBrowser {
        private var callback: ((List<RoomAdvert>) -> Unit)? = null

        override fun start(onRooms: (List<RoomAdvert>) -> Unit) = synchronized(delivery) {
            val list = synchronized(this@InProcessDirectory) {
                callback = onRooms
                browsers += onRooms
                rooms.values.toList()
            }
            deliver(list, listOf(onRooms))
        }

        override fun stop() {
            synchronized(this@InProcessDirectory) { callback?.let { browsers.remove(it) } }
        }
    }

    private fun changed(block: () -> Unit) = synchronized(delivery) {
        val (list, targets) = synchronized(this) {
            block()
            rooms.values.toList() to browsers.toList()
        }
        deliver(list, targets)
    }

    /** Called with [delivery] held. Queues the snapshot; unless a delivery is already running, delivers the queue in order. */
    private fun deliver(list: List<RoomAdvert>, targets: List<(List<RoomAdvert>) -> Unit>) {
        pending.addLast(list to targets)
        if (delivering) return
        delivering = true
        try {
            while (true) {
                val (next, to) = pending.removeFirstOrNull() ?: break
                to.forEach { it(next) }
            }
        } finally {
            delivering = false
        }
    }
}
