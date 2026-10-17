package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.channel.plainFrame
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.framing.ChunkCodec
import cn.wpgd.app.session.host.HostConfig
import cn.wpgd.app.session.host.RoomStatus
import cn.wpgd.app.session.host.SeatControl
import cn.wpgd.app.session.storage.InMemoryStorage
import cn.wpgd.app.session.storage.SavedTable
import cn.wpgd.app.session.storage.standIn
import cn.wpgd.app.session.transport.TransportListener
import cn.wpgd.app.session.wire.AdmissionDenial
import cn.wpgd.app.session.wire.CloseReason
import cn.wpgd.app.session.wire.HelloRejectReason
import cn.wpgd.app.session.wire.Join
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.session.wire.RoomQuery
import cn.wpgd.app.session.wire.Status
import cn.wpgd.app.session.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regressions for the session client and host findings of the 2026-10-17 branch audit (#50-#58). */
class ClientHostAuditFixesTest {
    /** #50: a host restored from its save numbers statuses from 1 again; the rejoined client must apply them. */
    @Test fun aClientRejoiningARestoredHostAppliesItsFreshStatuses() {
        val saved = SavedTable(5001)
        val south = saved.t.player(Seat.SOUTH)
        assertTrue(saved.host.setAi(Seat.NORTH, standIn))
        assertTrue(saved.host.setAi(Seat.WEST, standIn))
        // Lobby changes push this member's status revision well above what a restored host will reach soon.
        repeat(6) {
            assertTrue(saved.host.setAi(Seat.EAST, standIn))
            assertTrue(saved.host.clearAiSeat(Seat.EAST))
        }
        saved.t.run(1_500)
        val before = south.client.statusRevision
        assertTrue(before >= 12, "the client should have seen many statuses, saw $before")
        saved.crash()
        saved.restored()
        val back = saved.rejoin(south)
        assertTrue(saved.host.setAi(Seat.EAST, standIn))
        saved.t.run(200)
        val east = checkNotNull(back.client.room).seats.single { it.seat == Seat.EAST }
        assertEquals(Occupant.AI, east.occupant, "the restored host's roster never reached the client (status revision held at $before)")
        assertTrue(back.client.statusRevision < before, "the restored host restarted its numbering")
    }

    /**
     * #50, round 2: the follow view and grants are connection-scoped. A grant revoked while the
     * observer was away must not stay visible through the reconnect, before the new status arrives.
     */
    @Test fun aReconnectingObserverDropsItsPrivateFollowView() {
        val t = Table(seed = 5002)
        val players = t.fourPlayers()
        val o = t.observer("watcher")
        t.startDeal()
        val south = players.getValue(Seat.SOUTH)
        assertIs<cn.wpgd.app.session.wire.Receipt>(t.command(o, cn.wpgd.app.session.wire.FollowRequestBody(Seat.SOUTH)))
        t.until(5_000) { south.client.grants.any { it.status == cn.wpgd.app.session.wire.GrantStatus.PENDING } }
        val g = south.client.grants.single { it.status == cn.wpgd.app.session.wire.GrantStatus.PENDING }
        assertIs<cn.wpgd.app.session.wire.Receipt>(t.command(south, cn.wpgd.app.session.wire.ConsentBody(g.grantId, true)))
        t.until(5_000) { o.client.follow != null }

        // The observer's link drops; while it is away the player revokes.
        o.link.disconnect()
        t.run(500)
        assertNull(o.client.follow, "the private view outlived its connection")
        assertTrue(o.client.grants.isEmpty(), "grants of a closed connection are still shown")
        assertIs<cn.wpgd.app.session.wire.Receipt>(t.command(south, cn.wpgd.app.session.wire.RevokeBody(g.grantId)))
        val back = t.reconnect(o)
        assertNull(back.client.follow, "a stale private view is visible during the reconnect")
        t.until(5_000) { back.client.phase == ClientPhase.ADMITTED && back.client.grants.isNotEmpty() }
        assertNull(back.client.follow)
        assertEquals(cn.wpgd.app.session.wire.GrantStatus.REVOKED, back.client.grants.single { it.grantId == g.grantId }.status)
    }

    /** #51: a configured pin that is not a fingerprint must refuse every key, not skip the check. */
    @Test fun aMalformedPinnedHostKeyRefusesTheWelcome() {
        val t = Table(seed = 5101)
        for (pin in listOf("!!not-base64!!", "A", cn.wpgd.app.session.wire.B64.encode(ByteArray(5)))) {
            val joins = ArrayList<Join>()
            val peer = t.connect(t.config(Role.OBSERVER, "o", pin = pin))
            peer.client.sentTap = { if (it is Join) joins += it }
            t.run(2_000)
            assertEquals(CloseReason.HOST_KEY_MISMATCH, peer.client.closeReason, "pin '$pin': phase ${peer.client.phase}")
            assertTrue(joins.isEmpty(), "pin '$pin': a join was sent to an unpinned host")
            assertNotEquals(ClientPhase.PENDING, peer.client.phase)
            assertEquals(pin, peer.client.hostKeyFingerprint, "pin '$pin' was overwritten by the welcome's key")
        }
    }

    /** #52: after keys exist, unauthenticated plaintext must not keep a silent host looking alive. */
    @Test fun plaintextFramesDoNotRefreshLivenessAfterKeys() {
        val t = Table(seed = 5201, hostFactory = { s -> cn.wpgd.app.session.host.SessionHost(HostConfig(roomId = ROOM, recovery = TEST_RECOVERY), TestProfiles.base, TestProfiles.match, testCrypto(5201), s) })
        val south = t.player(Seat.SOUTH)
        t.run(200)
        assertFalse(south.client.stale)
        // The host goes silent (its timers stop); someone on the path injects plaintext room queries.
        t.process.alive = false
        val query = plainFrame(WireCodec.encode(RoomQuery(1, 1)))
        var frameId = 1_000_000L
        val end = t.scheduler.now + t.hostConfig.livenessTimeoutMs * 3
        while (t.scheduler.now < end && !south.client.stale) {
            ChunkCodec.split(frameId++, query, 150).forEach { south.link.hostEnd.trySend(it) }
            t.run(250)
        }
        assertTrue(south.client.stale, "injected plaintext kept the client live for ${t.hostConfig.livenessTimeoutMs * 3} ms")
    }

    /** #53: a configured ticket from an ended epoch must not be presented again; the client asks afresh. */
    @Test fun aConfiguredTicketIsDroppedWhenItsEpochEnds() {
        val t = Table(seed = 5301)
        val first = t.player(Seat.SOUTH)
        val cap = checkNotNull(first.client.capability)
        val joins = ArrayList<Join>()
        val ticketed = t.connect(t.config(Role.PLAYER, "south", Seat.SOUTH, rejoin = cap))
        ticketed.client.sentTap = { if (it is Join) joins += it }
        t.until(5_000) { ticketed.client.phase == ClientPhase.ADMITTED }
        assertNotNull(joins.last().rejoinProof, "the ticket rejoins by proof")
        // The link is lost (no closing message), then the host starts a new epoch.
        ticketed.link.disconnect()
        t.run(500)
        t.host.restartRoom()
        joins.clear()
        val again = t.reconnect(ticketed)
        t.run(2_000)
        assertTrue(joins.isNotEmpty(), "no join was sent")
        assertTrue(joins.all { it.rejoinProof == null }, "the obsolete ticket was presented to the new epoch")
        assertNotEquals(AdmissionDenial.CAPABILITY_INVALID, again.client.denial)
        assertEquals(ClientPhase.PENDING, again.client.phase)
    }

    /** #55: connections that never say hello count against maxUnadmitted at accept time. */
    @Test fun silentConnectionsCannotExceedTheUnadmittedBound() {
        val t = Table(seed = 5501, hostConfig = HostConfig(roomId = ROOM, recovery = TEST_RECOVERY, maxUnadmitted = 2))
        val links = (1..5).map { t.network.link() }
        val closed = BooleanArray(links.size)
        links.forEachIndexed { i, l ->
            l.clientEnd.open(object : TransportListener {
                override fun onReceive(chunk: ByteArray) = Unit
                override fun onWritable() = Unit
                override fun onClosed() { closed[i] = true }
            })
        }
        val ids = links.map { t.host.accept(it.hostEnd) }
        t.run(100)
        assertEquals(2, t.host.connectionCount, "silent peers were registered past the bound")
        assertEquals(3, ids.count { it == null })
        assertEquals(3L, t.host.metrics.refusedConnections)
        assertTrue(closed.drop(2).all { it }, "refused transports were not closed")
        // A joiner refused this way learns the room is full.
        val late = t.joiner(t.config(Role.OBSERVER, "late"))
        t.run(500)
        assertEquals(HelloRejectReason.ROOM_FULL, late.client.helloRejection?.reason)
    }

    /** #56: an admission whose sends overflow the queue leaves the seat disconnected (grace runs), not Human. */
    @Test fun anAdmissionThatOverflowsLeavesTheSeatAbsent() {
        val t = Table(seed = 5601, hostConfig = HostConfig(roomId = ROOM, recovery = TEST_RECOVERY, queueLimit = 2))
        val south = t.joiner(t.config(Role.PLAYER, "south", Seat.SOUTH))
        t.until(5_000) { south.client.phase == ClientPhase.PENDING }
        val p = t.host.pendingAdmissions().single()
        t.host.approve(p.connectionId, checkNotNull(south.client.confirmationCode))
        assertEquals(1L, t.host.metrics.closed[CloseReason.QUEUE_OVERFLOW], "the admission sends should overflow a queue of 2")
        assertFalse(t.host.members().single().connected)
        assertIs<SeatControl.Absent>(t.host.seatControl(Seat.SOUTH), "a disconnected member's seat must be in grace")
    }

    /** #57: restarting an unsaved quarantined room starts the new epoch unquarantined. */
    @Test fun restartRoomClearsQuarantine() {
        val t = Table(seed = 5701)
        val peer = t.connect(t.config(Role.PLAYER, "south", Seat.SOUTH).copy(heldRevision = 100))
        t.until(5_000) { peer.client.phase == ClientPhase.PENDING }
        t.host.approve(t.host.pendingAdmissions().single().connectionId, checkNotNull(peer.client.confirmationCode))
        t.until(10_000) { t.host.status is RoomStatus.Quarantined }
        t.host.restartRoom()
        assertEquals(RoomStatus.Active, t.host.status)
        val south = t.player(Seat.SOUTH)
        assertEquals(ClientPhase.ADMITTED, south.client.phase)
    }

    /** #58: a seat swap whose save fails never shows a member the proposed seat. */
    @Test fun aSwapThatCannotBeSavedNeverMovesAClient() {
        val saved = SavedTable(5801, InMemoryStorage(5801))
        val south = saved.t.player(Seat.SOUTH)
        val east = saved.t.player(Seat.EAST)
        saved.t.run(200)
        south.received.clear()
        saved.storage.failWhen = { _, _ -> true }
        assertFalse(saved.host.swapSeats(Seat.SOUTH, Seat.EAST))
        saved.t.run(500)
        val statuses = south.received.filterIsInstance<Status>()
        assertTrue(statuses.isNotEmpty(), "the storage pause reached the member")
        assertTrue(statuses.all { it.room.yourSeat == Seat.SOUTH }, "a rejected seat was sent: ${statuses.map { it.room.yourSeat }}")
        assertEquals(Seat.SOUTH, south.client.seat)
        assertEquals(Seat.EAST, east.client.seat)
        assertEquals(Seat.SOUTH, saved.host.members().single { it.nickname == "south" }.seat)
    }

    @Test fun refusedAcceptKeepsNothing() {
        val t = Table(seed = 5502, hostConfig = HostConfig(roomId = ROOM, recovery = TEST_RECOVERY, maxUnadmitted = 1))
        assertNotNull(t.host.accept(t.network.link().hostEnd))
        assertNull(t.host.accept(t.network.link().hostEnd))
        assertEquals(1, t.host.connectionCount)
    }
}
