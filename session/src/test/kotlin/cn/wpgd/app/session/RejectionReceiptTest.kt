package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.StateHash
import cn.wpgd.app.session.wire.PassBody
import cn.wpgd.app.session.wire.PlayBody
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.session.wire.RejectionReceipt
import cn.wpgd.app.session.wire.Snapshot
import cn.wpgd.app.session.wire.WireAssignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Audit A20, GDN-028: every refused command reaches its issuer as a typed rejection, from any seat. */
class RejectionReceiptTest {
    @Test fun everySeatGetsATypedRejectionAndNothingChanges() {
        val t = Table(seed = 41)
        val players = t.fourPlayers()
        t.startDeal()
        val turn = t.turn()
        val before = StateHash.of(checkNotNull(t.host.state.deal))
        for (seat in Seat.entries.filter { it != turn }) {
            val p = players.getValue(seat)
            val snapshots = p.received.count { it is Snapshot }
            val r = assertIs<RejectionReceipt>(t.command(p, PassBody), "$seat must receive the rejection")
            assertEquals(RejectionCode.RULE, r.code)
            assertEquals("Deal.WrongSeat", r.reason)
            assertEquals(t.host.revision, r.revision)
            assertEquals(snapshots, p.received.count { it is Snapshot }, "a rejection sends no new state")
        }
        assertEquals(before, StateHash.of(checkNotNull(t.host.state.deal)))
    }

    @Test fun ruleStaleRevisionAndMalformedRejectionsAreDistinct() {
        val t = Table(seed = 42)
        val players = t.fourPlayers()
        t.startDeal()
        val actor = players.getValue(t.turn())
        val other = players.getValue(t.turn().next)
        val notMine = checkNotNull(other.client.memberView).deal!!.hand.first().id

        val unowned = assertIs<RejectionReceipt>(t.command(actor, PlayBody(listOf(notMine), emptyList(), null)))
        assertEquals(RejectionCode.RULE, unowned.code)
        assertEquals("Deal.UnownedCard", unowned.reason)

        val badDeclaration = assertIs<RejectionReceipt>(t.command(actor, PlayBody(listOf(actor.client.memberView!!.deal!!.hand.first().id), listOf(WireAssignment(99, null)), null)))
        assertEquals(RejectionCode.MALFORMED, badDeclaration.code)

        // Act, then a second command still built on the old revision is refused as stale.
        val rev = actor.client.revision
        t.command(actor, simpleBody(checkNotNull(actor.client.memberView)))
        val next = players.getValue(t.turn())
        t.run(100)
        val forged = cn.wpgd.app.session.wire.CommandMessage(next.client.epoch!!, next.client.capability!!, "stale-1", rev, PassBody)
        next.client.sendRaw(forged)
        t.until(5_000) { next.received.any { it is RejectionReceipt && it.commandId == "stale-1" } }
        val stale = next.received.filterIsInstance<RejectionReceipt>().single { it.commandId == "stale-1" }
        assertEquals(RejectionCode.STALE_REVISION, stale.code)
        assertEquals(t.host.revision, stale.revision)
    }
}
