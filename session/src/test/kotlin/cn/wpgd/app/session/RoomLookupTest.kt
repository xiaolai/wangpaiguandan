package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.channel.plainFrame
import cn.wpgd.app.session.client.LookupResult
import cn.wpgd.app.session.client.RoomLookup
import cn.wpgd.app.session.framing.ChunkCodec
import cn.wpgd.app.session.framing.FramingLimits
import cn.wpgd.app.session.host.RoomListingInfo
import cn.wpgd.app.session.transport.TransportListener
import cn.wpgd.app.session.wire.CloseReason
import cn.wpgd.app.session.wire.Hello
import cn.wpgd.app.session.wire.PROTOCOL_VERSION
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.session.wire.RoomListing
import cn.wpgd.app.session.wire.RoomQuery
import cn.wpgd.app.session.wire.WireCodec
import cn.wpgd.app.session.wire.WireValidation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Join by address: a joiner that knows only the host's address asks what room listens there
 * (`room_query`) and gets the room's listing (`room_listing`): the same public facts the discovery
 * advert carries, nothing about members or cards. The host then closes; joining is a new connection
 * through the unchanged hello, code comparison and host-key pinning.
 */
class RoomLookupTest {
    private val listing = RoomListingInfo("周末掼蛋", "阿梅", openSeats = setOf(Seat.EAST, Seat.WEST), replaceableSeats = setOf(Seat.NORTH))

    private fun Table.lookup(timeoutMs: Long = 5_000): Pair<RoomLookup, () -> LookupResult?> {
        val link = network.link()
        host.accept(link.hostEnd)
        var result: LookupResult? = null
        val lookup = RoomLookup(link.clientEnd, scheduler, timeoutMs = timeoutMs) { result = it }
        lookup.start()
        return lookup to { result }
    }

    @Test fun aListedRoomAnswersWithItsPublicListingAndCloses() {
        val t = Table(seed = 401)
        t.player(Seat.SOUTH)
        t.host.listing = listing
        val (_, result) = t.lookup()
        t.until(2_000) { result() != null }
        val found = assertIs<LookupResult.Found>(result()).listing
        assertEquals(ROOM, found.roomId)
        assertEquals("周末掼蛋", found.tableName)
        assertEquals("阿梅", found.hostName)
        assertEquals(t.host.hostKeyFingerprint, found.hostKey, "the listing names the room key a joiner will be welcomed with")
        assertEquals(TestProfiles.base.hash, found.baseProfileHash)
        assertEquals(TestProfiles.match.hash, found.matchProfileHash)
        assertEquals(listOf(Seat.EAST, Seat.WEST), found.openSeats)
        assertEquals(listOf(Seat.NORTH), found.replaceableSeats)
        assertEquals(PROTOCOL_VERSION, found.protocolMax)
        t.run(500)
        assertEquals(1, t.host.connectionCount, "the asking connection is closed; only the seated player remains")
        assertTrue(t.host.pendingAdmissions().isEmpty(), "a lookup is never an admission request")
        assertEquals(1L, t.host.metrics.roomQueries)
        assertFalse(WireCodec.encodeToString(found).contains("south"), "no member's nickname is listed")
    }

    @Test fun aHostWithoutAListingRefusesTheQuery() {
        val t = Table(seed = 402)
        val (_, result) = t.lookup()
        t.until(2_000) { result() != null }
        assertEquals(LookupResult.NotListed, result())
    }

    @Test fun aSilentPeerTimesOut() {
        val t = Table(seed = 403)
        val link = t.network.link()
        link.hostEnd.open(object : TransportListener {
            override fun onReceive(chunk: ByteArray) = Unit
            override fun onWritable() = Unit
            override fun onClosed() = Unit
        })
        var result: LookupResult? = null
        RoomLookup(link.clientEnd, t.scheduler, timeoutMs = 3_000) { result = it }.start()
        t.run(2_900)
        assertNull(result)
        t.run(200)
        assertEquals(LookupResult.TimedOut, result)
    }

    @Test fun aConnectionClosedBeforeAnyAnswerIsReportedAsClosed() {
        val t = Table(seed = 404)
        val link = t.network.link()
        var result: LookupResult? = null
        RoomLookup(link.clientEnd, t.scheduler) { result = it }.start()
        link.disconnect()
        t.run(100)
        assertEquals(LookupResult.Closed, result)
    }

    @Test fun somethingElseAnsweringIsNotARoom() {
        val t = Table(seed = 405)
        val link = t.network.link()
        link.hostEnd.open(object : TransportListener {
            override fun onReceive(chunk: ByteArray) {
                link.hostEnd.trySend(ChunkCodec.split(1, "HTTP/1.1 400 Bad Request\r\n".encodeToByteArray(), 150).first())
            }
            override fun onWritable() = Unit
            override fun onClosed() = Unit
        })
        var result: LookupResult? = null
        RoomLookup(link.clientEnd, t.scheduler) { result = it }.start()
        t.run(500)
        assertEquals(LookupResult.NotARoom, result)
    }

    @Test fun theResultIsDeliveredOnce() {
        val t = Table(seed = 406)
        t.host.listing = listing
        val link = t.network.link()
        t.host.accept(link.hostEnd)
        val results = ArrayList<LookupResult>()
        RoomLookup(link.clientEnd, t.scheduler, timeoutMs = 1_000) { results += it }.start()
        t.run(10_000)
        assertEquals(1, results.size)
        assertIs<LookupResult.Found>(results.single())
    }

    /** A query is only a first message: after a hello on the same connection it is a protocol violation. */
    @Test fun aQueryAfterAHelloIsAViolation() {
        val t = Table(seed = 407)
        t.host.listing = listing
        val link = t.network.link()
        t.host.accept(link.hostEnd)
        var closed = false
        link.clientEnd.open(object : TransportListener {
            override fun onReceive(chunk: ByteArray) = Unit
            override fun onWritable() = Unit
            override fun onClosed() { closed = true }
        })
        val key = cn.wpgd.app.session.wire.B64.encode(testCrypto(9).keyAgreement.generate().publicKey)
        val hello = Hello(PROTOCOL_VERSION, PROTOCOL_VERSION, Role.OBSERVER, ROOM, TestProfiles.base.hash, TestProfiles.match.hash, key)
        ChunkCodec.split(1, plainFrame(WireCodec.encode(hello)), 150).forEach { link.clientEnd.trySend(it) }
        t.run(100)
        ChunkCodec.split(2, plainFrame(WireCodec.encode(RoomQuery(PROTOCOL_VERSION, PROTOCOL_VERSION))), 150).forEach { link.clientEnd.trySend(it) }
        t.run(200)
        assertTrue(closed)
        assertEquals(1L, t.host.metrics.closed[CloseReason.PROTOCOL_VIOLATION])
        assertEquals(0L, t.host.metrics.roomQueries)
    }

    @Test fun listingBoundsAreEnforced() {
        val ok = RoomListing(2, 2, ROOM, "周末掼蛋", "阿梅", TestProfiles.base.hash, TestProfiles.match.hash, "A".repeat(43), listOf(Seat.EAST), listOf(Seat.NORTH))
        assertNull(WireValidation.problem(ok))
        assertNotNull(WireValidation.problem(ok.copy(roomId = "bad id")))
        assertNotNull(WireValidation.problem(ok.copy(tableName = "x".repeat(25))))
        assertNotNull(WireValidation.problem(ok.copy(tableName = " ")))
        assertNotNull(WireValidation.problem(ok.copy(hostName = "a\u0000b")))
        assertNotNull(WireValidation.problem(ok.copy(hostName = "x".repeat(33))))
        assertNotNull(WireValidation.problem(ok.copy(hostKey = "A".repeat(42))))
        assertNotNull(WireValidation.problem(ok.copy(baseProfileHash = "zz")))
        assertNotNull(WireValidation.problem(ok.copy(openSeats = listOf(Seat.EAST, Seat.EAST))))
        assertNotNull(WireValidation.problem(ok.copy(openSeats = listOf(Seat.NORTH))), "a seat is either open or replaceable")
        assertNotNull(WireValidation.problem(ok.copy(protocolMin = 3, protocolMax = 2)))
        assertNotNull(WireValidation.problem(RoomQuery(0, 2)))
        assertNull(WireValidation.problem(RoomQuery(2, 2)))
    }

    @Test fun lookupLimitsAreValidated() {
        val t = Table(seed = 408)
        val link = t.network.link()
        kotlin.test.assertFailsWith<IllegalArgumentException> { RoomLookup(link.clientEnd, t.scheduler, FramingLimits(), timeoutMs = 0) { } }
    }
}
