package cn.wpgd.app.lan

import cn.wpgd.app.TestProfiles
import cn.wpgd.app.ble.BleFaults
import cn.wpgd.app.ble.BleRig
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchState
import cn.wpgd.app.core.match.MatchMemberView
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.IdentityRepository
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.nickname
import cn.wpgd.app.owner.ExecutorSessionLoop
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.owner.OwnerDependencies
import cn.wpgd.app.owner.RoomConfig
import cn.wpgd.app.owner.SessionOwner
import cn.wpgd.app.room.InProcessDirectory
import cn.wpgd.app.room.LanRoomNetwork
import cn.wpgd.app.room.LobbyUi
import cn.wpgd.app.room.Phone
import cn.wpgd.app.room.RoomNetwork
import cn.wpgd.app.room.RoomRig
import cn.wpgd.app.room.RoomSettings
import cn.wpgd.app.room.Seated
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.testCrypto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

/** Phones whose owners run on real threads and reach each other over loopback TCP. */
class LanRig(private val seed: Long) {
    val directory = InProcessDirectory()
    val network: RoomNetwork = LanRoomNetwork(directory, bindAddress = InetAddress.getLoopbackAddress())
    private val made = ArrayList<Phone>()

    fun phone(name: String): Phone {
        val identity = IdentityRepository(InMemoryKeyValueStore())
        val owner = SessionOwner(
            OwnerDependencies(
                profiles = TestProfiles.profiles,
                // The same seeds as [RoomRig] gives its phones, in the same order.
                crypto = testCrypto(seed * 100 + made.size + 1),
                loopFactory = { onError -> ExecutorSessionLoop("lan-${made.size}", onError) },
                network = network,
                identity = identity,
                room = RoomConfig(aiDelayMs = 5, aiDeadlineMs = 30_000),
            ),
        )
        return Phone(name, owner, identity).also { made += it }
    }

    fun until(ms: Long = 20_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!done()) {
            if (System.currentTimeMillis() > end) {
                throw AssertionError("timed out\n" + made.joinToString("\n") { "${it.name}: ${it.state.lifecycle} ${it.state.failure} join=${it.table?.join} deal=${it.table?.view?.match?.dealNumber}" })
            }
            Thread.sleep(2)
        }
    }

    fun host(name: String): Phone = phone(name).also { p ->
        assertTrue(p.owner.hostRoom(nickname(name), RoomSettings("周末掼蛋")))
        until { p.table?.roomView != null && directory.announced.isNotEmpty() }
    }

    fun join(p: Phone, host: Phone, seat: Seat) {
        val advert = directory.announced.single { it.roomId == host.table!!.tableId }
        assertTrue(p.owner.joinRoom(advert, Role.PLAYER, seat, nickname(p.name)))
        until { p.table?.join?.phase == JoinPhase.AWAITING_APPROVAL && host.table!!.hostSide!!.pending.isNotEmpty() }
        assertEquals("both screens show the same code", p.table!!.join!!.code, host.table!!.hostSide!!.pending.single().code)
        host.owner.approve(host.table!!.hostSide!!.pending.single().connectionId)
        until { p.table?.join?.phase == JoinPhase.ADMITTED && p.table?.roomView != null }
    }

    fun shutdown() = made.forEach { it.owner.shutdown() }
}

/** What one deal leaves behind, for comparing transports. */
data class DealOutcome(val host: MatchState, val south: MatchMemberView, val east: MatchMemberView)

/** The host's authoritative state, read on its session loop; [tick] lets that loop run. */
fun Phone.hostState(tick: () -> Unit): MatchState {
    val out = AtomicReference<MatchState>()
    owner.withHost { out.set(it.state) }
    repeat(10_000) { out.get()?.let { return it }; tick() }
    error("the host loop never ran")
}

/**
 * Seats AI at NORTH and WEST, readies, starts, and lets the two scripted people play deal 1 to its
 * settlement. [wait] waits on the world's clock; [tick] lets it run a little.
 */
fun playDealOne(host: Phone, east: Phone, people: List<Seated>, wait: (() -> Boolean) -> Unit, tick: () -> Unit): DealOutcome {
    host.owner.setAiSeat(Seat.NORTH)
    host.owner.setAiSeat(Seat.WEST)
    east.owner.setReady(true)
    wait { LobbyUi.of(host.state, null)?.canStart == true }
    host.owner.startMatch()
    wait { east.table?.view?.match?.dealNumber == 1 && host.table?.view?.match?.dealNumber == 1 }
    val settled = { p: Phone -> p.table?.view?.match.let { it?.phase == MatchPhase.AWAITING_DEAL && it.lastResult?.dealNumber == 1 } }
    var guard = 0
    while (!(settled(host) && settled(east))) {
        people.forEach { it.human.act() }
        tick()
        check(++guard < 1_000_000) { "deal 1 did not settle" }
    }
    return DealOutcome(host.hostState(tick), host.table!!.view!!, east.table!!.view!!)
}

class LanRoomTest {
    private val rigs = ArrayList<LanRig>()

    @After fun tearDown() = rigs.forEach { it.shutdown() }

    /** A room over real sockets: ask, compare codes, approve, lobby, play a whole deal. */
    @Test fun aRoomPlaysADealOverLoopbackSockets() {
        val lan = LanRig(seed = 121).also { rigs += it }
        val host = lan.host("阿梅")
        val zhou = lan.phone("老周")
        lan.join(zhou, host, Seat.EAST)
        val people = listOf(Seated(host, 1), Seated(zhou, 2))
        val out = playDealOne(host, zhou, people, { lan.until(60_000, it) }, { Thread.sleep(1) })
        assertEquals(1, out.host.previous!!.dealNumber)
        assertEquals(out.host.previous, out.east.match.lastResult)
        assertTrue(people.all { it.human.actions > 0 })
        assertEquals(0L, zhou.owner.metrics.failures.get())
    }
}

/**
 * GDN-023: the session is transport-independent. The same seeds and the same scripted people produce
 * the same deal, the same play and the same settlement — the whole authoritative state and both
 * members' views — over loopback TCP, and (WI-10) over Bluetooth links at the default MTU, as over
 * the seeded in-memory network.
 */
class TransportParityTest {
    private val rigs = ArrayList<LanRig>()

    @After fun tearDown() = rigs.forEach { it.shutdown() }

    private fun inMemory(seed: Long): DealOutcome {
        val rig = RoomRig(seed)
        val memHost = rig.phone("阿梅")
        assertTrue(memHost.owner.hostRoom(nickname("阿梅"), RoomSettings("周末掼蛋")))
        rig.until { memHost.table?.roomView != null }
        val memEast = rig.join(rig.phone("老周"), memHost, Seat.EAST)
        return playDealOne(memHost, memEast, listOf(Seated(memHost, 1), Seated(memEast, 2)), { rig.until(600_000, it) }, { rig.run(20) })
    }

    @Test fun bluetoothLinksAndTheInMemoryNetworkProduceTheSameDeal() {
        val seed = 123L
        val memory = inMemory(seed)
        val ble = BleRig(seed, BleFaults(mtu = 23))
        val bleHost = ble.host("阿梅")
        val bleEast = ble.join(ble.phone("老周"), bleHost, Seat.EAST)
        val radio = playDealOne(bleHost, bleEast, listOf(Seated(bleHost, 1), Seated(bleEast, 2)), { ble.until(1_200_000, it) }, { ble.scheduler.runFor(20) })
        ble.shutdown()
        assertEquals("the same settlement", memory.host.previous, radio.host.previous)
        assertEquals("the same authoritative state", memory.host, radio.host)
        assertEquals("the same view for SOUTH", memory.south, radio.south)
        assertEquals("the same view for EAST", memory.east, radio.east)
    }

    @Test fun loopbackSocketsAndTheInMemoryNetworkProduceTheSameDeal() {
        val seed = 122L
        val rig = RoomRig(seed)
        val memHost = rig.phone("阿梅")
        assertTrue(memHost.owner.hostRoom(nickname("阿梅"), RoomSettings("周末掼蛋")))
        rig.until { memHost.table?.roomView != null }
        val memEast = rig.join(rig.phone("老周"), memHost, Seat.EAST)
        val memory = playDealOne(memHost, memEast, listOf(Seated(memHost, 1), Seated(memEast, 2)), { rig.until(600_000, it) }, { rig.run(20) })

        val lan = LanRig(seed).also { rigs += it }
        val lanHost = lan.host("阿梅")
        val lanEast = lan.phone("老周")
        lan.join(lanEast, lanHost, Seat.EAST)
        val sockets = playDealOne(lanHost, lanEast, listOf(Seated(lanHost, 1), Seated(lanEast, 2)), { lan.until(60_000, it) }, { Thread.sleep(1) })

        assertEquals("the same settlement", memory.host.previous, sockets.host.previous)
        assertEquals("the same authoritative state", memory.host, sockets.host)
        assertEquals("the same view for SOUTH", memory.south, sockets.south)
        assertEquals("the same view for EAST", memory.east, sockets.east)
    }
}
