package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.transport.LinkFaults
import cn.wpgd.app.session.wire.AdmissionDenial
import cn.wpgd.app.session.wire.CloseReason
import cn.wpgd.app.session.wire.Receipt
import cn.wpgd.app.session.wire.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** GDN-016, audit A15: an approved replacement invalidates the old capability in the same step; cards stay. */
class ReplacementTest {
    @Test fun approvedReplacementInvalidatesTheOldCapabilityAndKeepsTheHand() {
        val t = Table(seed = 61)
        val players = t.fourPlayers()
        val bots = players.values.map { Bot(t, it) }
        t.startDeal()
        bots.forEach { it.poke() }
        t.until(60_000) { t.host.revision >= 12 }
        bots.forEach { it.enabled = false }
        t.run(500)

        val seat = Seat.EAST
        val old = players.getValue(seat)
        val oldCapability = checkNotNull(old.client.capability)
        val handBefore = checkNotNull(old.client.memberView).deal!!.hand
        val finishedBefore = t.host.state.deal!!.finishOrder

        val newcomer = t.joiner(t.config(Role.PLAYER, "sub", seat, replace = true))
        t.until(5_000) { newcomer.client.phase == ClientPhase.PENDING }
        val p = t.host.pendingAdmissions().single()
        assertIs<SessionHost.ApprovalResult.Admitted>(t.host.approve(p.connectionId, checkNotNull(newcomer.client.confirmationCode)))
        t.until(5_000) { newcomer.client.viewJson != null && old.client.phase == ClientPhase.CLOSED }

        assertEquals(CloseReason.REPLACED, old.client.closeReason)
        assertEquals(handBefore, newcomer.client.memberView!!.deal!!.hand, "the replacement keeps the seat's cards")
        assertEquals(finishedBefore, t.host.state.deal!!.finishOrder)
        assertNotEquals(oldCapability, newcomer.client.capability)
        assertEquals(1, t.host.members().count { it.seat == seat })

        // The old capability no longer admits anyone.
        val ghost = t.joiner(t.config(Role.PLAYER, "ghost", seat, rejoin = oldCapability))
        t.until(5_000) { ghost.client.phase == ClientPhase.REJECTED }
        assertEquals(AdmissionDenial.CAPABILITY_INVALID, ghost.client.denial)

        // The new capability acts for the seat.
        if (t.turn() == seat) assertIs<Receipt>(t.command(newcomer, simpleBody(newcomer.client.memberView!!)))
    }

    @Test fun aCommandInFlightFromTheOldMemberDoesNotActAfterApproval() {
        val t = Table(seed = 62)
        val slow = LinkFaults(latencyMs = 400)
        val players = Seat.entries.associateWith { if (it == Seat.SOUTH) t.player(it, slow) else t.player(it) }
        t.startDeal()
        while (t.turn() != Seat.SOUTH) {
            val actor = players.getValue(t.turn())
            t.command(actor, simpleBody(actor.client.memberView!!))
            t.run(1_000)
        }
        t.until(5_000) { players.getValue(Seat.SOUTH).client.revision == t.host.revision }
        val revision = t.host.revision
        val old = players.getValue(Seat.SOUTH)
        val newcomer = t.joiner(t.config(Role.PLAYER, "sub", Seat.SOUTH, replace = true))
        t.until(5_000) { newcomer.client.phase == ClientPhase.PENDING }
        // The old member acts now; its command is still on the wire when the host approves the replacement.
        old.client.submit(simpleBody(old.client.memberView!!))
        t.run(100)
        val p = t.host.pendingAdmissions().single()
        assertIs<SessionHost.ApprovalResult.Admitted>(t.host.approve(p.connectionId, checkNotNull(newcomer.client.confirmationCode)))
        t.run(3_000)
        assertEquals(revision, t.host.revision, "the replaced member's command must not change the game")
        assertTrue(t.host.members().single { it.seat == Seat.SOUTH }.nickname == "sub")
    }
}
