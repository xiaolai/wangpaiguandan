package cn.wpgd.app.session.storage

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.host.PauseReason
import cn.wpgd.app.session.host.RoomStatus
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.simpleBody
import cn.wpgd.app.session.wire.Admitted
import cn.wpgd.app.session.wire.Receipt
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.session.wire.RejectionReceipt
import cn.wpgd.app.session.wire.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * GDN-083: a failed write of the log never produces a receipt. The room pauses (no member sees the
 * revision, no AI acts, the host cannot simply resume), and a restart from storage decides what is
 * durable, never going below the high-water mark.
 */
class StorageFailurePausesTest {
    private fun commitSteps(seed: Long): List<InMemoryStorage.Op> {
        val saved = SavedTable(seed)
        val players = saved.playersAt(10)
        val before = saved.storage.step
        val (actor, id) = saved.submitTurn(players)
        saved.t.until(5_000) { id in actor.client.replies }
        return saved.storage.ops.filter { it.step > before }
    }

    @Test fun aFailureAtAnyWriteStepPausesWithNoReceiptAndRestartDecides() {
        val seed = 901L
        val steps = commitSteps(seed)
        assertEquals(7, steps.size, "record, sync, marker, sync, then the high-water mark in three steps: $steps")
        val markerSync = steps[3].step
        for (op in steps) {
            val saved = SavedTable(seed)
            val players = saved.playersAt(10)
            saved.storage.failAt += op.step
            val others = players.values.associateWith { it.client.revision }
            val (actor, id) = saved.submitTurn(players)
            saved.t.until(5_000) { id in actor.client.replies }

            val reply = actor.client.replies.getValue(id)
            assertIs<RejectionReceipt>(reply, "step ${op.step} ${op.kind}: $reply")
            assertEquals(RejectionCode.ROOM_PAUSED, reply.code)
            assertEquals(RoomStatus.Paused(setOf(PauseReason.STORAGE_FAILURE)), saved.host.status)
            assertEquals(10, saved.host.revision, "not applied")
            saved.t.run(5_000)
            for ((p, rev) in others) assertEquals(rev, p.client.revision, "no member saw the failed revision")
            assertEquals(1, saved.host.metrics.storageFailures)

            // Paused: further game commands are refused, the host cannot just resume, nothing advances.
            val again = checkNotNull(actor.client.submit(simpleBody(checkNotNull(actor.client.memberView))))
            saved.t.until(5_000) { again in actor.client.replies }
            assertEquals(RejectionCode.ROOM_PAUSED, assertIs<RejectionReceipt>(actor.client.replies[again]).code)
            assertFalse(saved.host.resume())
            assertIs<SessionHost.AdvanceResult.RoomNotActive>(saved.host.advance())

            // Restart from storage: r11 exactly when its marker became durable before the failure.
            saved.crash()
            val r = saved.restored()
            val expected = if (op.step > markerSync) 11L else 10L
            assertEquals(expected, r.host.revision, "step ${op.step} ${op.kind}")
            assertTrue(r.host.revision >= checkNotNull(readHwm(saved.storage)))
            val p = saved.rejoin(actor)
            p.client.resend(id)
            saved.t.run(500)
            if (expected == 11L) {
                assertEquals(11, assertIs<Receipt>(p.client.repeatedReplies.last()).revision, "the durable command's original receipt")
            } else {
                // Never acknowledged and not durable: judged afresh, applied once.
                assertEquals(11, assertIs<Receipt>(p.client.repeatedReplies.last()).revision)
            }
            assertEquals(11, saved.host.revision)
            assertEquals(1, committedCommandIds(saved.storage.fork()).count { it == id })
        }
    }

    @Test fun aFailedMemberSaveAdmitsNobody() {
        val saved = SavedTable(902)
        saved.storage.failWhen = { kind, name -> kind == InMemoryStorage.Kind.REPLACE_WRITE && name.endsWith("/members") }
        val joiner = saved.t.joiner(saved.t.config(Role.PLAYER, "south", Seat.SOUTH))
        saved.t.until(5_000) { joiner.client.phase == ClientPhase.PENDING }
        val result = saved.host.approve(saved.host.pendingAdmissions().single().connectionId, checkNotNull(joiner.client.confirmationCode))
        assertIs<SessionHost.ApprovalResult.RoomNotActive>(result)
        saved.t.run(1_000)
        assertTrue(joiner.received.none { it is Admitted }, "a capability that is not durable is never handed out")
        assertEquals(emptyList(), saved.host.members())
    }

    /** A replacement that cannot be saved changes nothing: the old member keeps its seat, connection and capability. */
    @Test fun aFailedReplacementSaveKeepsTheOldMember() {
        val saved = SavedTable(905)
        val old = saved.t.player(Seat.SOUTH)
        val before = saved.host.members()
        saved.storage.failWhen = { kind, name -> kind == InMemoryStorage.Kind.REPLACE_WRITE && name.endsWith("/members") }
        val sub = saved.t.joiner(saved.t.config(Role.PLAYER, "sub", Seat.SOUTH, replace = true))
        saved.t.until(5_000) { sub.client.phase == ClientPhase.PENDING }
        val statuses = old.received.count { it is cn.wpgd.app.session.wire.Status }
        val result = saved.host.approve(saved.host.pendingAdmissions().single().connectionId, checkNotNull(sub.client.confirmationCode))
        assertIs<SessionHost.ApprovalResult.RoomNotActive>(result)
        saved.t.run(1_000)
        assertEquals(before, saved.host.members(), "the unsaved replacement changed the roster")
        assertEquals(ClientPhase.ADMITTED, old.client.phase, "the old member was disconnected by an unsaved replacement")
        assertTrue(old.received.drop(statuses).filterIsInstance<cn.wpgd.app.session.wire.Status>().none { st ->
            st.room.seats.single { it.seat == Seat.SOUTH }.nickname == "sub"
        }, "an unsaved seat owner was broadcast")
        assertTrue(sub.received.none { it is Admitted })
    }

    /** A failed status write (suspend or end) leaves the on-disk status unknown: pause, close nothing, accept no commit. */
    @Test fun aFailedStatusWritePausesTheRoomAndRefusesFurtherCommits() {
        for (ending in listOf(false, true)) {
            val saved = SavedTable(904)
            val players = saved.playersAt(10)
            saved.storage.failWhen = { kind, name -> kind == InMemoryStorage.Kind.REPLACE_WRITE && name.endsWith("/meta") }
            val ok = if (ending) saved.host.end(T0 + 1, T0 + 2) else saved.host.suspend(T0 + 1, T0 + 2)
            assertFalse(ok, "ending=$ending: a status that was not saved is not reported as done")
            assertEquals(RoomStatus.Paused(setOf(PauseReason.STORAGE_FAILURE)), saved.host.status)
            saved.storage.failWhen = { _, _ -> false }
            val (actor, id) = saved.submitTurn(players)
            saved.t.until(5_000) { id in actor.client.replies }
            assertEquals(RejectionCode.ROOM_PAUSED, assertIs<RejectionReceipt>(actor.client.replies.getValue(id)).code)
            assertEquals(10, saved.host.revision, "ending=$ending: no commit after the failed status write")
        }
    }

    @Test fun aFailedCheckpointDoesNotPauseTheRoom() {
        val saved = SavedTable(903, config = savedConfig(checkpointEvery = 4))
        saved.storage.failWhen = { _, name -> "/checkpoint." in name }
        Seat.entries.forEach { saved.host.setAi(it, standIn) }
        saved.driveUntil { it.revision >= 20 }
        assertEquals(RoomStatus.Active, saved.host.status)
        assertTrue(saved.host.metrics.checkpointFailures >= 4)
        saved.crash()
        assertEquals(saved.restored().host.revision, 20)
    }
}
