package cn.wpgd.app.session.storage

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.host.EndReason
import cn.wpgd.app.session.host.RoomStatus
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.wire.CloseReason
import cn.wpgd.app.session.wire.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Room-status transitions that must not happen (findings 51, 52): an ended table cannot be suspended
 * (that would overwrite its durable ENDED status and revive capabilities on restore), and a suspended
 * table admits nobody until it is restored.
 */
class SuspendEndTransitionTest {
    @Test fun anEndedTableCannotBeSuspendedAndStaysEndedAfterARestore() {
        val saved = SavedTable(181)
        saved.t.player(Seat.SOUTH)
        assertTrue(saved.host.end(T0 + 1, T0 + 100))
        assertFailsWith<IllegalStateException> { saved.host.suspend(T0 + 2, T0 + 200) }
        assertEquals(RoomStatus.Ended(EndReason.HOST_ENDED), saved.host.status)
        saved.crash()
        val r = saved.restored()
        assertEquals(RoomStatus.Ended(EndReason.HOST_ENDED), r.host.status, "the durable status is still ENDED")
    }

    @Test fun aSuspendedTableAdmitsNobody() {
        val saved = SavedTable(182)
        val t = saved.t
        val south = t.player(Seat.SOUTH)
        val capability = checkNotNull(south.client.capability)
        // A joiner already waiting for approval when the table is suspended.
        val waiting = t.joiner(t.config(Role.OBSERVER, "早到"))
        t.until(5_000) { waiting.client.phase == ClientPhase.PENDING }
        val pending = t.host.pendingAdmissions().single()
        assertTrue(saved.host.suspend(T0 + 1, T0 + 100_000))
        t.run(500)
        assertEquals(CloseReason.SUSPENDED, south.client.closeReason)
        assertIs<SessionHost.ApprovalResult>(t.host.approve(pending.connectionId, pending.confirmationCode)).let {
            assertTrue(it !is SessionHost.ApprovalResult.Admitted, "approved after suspension: $it")
        }
        // New connections after suspension: a newcomer and an old capability.
        val newcomer = t.joiner(t.config(Role.OBSERVER, "迟到"))
        val ghost = t.joiner(t.config(Role.PLAYER, "south", Seat.SOUTH, rejoin = capability))
        t.run(5_000)
        assertTrue(t.host.pendingAdmissions().isEmpty(), "a suspended table queues nobody for approval")
        for (p in listOf(newcomer, ghost)) {
            val pc = p.client
            assertTrue(pc.phase != ClientPhase.ADMITTED && pc.phase != ClientPhase.PENDING, "${pc.config.nickname} got ${pc.phase}")
            assertEquals(CloseReason.SUSPENDED, pc.closeReason, pc.config.nickname)
        }
        assertTrue(t.host.members().none { it.connected })
    }
}
