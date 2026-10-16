package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.host.HostConfig
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.wire.AdmissionDenial
import cn.wpgd.app.session.wire.AdmissionDenied
import cn.wpgd.app.session.wire.AdmissionPending
import cn.wpgd.app.session.wire.Heartbeat
import cn.wpgd.app.session.wire.HelloRejectReason
import cn.wpgd.app.session.wire.HelloRejected
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.session.wire.Welcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GDN-011, GDN-024, P6: no private bytes before approval; version mismatch refused before any hand. */
class AdmissionTest {
    @Test fun unadmittedPeerReceivesNoPrivateBytesWhileTheGameRuns() {
        val t = Table(seed = 51)
        val players = Seat.entries.filter { it != Seat.WEST }.associateWith { t.player(it) }
        t.host.setAi(Seat.WEST) { _, o -> if (o.mayPass) cn.wpgd.app.session.wire.PassBody else firstPlay(o) }
        val waiting = t.joiner(t.config(Role.OBSERVER, "lurker"))
        t.until(5_000) { waiting.client.phase == ClientPhase.PENDING }
        val bots = players.values.map { Bot(t, it) }
        t.startDeal()
        bots.forEach { it.poke() }
        t.until(5 * 60_000L) { t.host.revision >= 25 }

        // Only handshake and admission control ever reached it, and its heartbeats carry no revision.
        assertTrue(waiting.received.all { it is Welcome || it is AdmissionPending || it is Heartbeat }, "got ${waiting.received.map { it::class.simpleName }.toSet()}")
        assertTrue(waiting.received.filterIsInstance<Heartbeat>().all { it.revision == null && it.statusRevision == null })
        assertEquals(-1, waiting.client.revision)
        assertNull(waiting.client.viewJson)
        assertEquals(ClientPhase.PENDING, waiting.client.phase)
    }

    @Test fun approvalAdmitsAndOnlyThenSendsTheView() {
        val t = Table(seed = 52)
        val p = t.joiner(t.config(Role.PLAYER, "south", Seat.SOUTH))
        t.until(5_000) { p.client.phase == ClientPhase.PENDING }
        t.run(3_000)
        assertNull(p.client.viewJson, "no view while pending")
        val pending = t.host.pendingAdmissions().single()
        assertEquals(p.client.confirmationCode, pending.confirmationCode, "both ends derive the same confirmation code")
        assertIs<SessionHost.ApprovalResult.Admitted>(t.host.approve(pending.connectionId, checkNotNull(p.client.confirmationCode)))
        t.until(5_000) { p.client.viewJson != null }
        assertEquals(Seat.SOUTH, p.client.seat)
    }

    @Test fun wrongConfirmationCodeIsNotAnApprovalAndDenialCloses() {
        val t = Table(seed = 53)
        val p = t.joiner(t.config(Role.PLAYER, "south", Seat.SOUTH))
        t.until(5_000) { p.client.phase == ClientPhase.PENDING }
        val pending = t.host.pendingAdmissions().single()
        val wrong = ((checkNotNull(p.client.confirmationCode).toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        assertEquals(SessionHost.ApprovalResult.CodeMismatch, t.host.approve(pending.connectionId, wrong))
        assertEquals(1, t.host.pendingAdmissions().size, "still pending after a code mismatch")
        assertTrue(t.host.deny(pending.connectionId))
        t.until(5_000) { p.client.phase == ClientPhase.REJECTED }
        assertEquals(AdmissionDenial.DENIED_BY_HOST, p.client.denial)
        assertTrue(t.host.members().isEmpty())
    }

    @Test fun versionMismatchIsRejectedWithTheHostRangeBeforeAnyHand() {
        val t = Table(seed = 54)
        val players = t.fourPlayers()
        t.startDeal()
        // Protocol 2 (G2, match number): a newer-only client and a version-1 client are both refused at hello.
        for (range in listOf(3..4, 1..1)) {
            val other = t.joiner(t.config(Role.PLAYER, "v${range.first}", Seat.SOUTH, replace = true).copy(protocolMin = range.first, protocolMax = range.last))
            t.until(5_000) { other.client.phase == ClientPhase.REJECTED }
            val rejected = assertIs<HelloRejected>(other.received.single(), "the only message is the rejection")
            assertEquals(HelloRejectReason.VERSION_UNSUPPORTED, rejected.reason)
            assertEquals(2, rejected.protocolMin)
            assertEquals(2, rejected.protocolMax)
        }
        assertTrue(t.host.pendingAdmissions().isEmpty())
        assertEquals(4, players.size)
    }

    @Test fun seatClaimsAndObserverCapacityAreBounded() {
        val t = Table(seed = 55, hostConfig = HostConfig(roomId = ROOM, recovery = TEST_RECOVERY, maxObservers = 1))
        t.player(Seat.SOUTH)
        val second = t.joiner(t.config(Role.PLAYER, "intruder", Seat.SOUTH))
        t.until(5_000) { second.client.phase == ClientPhase.REJECTED }
        assertEquals(AdmissionDenial.SEAT_TAKEN, assertIs<AdmissionDenied>(second.received.last()).reason)

        val o1 = t.observer("o1")
        val o2 = t.joiner(t.config(Role.OBSERVER, "o2"))
        t.until(5_000) { o2.client.phase == ClientPhase.REJECTED }
        // Capacity is judged at join, where a rejoining observer's capability is known.
        assertEquals(AdmissionDenial.OBSERVERS_FULL, o2.client.denial)
        assertEquals(1, t.host.members().count { it.role == Role.OBSERVER })

        // An admitted observer is not a new admission: at capacity it still rejoins.
        val again = t.reconnect(o1)
        t.until(5_000) { again.client.phase == ClientPhase.ADMITTED || again.client.phase == ClientPhase.REJECTED }
        assertEquals(ClientPhase.ADMITTED, again.client.phase, "rejected: ${again.client.helloRejection} ${again.client.denial}")
    }

    @Test fun observerDoesNotTakeASeat() {
        val t = Table(seed = 56)
        val players = t.fourPlayers()
        t.observer("o1")
        t.observer("o2")
        val seated = t.host.members().filter { it.seat != null }
        assertEquals(Seat.entries.toSet(), seated.map { it.seat }.toSet())
        assertEquals(2, t.host.members().count { it.role == Role.OBSERVER && it.seat == null })
        t.startDeal()
        assertEquals(4, players.size)
    }

    private fun firstPlay(o: cn.wpgd.app.session.host.SeatOptions): cn.wpgd.app.session.wire.CommandBody {
        val a = o.plays.minBy { it.cards.size }
        return cn.wpgd.app.session.wire.PlayBody(a.cards.map { it.id }, a.declaration?.map { cn.wpgd.app.session.wire.WireAssignment(it.rank, it.suit) }, a.combination.type)
    }
}
