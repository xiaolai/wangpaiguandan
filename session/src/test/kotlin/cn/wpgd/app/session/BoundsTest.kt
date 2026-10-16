package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.channel.plainFrame
import cn.wpgd.app.session.framing.ChunkCodec
import cn.wpgd.app.session.host.HostConfig
import cn.wpgd.app.session.transport.InMemoryLink
import cn.wpgd.app.session.transport.TransportListener
import cn.wpgd.app.session.wire.CloseReason
import cn.wpgd.app.session.wire.Hello
import cn.wpgd.app.session.wire.Receipt
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.session.wire.WireCodec
import cn.wpgd.app.session.wire.WireValidation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GDN-027, audit A19: oversized frames, stalled hellos, floods and junk disconnect without blocking the table. */
class BoundsTest {
    /** A raw peer that speaks chunks directly, bypassing the client. */
    private class Raw(val link: InMemoryLink) {
        var closed = false
        init {
            link.clientEnd.open(object : TransportListener {
                override fun onReceive(chunk: ByteArray) = Unit
                override fun onWritable() = Unit
                override fun onClosed() { closed = true }
            })
        }
        fun send(frame: ByteArray, frameId: Long = 1) = ChunkCodec.split(frameId, frame, 150).forEach { link.clientEnd.trySend(it) }
    }

    private fun Table.raw(): Raw = network.link().also { host.accept(it.hostEnd) }.let(::Raw)

    private fun Table.tableStillPlays(players: Map<Seat, Table.Peer>) {
        val actor = players.getValue(turn())
        assertTrue(command(actor, simpleBody(actor.client.memberView!!)) is Receipt, "the valid table must keep playing")
    }

    @Test fun oversizedFrameHeaderDisconnectsOnlyThatPeer() {
        val t = Table(seed = 121)
        val players = t.fourPlayers()
        t.startDeal()
        val raw = t.raw()
        raw.link.clientEnd.trySend(ChunkCodec.encode(1, 0, 1000, t.hostConfig.limits.maxFrameSize + 1, ByteArray(100)))
        t.run(100)
        assertEquals(1L, t.host.metrics.closed[CloseReason.PROTOCOL_VIOLATION])
        assertTrue(raw.closed)
        t.tableStillPlays(players)
    }

    @Test fun stalledHelloDisconnectsAtTheDeadline() {
        val t = Table(seed = 122)
        val players = t.fourPlayers()
        t.startDeal()
        val raw = t.raw()
        val before = t.host.connectionCount
        t.run(t.hostConfig.helloDeadlineMs + 50)
        assertEquals(1L, t.host.metrics.closed[CloseReason.HELLO_TIMEOUT])
        assertEquals(before - 1, t.host.connectionCount)
        assertTrue(raw.closed)
        t.tableStillPlays(players)
    }

    @Test fun floodIsRateLimited() {
        val t = Table(seed = 123, hostConfig = HostConfig(roomId = ROOM, recovery = TEST_RECOVERY, maxFramesPerSecond = 50))
        val players = t.fourPlayers()
        t.startDeal()
        val raw = t.raw()
        val hello = Hello(1, 1, Role.OBSERVER, ROOM, TestProfiles.base.hash, TestProfiles.match.hash, "AAAA")
        repeat(200) { raw.send(plainFrame(WireCodec.encode(hello)), frameId = it.toLong() + 1) }
        t.run(100)
        assertTrue(raw.closed, "a flooding peer must be closed")
        t.tableStillPlays(players)
    }

    @Test fun junkAndHostileJsonAreRefused() {
        val t = Table(seed = 124)
        val players = t.fourPlayers()
        t.startDeal()
        val junk = t.raw()
        junk.send(plainFrame("{\"type\":\"snapshot\",\"epoch\":1,\"revision\":1,\"view\":\"x\"}".encodeToByteArray()))
        t.run(100)
        assertTrue(junk.closed, "a plain non-handshake frame is a violation")
        val deep = "[".repeat(10_000) + "]".repeat(10_000)
        assertNull(WireCodec.decode(deep), "deep nesting is refused before parsing")
        assertNull(WireCodec.decode("{\"type\":\"heartbeat\",\"epoch\":1,\"revision\":null,\"statusRevision\":null,\"extra\":1}"))
        t.tableStillPlays(players)
    }

    @Test fun pendingJoinersAreBounded() {
        val t = Table(seed = 125, hostConfig = HostConfig(roomId = ROOM, recovery = TEST_RECOVERY, maxUnadmitted = 2))
        val joiners = (1..4).map { t.joiner(t.config(Role.OBSERVER, "o$it").copy()) }
        t.run(2_000)
        assertTrue(joiners.count { it.client.helloRejection?.reason == cn.wpgd.app.session.wire.HelloRejectReason.ROOM_FULL } >= 1)
    }

    /** A receipt digest is an HMAC-SHA256: anything but 32 base64url-encoded bytes is out of bounds. */
    @Test fun receiptDigestMustBeA32ByteBase64urlValue() {
        val valid = cn.wpgd.app.session.wire.B64.encode(ByteArray(32) { it.toByte() })
        assertNull(WireValidation.problem(Receipt(1, "c-1", 1, valid)))
        for (bad in listOf("a", "AAAA", cn.wpgd.app.session.wire.B64.encode(ByteArray(31)), cn.wpgd.app.session.wire.B64.encode(ByteArray(33)))) {
            assertEquals("digest", WireValidation.problem(Receipt(1, "c-1", 1, bad)), "accepted digest $bad")
        }
    }

    @Test fun clientConfigRejectsTimingThatCannotHold() {
        val ok = Table(seed = 126).config(Role.PLAYER, "south", Seat.SOUTH)
        val bad = listOf(
            "retryMs = 0" to { ok.copy(retryMs = 0) },
            "retryMs = -1" to { ok.copy(retryMs = -1) },
            "maxRetryMs < retryMs" to { ok.copy(retryMs = 400, maxRetryMs = 399) },
            "heartbeatIntervalMs = 0" to { ok.copy(heartbeatIntervalMs = 0) },
            "livenessTimeoutMs = 0" to { ok.copy(livenessTimeoutMs = 0) },
            "resyncMinIntervalMs = -1" to { ok.copy(resyncMinIntervalMs = -1) },
            "queueLimit = 0" to { ok.copy(queueLimit = 0) },
            "protocol 2..1" to { ok.copy(protocolMin = 2, protocolMax = 1) },
            "protocol 0..1" to { ok.copy(protocolMin = 0) },
            "heldRevision = -2" to { ok.copy(heldRevision = -2) },
        )
        for ((name, make) in bad) {
            assertTrue(runCatching { make() }.exceptionOrNull() is IllegalArgumentException, "accepted $name")
        }
    }

    @Test fun hostConfigRejectsBoundsThatCannotHold() {
        val ok = HostConfig(roomId = ROOM, recovery = TEST_RECOVERY)
        val bad = listOf(
            "heartbeatIntervalMs = 0" to { ok.copy(heartbeatIntervalMs = 0) },
            "livenessTimeoutMs = 0" to { ok.copy(livenessTimeoutMs = 0) },
            "helloDeadlineMs = 0" to { ok.copy(helloDeadlineMs = 0) },
            "replyCacheSize = -1" to { ok.copy(replyCacheSize = -1) },
            "queueLimit = 0" to { ok.copy(queueLimit = 0) },
            "maxUnadmitted = 0" to { ok.copy(maxUnadmitted = 0) },
            "maxFramesPerSecond = 0" to { ok.copy(maxFramesPerSecond = 0) },
            "maxObservers = -1" to { ok.copy(maxObservers = -1) },
            "aiDelayMs = -1" to { ok.copy(aiDelayMs = -1) },
            "protocol 2..1" to { ok.copy(protocolMin = 2, protocolMax = 1) },
        )
        for ((name, make) in bad) {
            assertTrue(runCatching { make() }.exceptionOrNull() is IllegalArgumentException, "accepted $name")
        }
    }
}
