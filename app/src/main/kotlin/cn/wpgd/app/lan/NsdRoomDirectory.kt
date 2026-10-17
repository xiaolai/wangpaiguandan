package cn.wpgd.app.lan

import android.content.Context
import android.util.Log
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import cn.wpgd.app.room.AdvertCodec
import cn.wpgd.app.room.RoomAdvert
import cn.wpgd.app.room.RoomBrowser
import cn.wpgd.app.room.RoomDirectory
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Room discovery on the local network through Android's network service discovery (DNS-SD over
 * mDNS, GDN-009, GDN-023). Needs no runtime permission; the sockets behind it need INTERNET only.
 *
 * A hosted room registers one service named by its room id, with its [RoomAdvert] in the TXT record
 * ([AdvertCodec]); re-announcing replaces the registration. A browser resolves each found service
 * (one at a time: older Android versions refuse concurrent resolves) and reports only adverts that
 * decode within bounds, with the address the resolve returned — never one taken from the TXT record.
 *
 * Discovery reports a service name once, so a re-announcement under the same name (new open seats,
 * or a restored table listening on a new port) is not reported again. While browsing, every room
 * already found is therefore resolved again every [REFRESH_MS]; a changed advert is reported.
 */
class NsdRoomDirectory(context: Context) : RoomDirectory {
    private val nsd = context.getSystemService(NsdManager::class.java) ?: error("network service discovery is unavailable")
    private val registrations = NsdRegistrations<NsdManager.RegistrationListener> { nsd.unregisterService(it) }

    @Synchronized
    override fun announce(advert: RoomAdvert, onFailed: () -> Unit) {
        val endpoint = advert.endpoint
        require(endpoint is LanEndpoint) { "NSD announces LAN rooms only" }
        registrations.withdraw(advert.roomId)
        val info = NsdServiceInfo().apply {
            serviceName = advert.roomId
            serviceType = AdvertCodec.SERVICE_TYPE
            port = endpoint.port
            AdvertCodec.encode(advert).forEach { (k, v) -> setAttribute(k, v) }
        }
        // Android may register under another name on a conflict ("<room id> (2)"); browsers accept
        // that form ([serviceNameMatchesRoom]), so nothing needs re-announcing here.
        val roomId = advert.roomId
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                val current = synchronized(this@NsdRoomDirectory) { registrations.registrationFailed(roomId, this) }
                // Only the room's current registration speaks for it: a replaced one failing changes nothing.
                if (current) onFailed()
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) =
                synchronized(this@NsdRoomDirectory) { registrations.unregistered(this) }
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {
                synchronized(this@NsdRoomDirectory) { registrations.unregistrationFailed(this) }
                Log.w(TAG, "withdrawing room $roomId failed (error $error); the platform no longer accepts a retry")
            }
        }
        registrations.put(roomId, listener)
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    override fun withdraw(roomId: String) = registrations.withdraw(roomId)

    override fun browser(): RoomBrowser = Browser()

    private inner class Browser : RoomBrowser {
        private val found = LinkedHashMap<String, RoomAdvert>()
        /** The service as discovery reported it, kept to resolve it again ([refresh]). */
        private val services = HashMap<String, NsdServiceInfo>()
        private val queue = ArrayDeque<NsdServiceInfo>()
        private var refresher: ScheduledExecutorService? = null
        private var resolving = false
        private var stopped = false
        private var onRooms: ((List<RoomAdvert>) -> Unit)? = null

        private val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            // The platform drops the listener on this failure, so the same listener may start again.
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = discoveryFailed(errorCode)
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(this@Browser) {
                    if (stopped) return
                    if (services.size < MAX_QUEUED || info.serviceName in services) services[info.serviceName] = info
                    enqueue(info)
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val list = synchronized(this@Browser) {
                    services.remove(info.serviceName)
                    if (found.remove(info.serviceName) == null) return
                    found.values.toList()
                }
                onRooms?.invoke(list)
            }
        }

        override fun start(onRooms: (List<RoomAdvert>) -> Unit) {
            this.onRooms = onRooms
            onRooms(emptyList())
            // The timer exists before discovery starts: a failed start is retried on it.
            val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "wpgd-nsd-refresh").also { it.isDaemon = true } }
            synchronized(this) {
                if (stopped) return timer.shutdown()
                refresher = timer
            }
            startDiscovery()
            timer.scheduleWithFixedDelay(::refresh, REFRESH_MS, REFRESH_MS, TimeUnit.MILLISECONDS)
        }

        private val starts = DiscoveryStarts()

        /** Registering and stopping discovery, serialized: a start that loses the race to [stop] registers nothing. */
        private val registration = DiscoveryRegistration(
            register = { nsd.discoverServices(AdvertCodec.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) },
            unregister = { nsd.stopServiceDiscovery(discovery) },
        )

        private fun startDiscovery() {
            synchronized(this) {
                if (stopped) return
                starts.started()
            }
            try {
                registration.start()
            } catch (_: IllegalArgumentException) {
                // The platform still holds this listener (a stop not yet processed): retry like a failed start.
                discoveryFailed(-1)
            }
        }

        /** A failed start is retried a few times, later each time; then it is logged and the finder stays empty. */
        private fun discoveryFailed(errorCode: Int) {
            val retry = synchronized(this) {
                if (stopped) return
                val delay = starts.failed()
                val timer = refresher
                if (delay == null || timer == null) null else timer to delay
            }
            if (retry == null) {
                Log.w(TAG, "room discovery could not start (error $errorCode) after ${starts.attempts} attempts")
                return
            }
            try {
                retry.first.schedule(::startDiscovery, retry.second, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // Stopped meanwhile.
            }
        }

        override fun stop() {
            val timer = synchronized(this) {
                if (stopped) return
                stopped = true
                queue.clear()
                refresher.also { refresher = null }
            }
            timer?.shutdownNow()
            registration.stop()
        }

        /** Resolves every service found so far again, so a re-announced advert is seen. */
        private fun refresh() {
            synchronized(this) {
                if (stopped) return
                services.values.toList().forEach(::enqueue)
            }
        }

        /** Queues [info] for resolving unless it is queued already (callers hold the lock). */
        private fun enqueue(info: NsdServiceInfo) {
            if (queue.size >= MAX_QUEUED || queue.any { it.serviceName == info.serviceName }) return
            queue.addLast(info)
            resolveNext()
        }

        /** Resolves queued services one at a time (callers hold the lock). */
        private fun resolveNext() {
            if (resolving || stopped) return
            val next = queue.pollFirst() ?: return
            resolving = true
            @Suppress("DEPRECATION") // resolveService is the API available from minSdk 26 on.
            nsd.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done(null)

                    override fun onServiceResolved(info: NsdServiceInfo) = done(info)
                },
            )
        }

        private fun done(info: NsdServiceInfo?) {
            val list = synchronized(this) {
                resolving = false
                val advert = info?.let(::decode)
                // Only a service still known (not lost meanwhile) is reported, and only when it changed.
                val changed = advert != null && !stopped && info.serviceName in services && found.put(info.serviceName, advert) != advert
                resolveNext()
                if (!changed) return
                found.values.toList()
            }
            onRooms?.invoke(list)
        }

        private fun decode(info: NsdServiceInfo): RoomAdvert? {
            @Suppress("DEPRECATION") // host: the resolved address (getHostAddresses needs API 34).
            val host = info.host?.hostAddress ?: return null
            val endpoint = runCatching { LanEndpoint(host, info.port) }.getOrNull() ?: return null
            val raw = info.attributes ?: return null
            if (raw.size > MAX_ATTRIBUTES) return null
            val attrs = raw.mapNotNull { (k, v) -> v?.takeIf { it.size <= MAX_VALUE_BYTES }?.let { k to it.decodeToString() } }.toMap()
            // The service name is the room id; an advert claiming another id is dropped.
            return AdvertCodec.decode(attrs, endpoint)?.takeIf { serviceNameMatchesRoom(info.serviceName, it.roomId) }
        }
    }

    private companion object {
        const val TAG = "wpgd-nsd"
        const val MAX_QUEUED = 32
        const val MAX_ATTRIBUTES = 16
        const val MAX_VALUE_BYTES = 255
        const val REFRESH_MS = 3_000L
    }
}

/**
 * Whether a discovered service named [serviceName] may carry the advert of [roomId]: its own name, or
 * the name Android gives on a registration conflict, "<room id> (N)" (for instance while the previous
 * registration of a re-announced room is still being withdrawn). Any other name is a forgery.
 */
internal fun serviceNameMatchesRoom(serviceName: String, roomId: String): Boolean {
    if (serviceName == roomId) return true
    val rest = serviceName.removePrefix(roomId)
    return rest.length != serviceName.length && RENAMED_SUFFIX.matches(rest)
}

private val RENAMED_SUFFIX = Regex(" \\([1-9][0-9]{0,3}\\)")

/**
 * Starting and stopping one discovery listener, serialized (round 3 #9). [start] registers unless
 * [stop] ran; [stop] runs once and unregisters, so a registration that won the race is undone and one
 * that lost it never happens. [unregister] failing (nothing registered: a start that failed) is ignored.
 * The platform calls are asynchronous (results arrive on another thread), so holding the lock across
 * them cannot deadlock with the listener's callbacks.
 */
internal class DiscoveryRegistration(private val register: () -> Unit, private val unregister: () -> Unit) {
    private var stopped = false

    /** False when already stopped. What [register] throws propagates. */
    @Synchronized
    fun start(): Boolean {
        if (stopped) return false
        register()
        return true
    }

    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        runCatching(unregister)
    }
}

/**
 * Attempts to start discovery (not thread-safe: the browser's lock serializes calls). A failed start
 * is retried at most [maxAttempts] times in all, waiting [baseDelayMs], then twice as long each time.
 */
internal class DiscoveryStarts(private val maxAttempts: Int = 4, private val baseDelayMs: Long = 1_000) {
    init {
        require(maxAttempts >= 1 && baseDelayMs > 0)
    }

    var attempts: Int = 0
        private set

    fun started() {
        attempts++
    }

    /** The latest start failed: the delay before the next attempt, or null to give up. */
    fun failed(): Long? = if (attempts >= maxAttempts) null else baseDelayMs shl (attempts - 1).coerceIn(0, 16)
}

/**
 * Room registrations and their withdrawal (not thread-safe: the directory's lock serializes calls).
 * A registration stays tracked until the platform confirms it is gone: an unregistration that throws
 * is retried on the next announce or withdraw, never forgotten while the advert may live on. One the
 * platform reports failed later ([unregistrationFailed]) cannot be retried — Android forgets the
 * listener before that callback, and a retry would only be refused — so it is counted in [stranded].
 */
internal class NsdRegistrations<L : Any>(private val unregister: (L) -> Unit) {
    private val active = HashMap<String, L>()
    private val inFlight = HashSet<L>()
    private val failed = LinkedHashSet<L>()

    /** Registrations not yet confirmed withdrawn (in flight or awaiting a retry). */
    val withdrawing: Int get() = inFlight.size + failed.size

    /** Unregistrations the platform reported failed: their adverts may live on, and nothing can withdraw them. */
    var stranded: Int = 0
        private set

    fun put(roomId: String, listener: L) {
        retryFailed()
        check(active.put(roomId, listener) == null) { "room $roomId registered twice" }
    }

    fun withdraw(roomId: String) {
        retryFailed()
        active.remove(roomId)?.let(::attempt)
    }

    /** True when [listener] was [roomId]'s current registration (now forgotten): the room is no longer advertised. */
    fun registrationFailed(roomId: String, listener: L): Boolean {
        if (active[roomId] !== listener) return false
        active.remove(roomId)
        return true
    }

    fun unregistered(listener: L) {
        inFlight -= listener
        failed -= listener
    }

    fun unregistrationFailed(listener: L) {
        if (inFlight.remove(listener)) stranded++
    }

    private fun retryFailed() {
        val again = failed.toList()
        failed.clear()
        again.forEach(::attempt)
    }

    private fun attempt(listener: L) {
        try {
            unregister(listener)
            inFlight += listener
        } catch (_: IllegalArgumentException) {
            // The platform does not know this listener: its registration failed, so nothing is advertised.
        } catch (_: RuntimeException) {
            failed += listener
        }
    }
}
