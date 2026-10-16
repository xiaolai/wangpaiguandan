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
}
