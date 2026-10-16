package cn.wpgd.app.ble

import cn.wpgd.app.room.HostingFailure
import cn.wpgd.app.session.transport.VirtualScheduler
import kotlin.random.Random

/**
 * What the fake air does to packets. [mtu] is the ATT MTU every connection settles on (the ATT
 * payload is mtu - 3). Probabilities apply per packet, from the air's seeded random source.
 */
data class BleFaults(
    val mtu: Int = 185,
    /** Time from one end's send to the other end's receipt, and to the sender's onSent. */
    val latencyMs: Long = 8,
    /** Time from connect to ready (connection, MTU exchange, discovery, subscription). */
    val connectMs: Long = 60,
    val drop: Double = 0.0,
    val duplicate: Double = 0.0,
    val truncate: Double = 0.0,
    /** Extra delay given to a packet, so it can overtake the next one. */
    val reorder: Double = 0.0,
    /** The stack reports a failed write or notification (onSent(false)); the packet is not delivered. */
    val sendFailure: Double = 0.0,
    /** Simultaneous connections a server accepts; more are refused at connect. */
    val maxConnections: Int = 8,
    /** A stalled radio: sends are accepted and never complete. */
    val stall: Boolean = false,
)

/**
 * In-process Bluetooth for loopback tests: every phone's [FakeBleRadio] shares one [FakeAir] on one
 * [VirtualScheduler], so rooms run over the real [BleLink] chunking deterministically. Hosts are
 * found by scanning and their adverts read, as on a phone.
 */
class FakeAir(val scheduler: VirtualScheduler, seed: Long, var faults: BleFaults = BleFaults()) {
    private val random = Random(seed)
    internal val servers = LinkedHashMap<String, FakeServer>()
    private var nextAddress = 1

    /** Packets that left a sender, for assertions. */
    var packetsOnAir = 0L
        private set

    /** Sends attempted while a packet was still in flight on the same port (must stay 0). */
    var overlappingSends = 0L
        internal set

    fun radio(): FakeBleRadio = FakeBleRadio(this, "02:00:00:00:%02X:%02X".format(nextAddress / 256, nextAddress++ % 256))

    /** Every connection of the host at [address] drops (its radio went off). */
    fun cut(address: String) {
        servers[address]?.ports?.toList()?.forEach { it.drop() }
    }

    internal fun chance(p: Double): Boolean = p > 0 && random.nextDouble() < p

    internal fun nextInt(n: Int): Int = random.nextInt(n)

    internal fun countPacket() {
        packetsOnAir++
    }
}

class FakeBleRadio internal constructor(private val air: FakeAir, val address: String) : BleRadio {
    override val stats = BleRadioStats()
    override fun openServer(onPeer: (BlePort) -> Unit): BleServer {
        check(air.servers[address]?.closed != false) { "this radio already serves a room" }
        return FakeServer(air, address, onPeer).also { air.servers[address] = it }
    }

    override fun connect(address: String): BlePort = FakePort(air, address, client = true)

    override fun scanner(): BleScanner = FakeScanner(air)
}

internal class FakeServer(private val air: FakeAir, val address: String, val onPeer: (BlePort) -> Unit) : BleServer {
    var served: ByteArray? = null
    var closed = false
    val ports = LinkedHashSet<FakePort>()
    override val stats = BleServerStats()
    private val failure = HostingFailure()

    override fun onFailure(failed: () -> Unit) = failure.register(failed)

    /** The radio cannot host any more (advertising or the GATT service failed). */
    fun failHosting() {
        stats.advertiseFailures++
        stats.advertising = false
        failure.fail()
    }

    override fun setAdvert(advert: ByteArray?) {
        served = advert?.copyOf()
        stats.advertising = advert != null && !closed
    }

    override fun close() {
        if (closed) return
        closed = true
        served = null
        stats.advertising = false
        ports.toList().forEach { it.drop() }
    }
}

/**
 * One end of a fake GATT connection. A client end connects on [open] and is paired with a server end
 * that the server hands to its onPeer once "subscribed".
 */
internal class FakePort(private val air: FakeAir, override val address: String, private val client: Boolean) : BlePort {
    private var events: BlePortEvents? = null
    var peer: FakePort? = null
    private var ready = false
    private var closed = false
    private var inFlight = false
    private var server: FakeServer? = null

    override fun open(events: BlePortEvents) {
        check(this.events == null) { "port opened twice" }
        this.events = events
        if (!client) {
            ready = true
            air.scheduler.schedule(0) { if (!closed) events.onReady(air.faults.mtu - 3) }
            return
        }
        air.scheduler.schedule(air.faults.connectMs) {
            val s = air.servers[address]
            if (closed) return@schedule
            if (s == null || s.closed || s.ports.size >= air.faults.maxConnections) {
                s?.stats?.let { it.refused++ }
                drop()
                return@schedule
            }
            val hostEnd = FakePort(air, "02:00:00:00:FF:FF", client = false)
            hostEnd.server = s
            hostEnd.peer = this
            peer = hostEnd
            server = s
            s.ports += hostEnd
            s.stats.onConnected(s.ports.size)
            s.stats.subscribed = s.ports.size
            ready = true
            s.onPeer(hostEnd)
            events.onReady(air.faults.mtu - 3)
        }
    }

    override fun send(packet: ByteArray): Boolean {
        if (!ready || closed) return false
        if (inFlight) {
            air.overlappingSends++
            return false
        }
        require(packet.size <= air.faults.mtu - 3) { "packet of ${packet.size} bytes above ATT payload ${air.faults.mtu - 3}" }
        inFlight = true
        val f = air.faults
        if (f.stall) return true
        val failed = air.chance(f.sendFailure)
        if (!failed) {
            air.countPacket()
            val target = peer
            val copies = if (air.chance(f.duplicate)) 2 else 1
            if (target != null && !air.chance(f.drop)) {
                repeat(copies) {
                    val bytes = if (air.chance(f.truncate)) packet.copyOf(air.nextInt(packet.size)) else packet.copyOf()
                    val delay = f.latencyMs + if (air.chance(f.reorder)) f.latencyMs * 3 else 0
                    air.scheduler.schedule(delay) { target.receive(bytes) }
                }
            }
        }
        air.scheduler.schedule(f.latencyMs) {
            if (closed) return@schedule
            inFlight = false
            events?.onSent(!failed)
        }
        return true
    }

    private fun receive(bytes: ByteArray) {
        if (!closed) events?.onPacket(bytes)
    }

    override fun close() {
        if (closed) return
        drop()
    }

    /** Both ends see the disconnection, a little later. */
    fun drop() {
        if (closed) return
        closed = true
        server?.let {
            it.ports -= this
            it.stats.connected = it.ports.size
            it.stats.subscribed = it.ports.size
        }
        peer?.server?.let {
            it.ports -= peer!!
            it.stats.connected = it.ports.size
            it.stats.subscribed = it.ports.size
        }
        air.scheduler.schedule(air.faults.latencyMs) { events?.onDisconnected() }
        peer?.let { p -> air.scheduler.schedule(air.faults.latencyMs) { p.drop() } }
    }
}

internal class FakeScanner(private val air: FakeAir) : BleScanner {
    private var running = false
    private var generation = 0

    override fun start(onSeen: (address: String) -> Unit) {
        running = true
        val gen = ++generation
        fun sweep() {
            if (!running || gen != generation) return
            air.servers.values.filter { !it.closed && it.served != null }.forEach { onSeen(it.address) }
            air.scheduler.schedule(SCAN_INTERVAL_MS) { sweep() }
        }
        air.scheduler.schedule(air.faults.latencyMs) { sweep() }
    }

    override fun stop() {
        running = false
        generation++
    }

    override fun readAdvert(address: String, onResult: (ByteArray?) -> Unit) {
        air.scheduler.schedule(air.faults.connectMs) {
            val s = air.servers[address]
            onResult(s?.takeIf { !it.closed }?.served?.copyOf())
        }
    }

    companion object {
        const val SCAN_INTERVAL_MS = 500L
    }
}
