package cn.wpgd.app.lan

import cn.wpgd.app.TestProfiles
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.nickname
import cn.wpgd.app.owner.ExecutorSessionLoop
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.room.AddressLookupOutcome
import cn.wpgd.app.room.LanRoomNetwork
import cn.wpgd.app.room.LookupFailure
import cn.wpgd.app.room.NetworkAddressLookup
import cn.wpgd.app.room.InProcessDirectory
import cn.wpgd.app.session.host.AbsencePolicy
import cn.wpgd.app.session.host.HostConfig
import cn.wpgd.app.session.host.RecoveryPolicy
import cn.wpgd.app.session.host.RoomListingInfo
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.wire.CloseReason
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.testCrypto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Join by address over real loopback sockets: the joiner knows only "address:port", looks the room
 * up, and then joins through the unchanged flow: hello, six-digit code compared on both screens,
 * host approval, and on a later rejoin the pinned host key (G2).
 */
class AddressJoinOverLanTest {
    private val rig = LanRig(seed = 731)
    private val loop = ExecutorSessionLoop("lookup") { throw it }
    private val closers = ArrayList<() -> Unit>()

    @After fun tearDown() {
        rig.shutdown()
        loop.shutdown()
        closers.forEach { it() }
    }

    private fun lookup(host: String, port: Int, timeoutMs: Long = 3_000, limits: LanLimits = LanLimits()): AddressLookupOutcome {
        val network = LanRoomNetwork(InProcessDirectory(), limits)
        val out = ArrayBlockingQueue<AddressLookupOutcome>(1)
        NetworkAddressLookup(network, loop, timeoutMs).lookup(LanEndpoint(host, port)) { out.put(it) }
        return checkNotNull(out.poll(20, TimeUnit.SECONDS)) { "no lookup result" }
    }

    private fun portOf(host: cn.wpgd.app.room.Phone): Int = checkNotNull(host.table!!.hostSide!!.listenPort) { "the host shows no port" }

    @Test fun aRoomFoundByAddressIsJoinedThroughCodeComparisonAndApproval() {
        val host = rig.host("阿梅")
        val announced = rig.directory.announced.single()
        val found = lookup("127.0.0.1", portOf(host))
        val advert = (found as AddressLookupOutcome.Found).advert
        assertEquals("the lookup describes the announced room", announced.copy(endpoint = advert.endpoint), advert)
        assertEquals(LanEndpoint("127.0.0.1", portOf(host)), advert.endpoint)

        val zhou = rig.phone("老周")
        assertTrue(zhou.owner.joinRoom(advert, Role.PLAYER, Seat.EAST, nickname("老周")))
        rig.until { zhou.table?.join?.phase == JoinPhase.AWAITING_APPROVAL && host.table!!.hostSide!!.pending.isNotEmpty() }
        val code = zhou.table!!.join!!.code!!
        assertTrue(code.matches(Regex("[0-9]{6}")))
        assertEquals("both screens show the same code", code, host.table!!.hostSide!!.pending.single().code)
        assertEquals("a first join is never admitted before the host approves", JoinPhase.AWAITING_APPROVAL, zhou.table!!.join!!.phase)
        host.owner.approve(host.table!!.hostSide!!.pending.single().connectionId)
        rig.until { zhou.table?.join?.phase == JoinPhase.ADMITTED && zhou.table?.seat == Seat.EAST }
        assertEquals(announced.hostKey, zhou.identity.rejoinTicket(announced.roomId)!!.hostKey)
    }

    @Test fun aClosedPortIsReportedAsWrongPort() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertEquals(AddressLookupOutcome.Failed(LookupFailure.WRONG_PORT), lookup("127.0.0.1", port))
    }

    /**
     * Connecting failed without reaching anyone (timeout, no route, unknown name): the address is
     * wrong or the network keeps phones apart. A fake transport stands in, because on a development
     * machine behind a transparent proxy any TCP connect "succeeds".
     */
    @Test fun aConnectThatNeverReachedTheHostIsUnreachable() {
        for (failure in listOf(ConnectFailure.TIMED_OUT, ConnectFailure.UNREACHABLE)) {
            assertEquals(AddressLookupOutcome.Failed(LookupFailure.UNREACHABLE), fakeLookup(failure, malformed = 0))
        }
        assertEquals(AddressLookupOutcome.Failed(LookupFailure.WRONG_PORT), fakeLookup(ConnectFailure.REFUSED, malformed = 0))
        assertEquals(AddressLookupOutcome.Failed(LookupFailure.CLOSED), fakeLookup(null, malformed = 0))
        assertEquals(AddressLookupOutcome.Failed(LookupFailure.NOT_A_ROOM), fakeLookup(null, malformed = 1))
    }

    @Test fun connectErrorsAreClassified() {
        assertEquals(ConnectFailure.TIMED_OUT, SocketTransport.classify(java.net.SocketTimeoutException("connect timed out")))
        assertEquals(ConnectFailure.REFUSED, SocketTransport.classify(java.net.ConnectException("Connection refused")))
        assertEquals(ConnectFailure.REFUSED, SocketTransport.classify(java.net.ConnectException("failed to connect to /10.0.0.2 (port 1): connect failed: ECONNREFUSED")))
        assertEquals(ConnectFailure.UNREACHABLE, SocketTransport.classify(java.net.ConnectException("connect failed: ENETUNREACH (Network is unreachable)")))
        assertEquals(ConnectFailure.UNREACHABLE, SocketTransport.classify(java.net.NoRouteToHostException("No route to host")))
        assertEquals(ConnectFailure.UNREACHABLE, SocketTransport.classify(java.net.UnknownHostException("nowhere.invalid")))
    }

    /** A connection that closes at once, reporting [failure] and [malformed] records. */
    private fun fakeLookup(failure: ConnectFailure?, malformed: Long): AddressLookupOutcome {
        val network = object : cn.wpgd.app.room.RoomNetwork by LanRoomNetwork(InProcessDirectory()) {
            override fun connect(endpoint: cn.wpgd.app.room.RoomEndpoint, post: (() -> Unit) -> Unit): cn.wpgd.app.session.transport.Transport =
                object : cn.wpgd.app.session.transport.Transport, ConnectReport {
                    override val connectFailure = failure
                    override val malformedRecords = malformed
                    override fun open(listener: cn.wpgd.app.session.transport.TransportListener) = post { listener.onClosed() }
                    override fun trySend(chunk: ByteArray) = true
                    override fun close() = Unit
                }
        }
        val out = ArrayBlockingQueue<AddressLookupOutcome>(1)
        NetworkAddressLookup(network, loop, 2_000).lookup(LanEndpoint("10.0.0.9", 41234)) { out.put(it) }
        return checkNotNull(out.poll(10, TimeUnit.SECONDS))
    }

    @Test fun aServerThatNeverAnswersTimesOut() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        closers += { server.close() }
        thread(isDaemon = true) { runCatching { server.accept() } }
        assertEquals(AddressLookupOutcome.Failed(LookupFailure.TIMEOUT), lookup("127.0.0.1", server.localPort, timeoutMs = 500))
    }

    @Test fun anotherServiceIsNotARoom() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        closers += { server.close() }
        thread(isDaemon = true) {
            runCatching { server.accept().use { s -> s.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".encodeToByteArray()); s.getInputStream().read() } }
        }
        assertEquals(AddressLookupOutcome.Failed(LookupFailure.NOT_A_ROOM), lookup("127.0.0.1", server.localPort))
    }

    /** A host that has left its room lists nothing; the address no longer leads anywhere. */
    @Test fun anEndedRoomIsNoLongerFound() {
        val host = rig.host("阿梅")
        val port = portOf(host)
        host.owner.leaveHosted(checkNotNull(host.owner.prepareLeave()).end())
        rig.until { rig.directory.announced.isEmpty() }
        val outcome = lookup("127.0.0.1", port)
        assertTrue("$outcome", outcome is AddressLookupOutcome.Failed)
    }

    /**
     * G2 over join by address. A member admitted to room R holds a ticket pinned to R's host key. At the
     * same address, a host with R's id but another key (a spoofer, or a reinstalled host) answers:
     * - honestly listing its own key: the ticket is not used, so the member must ask afresh and the
     *   new host must approve after comparing the code; it is never re-admitted silently;
     * - listing the pinned key it does not hold: the welcome's key is checked, the client closes with
     *   HOST_KEY_MISMATCH, and no join (no rejoin proof) ever reaches the impostor.
     */
    @Test fun aHostWithAnotherKeyCannotTakeOverAPinnedSeat() {
        val host = rig.host("阿梅")
        val real = (lookup("127.0.0.1", portOf(host)) as AddressLookupOutcome.Found).advert
        val zhou = rig.phone("老周")
        assertTrue(zhou.owner.joinRoom(real, Role.PLAYER, Seat.EAST, nickname("老周")))
        rig.until { host.table!!.hostSide!!.pending.isNotEmpty() }
        host.owner.approve(host.table!!.hostSide!!.pending.single().connectionId)
        rig.until { zhou.table?.join?.phase == JoinPhase.ADMITTED }
        zhou.owner.shutdown()

        val impostor = Impostor(real.roomId)
        closers += impostor::stop
        val honest = (lookup("127.0.0.1", impostor.port) as AddressLookupOutcome.Found).advert
        assertEquals(real.roomId, honest.roomId)
        assertNotEquals(real.hostKey, honest.hostKey)

        // Same device (same identity store, same ticket), honest listing: asks afresh, code required.
        val again = rig.phone("老周", identity = zhou.identity)
        assertTrue(again.owner.joinRoom(honest, Role.PLAYER, Seat.EAST, nickname("老周")))
        rig.until { again.table?.join?.phase == JoinPhase.AWAITING_APPROVAL }
        assertEquals(1, impostor.pending())
        again.owner.shutdown()

        // A listing that lies about the key: the welcome gives it away; nothing is sent.
        val liar = rig.phone("老周", identity = zhou.identity)
        val before = impostor.pending()
        assertTrue(liar.owner.joinRoom(honest.copy(hostKey = real.hostKey), Role.PLAYER, Seat.EAST, nickname("老周")))
        rig.until { liar.table?.join?.closeReason == CloseReason.HOST_KEY_MISMATCH }
        Thread.sleep(300)
        assertTrue("the impostor got no join from the pinned member", impostor.pending() <= before)
        assertEquals(0, impostor.admitted())
    }

    /** A bare session host on loopback with a given room id and a fresh key, listing itself honestly. */
    private class Impostor(roomId: String) {
        private val loop = ExecutorSessionLoop("impostor") { throw it }
        private val host = SessionHost(HostConfig(roomId = roomId, recovery = RecoveryPolicy(30_000, AbsencePolicy.PAUSE, AbsencePolicy.PAUSE, null)), TestProfiles.profiles.base, TestProfiles.profiles.match, testCrypto(999), loop)
        private val server = LanServer(loop::post, { t -> host.accept(t) }, bindAddress = InetAddress.getLoopbackAddress())
        val port: Int get() = server.port

        init {
            onLoop { host.listing = RoomListingInfo("周末掼蛋", "阿梅", setOf(Seat.EAST)) }
        }

        private fun <T> onLoop(block: () -> T): T {
            val q = ArrayBlockingQueue<Result<T>>(1)
            loop.post { q.put(runCatching(block)) }
            return q.poll(5, TimeUnit.SECONDS)!!.getOrThrow()
        }

        fun pending(): Int = onLoop { host.pendingAdmissions().size }
        fun admitted(): Int = onLoop { host.room().seats.count { it.nickname != null } }

        fun stop() {
            server.stop()
            loop.shutdown()
        }
    }
}
