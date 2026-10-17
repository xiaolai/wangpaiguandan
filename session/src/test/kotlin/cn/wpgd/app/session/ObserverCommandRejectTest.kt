package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.StateHash
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.wire.AskCountBody
import cn.wpgd.app.session.wire.CommandMessage
import cn.wpgd.app.session.wire.GiveTributeBody
import cn.wpgd.app.session.wire.PassBody
import cn.wpgd.app.session.wire.PlayBody
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.session.wire.RejectionReceipt
import cn.wpgd.app.session.wire.ResistBody
import cn.wpgd.app.session.wire.ReturnTributeBody
import cn.wpgd.app.session.wire.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** GDN-070: a crafted observer command changes neither turn nor hand, and is refused typed. */
class ObserverCommandRejectTest {
    @Test fun everyGameCommandFromAnObserverIsRefused() {
        val t = Table(seed = 91)
        val players = t.fourPlayers()
        val o = t.observer("watcher")
        t.startDeal()
        val turnSeat = t.turn()
        val card = players.getValue(turnSeat).client.memberView!!.deal!!.hand.first().id
        val before = StateHash.of(checkNotNull(t.host.state.deal))
        val revision = t.host.revision
        for (body in listOf(PassBody, PlayBody(listOf(card), emptyList(), null), GiveTributeBody(card), ReturnTributeBody(card), ResistBody, AskCountBody(Seat.EAST))) {
            val r = assertIs<RejectionReceipt>(t.command(o, body), "$body")
            assertEquals(RejectionCode.NOT_A_PLAYER, r.code)
        }
        assertEquals(before, StateHash.of(checkNotNull(t.host.state.deal)))
        assertEquals(revision, t.host.revision)
        assertEquals(turnSeat, t.turn())
        assertEquals(6, t.host.metrics.observerCommandsRejected)
    }

    @Test fun observerCannotBorrowAnotherCapabilityOrTakeASeat() {
        val t = Table(seed = 92)
        t.fourPlayers()
        val o = t.observer("watcher")
        t.startDeal()
        val forged = CommandMessage(o.client.epoch!!, "A".repeat(43), "forged-1", t.host.revision, PassBody)
        o.client.sendRaw(forged)
        t.until(5_000) { o.received.any { it is RejectionReceipt } }
        assertEquals(RejectionCode.BAD_CAPABILITY, o.received.filterIsInstance<RejectionReceipt>().single().code)

        // An observer handshake cannot claim a seat.
        val claim = t.joiner(t.config(Role.OBSERVER, "claimer").copy(seat = Seat.SOUTH, replace = true))
        t.until(5_000) { claim.client.phase == ClientPhase.REJECTED }
        assertEquals(cn.wpgd.app.session.wire.AdmissionDenial.ROLE_MISMATCH, claim.client.denial)
    }
}
