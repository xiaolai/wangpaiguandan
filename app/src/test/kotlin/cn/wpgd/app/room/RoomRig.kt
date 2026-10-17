package cn.wpgd.app.room

import cn.wpgd.app.BlockingWorkerDecisionExecutor
import cn.wpgd.app.TestProfiles
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.HistoryRepository
import cn.wpgd.app.data.IdentityRepository
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.WallClock
import cn.wpgd.app.lan.LanEndpoint
import cn.wpgd.app.nickname
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.owner.OwnerDependencies
import cn.wpgd.app.owner.OwnerState
import cn.wpgd.app.owner.RoomConfig
import cn.wpgd.app.owner.SessionOwner
import cn.wpgd.app.owner.TableSnapshot
import cn.wpgd.app.owner.VirtualSessionLoop
import cn.wpgd.app.session.storage.InMemoryStorage
import cn.wpgd.app.session.storage.Storage
import cn.wpgd.app.session.transport.InMemoryLink
import cn.wpgd.app.session.transport.InMemoryNetwork
import cn.wpgd.app.session.transport.Scheduler
import cn.wpgd.app.session.transport.Transport
import cn.wpgd.app.session.transport.VirtualScheduler
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.testCrypto
import org.junit.Assert.assertTrue

/**
 * Rooms over the session's seeded in-memory network instead of sockets, so several owners (one per
 * "phone") run deterministically on one virtual clock. Same [RoomNetwork] contract as the LAN adapter.
 */
class InMemoryRoomNetwork(scheduler: Scheduler, seed: Long, val directory: InProcessDirectory = InProcessDirectory()) : RoomNetwork {
    override val medium = RoomMedium.LAN

    private val network = InMemoryNetwork(scheduler, seed).apply { captureEnabled = false }
    private val listeners = HashMap<Int, (Transport) -> Unit>()
    private val links = HashMap<Int, MutableList<InMemoryLink>>()
    private val failures = HashMap<Int, HostingFailure>()
    private var nextPort = 40_000

    /** Hosting at [endpoint] stops working (as a Bluetooth host whose advertising failed). */
    fun failHosting(endpoint: RoomEndpoint) = checkNotNull(failures[(endpoint as LanEndpoint).port]).fail()

    override fun listen(post: (() -> Unit) -> Unit, onPeer: (Transport) -> Unit): RoomListener {
        val port = nextPort++
        listeners[port] = { t -> post { onPeer(t) } }
        val failure = HostingFailure().also { failures[port] = it }
        return object : RoomListener {
            override val endpoint = LanEndpoint("mem", port)
            override fun onFailure(failed: () -> Unit) = failure.register(failed)
            override fun close() {
                listeners.remove(port)
                links.remove(port)?.forEach { it.disconnect() }
            }
        }
    }

    override fun connect(endpoint: RoomEndpoint, post: (() -> Unit) -> Unit): Transport {
        require(endpoint is LanEndpoint)
        val link = network.link()
        val accept = listeners[endpoint.port]
        if (accept == null) {
            link.disconnect() // connection refused: the joiner sees its transport close
        } else {
            links.getOrPut(endpoint.port) { ArrayList() } += link
            accept(link.hostEnd)
        }
        return link.clientEnd
    }

    /** The links joiners opened to [endpoint]'s room, oldest first. */
    fun linksTo(endpoint: RoomEndpoint): List<InMemoryLink> = links[(endpoint as LanEndpoint).port].orEmpty().toList()

    /** Every link to [endpoint]'s room drops, as if the host's Wi-Fi went away. */
    fun dropAll(endpoint: RoomEndpoint) {
        links[(endpoint as LanEndpoint).port]?.forEach { it.disconnect() }
    }

    /** Announcements reach the directory and then fail, as an NSD registration the platform refused. */
    var failAnnouncements = false

    override val announcer: RoomAnnouncer = object : RoomAnnouncer {
        override fun announce(advert: RoomAdvert, onFailed: () -> Unit) {
            directory.announce(advert)
            if (failAnnouncements) onFailed()
        }

        override fun withdraw(roomId: String) = directory.withdraw(roomId)
    }

    override fun browser(): RoomBrowser = directory.browser()
}

/** One simulated phone: its owner and its own stores. */
class Phone(val name: String, val owner: SessionOwner, val identity: IdentityRepository) {
    val state: OwnerState get() = owner.state.value
    val table: TableSnapshot? get() = state.table
}

/**
 * Several phones on one virtual clock and one in-memory room network. Every owner has its own
 * session loop over the shared [scheduler], so all of them run on the test thread, deterministically.
 */
class RoomRig(val seed: Long = 1, val clock: MutableClock = MutableClock(1_000_000)) {
    val scheduler = VirtualScheduler()
    val network = InMemoryRoomNetwork(scheduler, seed)
    private var phones = 0

    fun phone(
        name: String,
        storage: Storage? = null,
        rooms: RoomStore? = null,
        identity: IdentityRepository = IdentityRepository(InMemoryKeyValueStore()),
        room: RoomConfig = RoomConfig(aiDelayMs = 50),
        newTables: Boolean = true,
        history: HistoryRepository? = null,
    ): Phone {
        val n = ++phones
        val owner = SessionOwner(
            OwnerDependencies(
                profiles = TestProfiles.profiles,
                crypto = testCrypto(seed * 100 + n),
                loopFactory = { VirtualSessionLoop(scheduler) },
                decisionExecutorFactory = { w, _, _ -> BlockingWorkerDecisionExecutor(w) },
                clock = clock,
                network = network,
                storage = storage,
                rooms = rooms,
                identity = identity,
                room = room,
                newTables = newTables,
                history = history,
            ),
        )
        return Phone(name, owner, identity).also { made += it }
    }

    /** A host phone with a new room (saved when [storage] is given). */
    fun host(
        name: String = "阿梅",
        settings: RoomSettings = RoomSettings("周末掼蛋"),
        storage: Storage? = null,
        rooms: RoomStore? = null,
        history: HistoryRepository? = null,
    ): Phone {
        val p = phone(name, storage, rooms ?: storage?.let { RoomStore(InMemoryKeyValueStore()) }, history = history)
        assertTrue(p.owner.hostRoom(nickname(name), settings))
        until { p.table?.roomView != null && p.table?.hostSide != null }
        return p
    }

    fun advertOf(host: Phone): RoomAdvert = network.directory.announced.single { it.roomId == host.table!!.tableId }

    /** [phone] asks to join [host]'s room; returns once it waits for approval (or was refused). */
    fun ask(phone: Phone, host: Phone, seat: Seat?, role: Role = if (seat == null) Role.OBSERVER else Role.PLAYER) {
        assertTrue(phone.owner.joinRoom(advertOf(host), role, seat, nickname(phone.name)))
        until { phone.table?.join?.phase.let { it != null && it != JoinPhase.CONNECTING } }
    }

    /** [phone] joins and the host approves it. */
    fun join(phone: Phone, host: Phone, seat: Seat?, role: Role = if (seat == null) Role.OBSERVER else Role.PLAYER): Phone {
        ask(phone, host, seat, role)
        val code = phone.table!!.join!!.code
        until { host.table!!.hostSide!!.pending.any { it.code == code } }
        host.owner.approve(host.table!!.hostSide!!.pending.single { it.code == code }.connectionId)
        until { phone.table?.join?.phase == JoinPhase.ADMITTED && phone.table?.roomView != null }
        return phone
    }

    fun run(ms: Long) = scheduler.runFor(ms)

    /** Phones made so far, for failure messages. */
    val made = ArrayList<Phone>()

    fun until(maxMs: Long = 60_000, done: () -> Boolean) {
        if (scheduler.runUntil(maxMs, done = done)) return
        val story = made.joinToString("\n") { p ->
            val t = p.table
            "${p.name}: ${p.state.lifecycle} screen=${RoomScreen.of(p.state)} ${p.state.failure ?: ""} kind=${t?.kind} join=${t?.join} room=${t?.room} " +
                "lobby=${t?.roomView?.lobby} deal=${t?.view?.match?.dealNumber ?: t?.spectatorView?.match?.dealNumber} " +
                "phase=${t?.view?.match?.phase ?: t?.spectatorView?.match?.phase} grants=${t?.grants} follow=${t?.follow?.seat}"
        }
        throw AssertionError("condition not reached within $maxMs virtual ms\n$story")
    }

    companion object {
        fun savedStorage(): InMemoryStorage = InMemoryStorage()
    }
}

class MutableClock(var now: Long) : WallClock {
    override fun nowMillis(): Long = now
}
