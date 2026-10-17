package cn.wpgd.app.owner

import cn.wpgd.app.BlockingWorkerDecisionExecutor
import cn.wpgd.app.TestProfiles
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.nickname
import cn.wpgd.app.session.wire.PassBody
import cn.wpgd.app.testCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 3 #9: a failed owner's table accepts no more input, though its link is still up and the room active. */
class FailedOwnerInputTest {
    private val loop = VirtualSessionLoop()
    private var onError: ((Throwable) -> Unit)? = null
    private val owner = SessionOwner(
        OwnerDependencies(
            profiles = TestProfiles.profiles,
            crypto = testCrypto(91),
            loopFactory = { loop },
            decisionExecutorFactory = { w, _, e -> BlockingWorkerDecisionExecutor(w).also { onError = e } },
        ),
    )

    private fun startSolo() {
        assertTrue(owner.startSolo(nickname()))
        assertTrue(loop.scheduler.runUntil(60_000) { owner.state.value.table?.view?.match?.phase == MatchPhase.PLAYING })
    }

    @Test fun aSubmissionAfterTheFailureIsRefused() {
        startSolo()
        checkNotNull(onError)(IllegalStateException("boom"))
        val t = checkNotNull(owner.state.value.table)
        assertEquals(OwnerLifecycle.FAILED, owner.state.value.lifecycle)
        assertEquals(ConnectionState.CONNECTED, t.connection)
        assertEquals(RoomCondition.ACTIVE, t.room)
        assertEquals(SubmitResult.Refused(SubmitRefusal.FAILED), owner.submit(PassBody))
        owner.shutdown()
    }

    /** Audit #16: the host's own controls are input too; only the ones that wind the table down stay open. */
    @Test fun aFailedOwnerResumesNothingButStillPauses() {
        startSolo()
        owner.pause()
        assertTrue(loop.scheduler.runUntil(5_000) { owner.state.value.table?.room == RoomCondition.PAUSED })
        checkNotNull(onError)(IllegalStateException("boom"))
        owner.resume()
        loop.scheduler.runFor(2_000)
        assertEquals("a failed table is not resumed", RoomCondition.PAUSED, owner.state.value.table?.room)
        owner.shutdown()
    }

    @Test fun aResumeQueuedBeforeTheFailureDoesNotRun() {
        startSolo()
        owner.pause()
        assertTrue(loop.scheduler.runUntil(5_000) { owner.state.value.table?.room == RoomCondition.PAUSED })
        owner.resume()
        checkNotNull(onError)(IllegalStateException("boom"))
        loop.scheduler.runFor(2_000)
        assertEquals(RoomCondition.PAUSED, owner.state.value.table?.room)
        owner.shutdown()
    }

    @Test fun aCommandAcceptedJustBeforeTheFailureIsNotSent() {
        startSolo()
        val sent = owner.metrics.commandsSent.get()
        assertTrue(owner.submit(PassBody) is SubmitResult.Sent)
        checkNotNull(onError)(IllegalStateException("boom"))
        loop.scheduler.runFor(1_000)
        assertEquals("never handed to the client", sent, owner.metrics.commandsSent.get())
        assertNull(owner.state.value.table?.pending)
        owner.shutdown()
    }

    /**
     * Round 3 #10: a failed owner's host stops moving the game on by itself: AI seats, deadlines and
     * remote commands no longer change the table while the local player is locked out.
     */
    @Test fun aFailedOwnersHostStopsTheGame() {
        startSolo()
        checkNotNull(onError)(IllegalStateException("boom"))
        loop.scheduler.runFor(1_000)
        val t = checkNotNull(owner.state.value.table)
        assertEquals(RoomCondition.PAUSED, t.room)
        loop.scheduler.runFor(300_000)
        assertEquals("nothing moved after the failure", t.revision, owner.state.value.table?.revision)
        owner.shutdown()
    }

    /** Round 3 #13: an owner that failed before any table started stays failed: no table starts. */
    @Test fun anOwnerThatFailedBeforeStartingStartsNothing() {
        var failLoop: ((Throwable) -> Unit)? = null
        val failedLoop = VirtualSessionLoop()
        val o = SessionOwner(
            OwnerDependencies(
                profiles = TestProfiles.profiles,
                crypto = testCrypto(92),
                loopFactory = { e -> failedLoop.also { failLoop = e } },
            ),
        )
        checkNotNull(failLoop)(IllegalStateException("boom"))
        assertEquals(false, o.startSolo(nickname()))
        failedLoop.scheduler.runFor(5_000)
        assertEquals(OwnerLifecycle.FAILED, o.state.value.lifecycle)
        assertNull(o.state.value.table)
        o.shutdown()
    }
}
