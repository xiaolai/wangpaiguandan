package cn.wpgd.app.ble

import cn.wpgd.app.BlockingWorkerDecisionExecutor
import cn.wpgd.app.TestProfiles
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.IdentityRepository
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.lan.DealOutcome
import cn.wpgd.app.lan.playDealOne
import cn.wpgd.app.nickname
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.owner.OwnerDependencies
import cn.wpgd.app.owner.RoomConfig
import cn.wpgd.app.owner.SessionOwner
import cn.wpgd.app.owner.VirtualSessionLoop
import cn.wpgd.app.room.Phone
import cn.wpgd.app.room.RoomAdvert
import cn.wpgd.app.room.RoomMedium
import cn.wpgd.app.room.RoomRig
import cn.wpgd.app.room.RoomScreen
import cn.wpgd.app.room.RoomSettings
import cn.wpgd.app.room.Seated
import cn.wpgd.app.session.transport.VirtualScheduler
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.testCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phones whose owners share one virtual clock and reach each other over the fake Bluetooth air:
 * every chunk goes through [BleLink] packetization and reassembly, every room is found by scanning
 * and reading its advert characteristic.
 */
class BleRig(val seed: Long = 1, faults: BleFaults = BleFaults()) {
    val scheduler = VirtualScheduler()
    val air = FakeAir(scheduler, seed, faults)
    val made = ArrayList<Phone>()
    val networks = HashMap<Phone, BleRoomNetwork>()
    val radios = HashMap<Phone, FakeBleRadio>()

    fun phone(name: String): Phone {
        val n = made.size + 1
        val radio = air.radio()
        val network = BleRoomNetwork(radio, scheduler)
        val identity = IdentityRepository(InMemoryKeyValueStore())
        val owner = SessionOwner(
            OwnerDependencies(
                profiles = TestProfiles.profiles,
                // The same seeds as [RoomRig] gives its phones, in the same order.
                crypto = testCrypto(seed * 100 + n),
                loopFactory = { VirtualSessionLoop(scheduler) },
                decisionExecutorFactory = { w, _, _ -> BlockingWorkerDecisionExecutor(w) },
                network = network,
                identity = identity,
                room = RoomConfig(aiDelayMs = 50),
            ),
        )
        return Phone(name, owner, identity).also {
            made += it
            networks[it] = network
            radios[it] = radio
        }
    }

    fun host(name: String = "阿梅"): Phone = phone(name).also { p ->
        assertTrue(p.owner.hostRoom(nickname(name), RoomSettings("周末掼蛋")))
        until { p.table?.roomView != null && p.table?.hostSide != null }
    }

    /** What a joiner's Bluetooth search shows: scan, then read each host's advert. */
    fun find(joiner: Phone, host: Phone): RoomAdvert {
        val browser = networks.getValue(joiner).browser()
        var rooms = emptyList<RoomAdvert>()
        browser.start { rooms = it }
        until { rooms.any { it.roomId == host.table!!.tableId } }
        browser.stop()
        return rooms.single { it.roomId == host.table!!.tableId }
    }

    fun join(phone: Phone, host: Phone, seat: Seat?): Phone {
        val advert = find(phone, host)
        assertTrue(phone.owner.joinRoom(advert, if (seat == null) Role.OBSERVER else Role.PLAYER, seat, nickname(phone.name)))
        until { phone.table?.join?.phase.let { it != null && it != JoinPhase.CONNECTING } }
        val code = phone.table!!.join!!.code
        until { host.table!!.hostSide!!.pending.any { it.code == code } }
        host.owner.approve(host.table!!.hostSide!!.pending.single { it.code == code }.connectionId)
        until { phone.table?.join?.phase == JoinPhase.ADMITTED && phone.table?.roomView != null }
        return phone
    }

    fun until(maxMs: Long = 120_000, done: () -> Boolean) {
        if (scheduler.runUntil(maxMs, done = done)) return
        val story = made.joinToString("\n") { p -> "${p.name}: ${p.state.lifecycle} screen=${RoomScreen.of(p.state)} ${p.state.failure ?: ""} join=${p.table?.join} deal=${p.table?.view?.match?.dealNumber}" }
        throw AssertionError("condition not reached within $maxMs virtual ms\n$story")
    }

    fun shutdown() = made.forEach { it.owner.shutdown() }
}

class BleRoomTest {
    @Test fun aHostIsFoundByScanningAndReadingItsAdvert() {
        val rig = BleRig(31)
        val host = rig.host()
        val zhou = rig.phone("老周")
        val advert = rig.find(zhou, host)
        assertEquals("周末掼蛋", advert.tableName)
        assertEquals("阿梅", advert.hostName)
        assertEquals(host.table!!.info!!.tableCode, advert.tableCode)
        assertEquals(BleEndpoint(rig.radios.getValue(host).address), advert.endpoint)
        assertEquals(RoomMedium.BLUETOOTH, host.table!!.info!!.medium)
        assertTrue(rig.networks.getValue(host).stats().server!!.advertising)
        rig.shutdown()
    }

    @Test fun aRoomPlaysADealOverBluetoothAtTheDefaultMtu() {
        val rig = BleRig(32, BleFaults(mtu = 23))
        val host = rig.host()
        val zhou = rig.join(rig.phone("老周"), host, Seat.EAST)
        assertEquals(RoomMedium.BLUETOOTH, zhou.table!!.info!!.medium)
        val out = playDeal(rig, host, zhou)
        assertEquals(1, out.host.previous!!.dealNumber)
        assertEquals(out.host.previous, out.east.match.lastResult)
        val link = rig.networks.getValue(zhou).stats().links.single().second
        assertEquals(20, link.attPayload)
        assertTrue("records were split into several packets", link.packetsOut.get() > link.recordsOut.get())
        assertEquals(0L, zhou.owner.metrics.failures.get())
        rig.shutdown()
    }

    @Test fun aDealCompletesOverAFaultyRadio() {
        val rig = BleRig(33, BleFaults(mtu = 23, drop = 0.002, duplicate = 0.02, reorder = 0.02, truncate = 0.001))
        val host = rig.host()
        val zhou = rig.join(rig.phone("老周"), host, Seat.EAST)
        val out = playDeal(rig, host, zhou)
        assertEquals(out.host.previous, out.east.match.lastResult)
        val links = rig.networks.values.flatMap { it.stats().links.map { l -> l.second } }
        assertTrue("faults reached the links", links.sumOf { it.duplicatePackets.get() } > 0)
        rig.shutdown()
    }

    @Test fun fourPlayersAndTwoWatchersShareOneBluetoothHost() {
        val rig = BleRig(34)
        val host = rig.host()
        val players = listOf(Seat.EAST, Seat.NORTH, Seat.WEST).mapIndexed { i, seat -> rig.join(rig.phone("玩家$i"), host, seat) }
        val watchers = (0 until 2).map { rig.join(rig.phone("观众$it"), host, null) }
        players.forEach { it.owner.setReady(true) }
        rig.until { cn.wpgd.app.room.LobbyUi.of(host.state, null)?.canStart == true }
        host.owner.startMatch()
        rig.until { (players + watchers + host).all { p -> (p.table?.view?.match?.dealNumber ?: p.table?.spectatorView?.match?.dealNumber) == 1 } }
        val server = rig.networks.getValue(host).stats().server!!
        assertEquals("five remote members, one connection each", 5, server.connected)
        assertTrue(server.maxConnected >= 5)
        rig.shutdown()
    }

    @Test fun aHostWhoseRadioDropsIsSeenAsLostAndTheJoinerRejoins() {
        val rig = BleRig(35)
        val host = rig.host()
        val zhou = rig.join(rig.phone("老周"), host, Seat.EAST)
        rig.air.cut(rig.radios.getValue(host).address)
        rig.until { zhou.table?.join?.phase == JoinPhase.HOST_LOST }
        rig.until { zhou.table?.join?.phase == JoinPhase.ADMITTED && zhou.table?.roomView != null }
        assertTrue(zhou.owner.metrics.rejoinAttempts.get() >= 1)
        rig.shutdown()
    }

    @Test fun aRoomIsReachedOnlyOverTheMediumItWasFoundOn() {
        val ble = BleRig(36)
        val host = ble.host()
        val advert = ble.find(ble.phone("老周"), host)
        val lan = RoomRig(36)
        val lanPhone = lan.phone("小李")
        assertFalse("a LAN owner never joins a Bluetooth room", lanPhone.owner.joinRoom(advert, Role.PLAYER, Seat.EAST, nickname("小李")))
        ble.shutdown()
    }

    @Test fun leavingWithdrawsTheAdvertAndClosesTheServer() {
        val rig = BleRig(37)
        val host = rig.host()
        val net = rig.networks.getValue(host)
        val stats = net.stats().server!!
        host.owner.shutdown()
        assertFalse(stats.advertising)
        assertEquals(null, net.stats().server)
    }

    private fun playDeal(rig: BleRig, host: Phone, east: Phone): DealOutcome =
        playDealOne(host, east, listOf(Seated(host, 1), Seated(east, 2)), { rig.until(1_200_000, it) }, { rig.scheduler.runFor(20) })
}
