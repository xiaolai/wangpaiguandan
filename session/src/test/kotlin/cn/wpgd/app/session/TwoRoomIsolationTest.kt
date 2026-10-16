package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.host.HostConfig
import cn.wpgd.app.session.transport.InMemoryNetwork
import cn.wpgd.app.session.transport.TransportListener
import cn.wpgd.app.session.transport.VirtualScheduler
import cn.wpgd.app.session.wire.AdmissionDenial
import cn.wpgd.app.session.wire.HelloRejectReason
import cn.wpgd.app.session.wire.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GDN-025, audit A14: a nearby second room never accepts the first room's peers, frames or capabilities. */
class TwoRoomIsolationTest {
    @Test fun twoRoomsOnOneNetworkStayApart() {
        val scheduler = VirtualScheduler()
        val network = InMemoryNetwork(scheduler, 131)
        val a = Table(seed = 131, hostConfig = HostConfig(roomId = "room-a", recovery = TEST_RECOVERY), scheduler = scheduler, network = network)
        val b = Table(seed = 132, hostConfig = HostConfig(roomId = "room-b", recovery = TEST_RECOVERY), scheduler = scheduler, network = network)
        val aPlayers = a.fourPlayers()
        b.fourPlayers()
        a.startDeal()
        b.startDeal()
        val bRevision = b.host.revision

        // A client for room A that reaches host B is refused at hello.
        val lost = b.joiner(b.config(Role.PLAYER, "lost", Seat.SOUTH, roomId = "room-a"))
        a.until(5_000) { lost.client.phase == ClientPhase.REJECTED }
        assertEquals(HelloRejectReason.ROOM_MISMATCH, lost.client.helloRejection?.reason)

        // Room A's capability means nothing in room B.
        val borrowed = b.joiner(b.config(Role.PLAYER, "borrowed", Seat.SOUTH, rejoin = aPlayers.getValue(Seat.SOUTH).client.capability))
        a.until(5_000) { borrowed.client.phase == ClientPhase.REJECTED }
        assertEquals(AdmissionDenial.CAPABILITY_INVALID, borrowed.client.denial)

        // Room A's sealed frames, replayed into room B, open nothing and change nothing.
        val captured = network.captured.filter { it.toHost }.map { it.bytes }
        val bridge = network.link()
        b.host.accept(bridge.hostEnd)
        bridge.clientEnd.open(object : TransportListener {
            override fun onReceive(chunk: ByteArray) = Unit
            override fun onWritable() = Unit
            override fun onClosed() = Unit
        })
        for (c in captured.take(500)) bridge.clientEnd.trySend(c)
        a.run(1_000)
        assertEquals(bRevision, b.host.revision, "room A traffic changed room B")
        assertTrue(b.host.members().none { it.nickname == "borrowed" || it.nickname == "lost" })

        // Room A plays on; room B is untouched.
        val actor = aPlayers.getValue(a.turn())
        a.command(actor, simpleBody(actor.client.memberView!!))
        assertEquals(bRevision, b.host.revision)
    }
}
