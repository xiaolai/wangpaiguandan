package cn.wpgd.app.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The NSD adapter's platform-free parts: conflict-renamed service names and advert withdrawal. */
class NsdRegistrationsTest {
    @Test fun aServiceRenamedOnAConflictStillCarriesItsRoom() {
        assertTrue(serviceNameMatchesRoom("room-1", "room-1"))
        assertTrue(serviceNameMatchesRoom("room-1 (2)", "room-1"))
        assertTrue(serviceNameMatchesRoom("room-1 (13)", "room-1"))
        assertFalse(serviceNameMatchesRoom("room-2", "room-1"))
        assertFalse(serviceNameMatchesRoom("room-1 (x)", "room-1"))
        assertFalse(serviceNameMatchesRoom("room-1(2)", "room-1"))
        assertFalse(serviceNameMatchesRoom("room-10", "room-1"))
        assertFalse(serviceNameMatchesRoom("xroom-1 (2)", "room-1"))
    }

    private class Platform {
        val calls = ArrayList<String>()
        var throwNext: RuntimeException? = null
        fun unregister(l: String) {
            calls += l
            throwNext?.let { throwNext = null; throw it }
        }
    }

    @Test fun anUnregistrationThatThrowsIsRetriedNotForgotten() {
        val p = Platform()
        val r = NsdRegistrations(p::unregister)
        r.put("room-1", "L1")
        p.throwNext = IllegalStateException("busy")
        r.withdraw("room-1")
        assertEquals(1, r.withdrawing)
        r.withdraw("room-1") // the next call retries the failed withdrawal
        assertEquals(listOf("L1", "L1"), p.calls)
        r.unregistered("L1")
        assertEquals(0, r.withdrawing)
    }

    @Test fun anUnregistrationThatFailsLaterIsReportedNotRetried() {
        // Round 3 #5: Android forgets the listener before onUnregistrationFailed, so a retry would only
        // be refused (IllegalArgumentException) and then read as a successful withdrawal.
        val p = Platform()
        val r = NsdRegistrations(p::unregister)
        r.put("room-1", "L1")
        r.withdraw("room-1")
        r.unregistrationFailed("L1")
        assertEquals(0, r.withdrawing)
        assertEquals(1, r.stranded)
        r.put("room-2", "L2")
        r.withdraw("room-2")
        assertEquals("no retry of the forgotten listener", listOf("L1", "L2"), p.calls)
    }

    @Test fun aFailedRegistrationNeedsNoWithdrawal() {
        val p = Platform()
        val r = NsdRegistrations(p::unregister)
        r.put("room-1", "L1")
        assertTrue("the current registration failed: the room is not advertised", r.registrationFailed("room-1", "L1"))
        r.withdraw("room-1")
        assertTrue(p.calls.isEmpty())
        assertEquals(0, r.withdrawing)
    }

    @Test fun aReplacedRegistrationFailingDoesNotSpeakForTheRoom() {
        val r = NsdRegistrations(Platform()::unregister)
        r.put("room-1", "L1")
        r.withdraw("room-1")
        r.put("room-1", "L2")
        assertFalse(r.registrationFailed("room-1", "L1"))
        assertTrue(r.registrationFailed("room-1", "L2"))
    }

    /** Audit #15: a discovery that failed to start is retried, later each time, then given up (and logged). */
    @Test fun aFailedDiscoveryStartIsRetriedABoundedNumberOfTimes() {
        val s = DiscoveryStarts(maxAttempts = 4, baseDelayMs = 1_000)
        val delays = ArrayList<Long?>()
        repeat(4) {
            s.started()
            delays += s.failed()
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, null), delays)
        assertEquals(4, s.attempts)
    }

    /**
     * Round 3 #9: a start that races a stop either registers before the stop (which then undoes it) or
     * not at all; it never registers after the stop finished, leaving discovery running.
     */
    @Test fun aStartRacingAStopNeverLeavesDiscoveryRunning() {
        val calls = java.util.Collections.synchronizedList(ArrayList<String>())
        val inRegister = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val d = DiscoveryRegistration(
            register = {
                inRegister.countDown()
                release.await()
                calls += "register"
            },
            unregister = { calls += "unregister" },
        )
        val starter = Thread { d.start() }.apply { start() }
        assertTrue(inRegister.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val stopper = Thread { d.stop() }.apply { start() }
        stopper.join(200)
        assertTrue("the stop waits for the registration in progress", stopper.isAlive)
        release.countDown()
        starter.join(2_000)
        stopper.join(2_000)
        assertEquals(listOf("register", "unregister"), calls.toList())
        assertFalse("nothing registers after the stop", d.start())
        assertEquals(listOf("register", "unregister"), calls.toList())
    }

    @Test fun aStopBeforeTheStartRegistersNothing() {
        val calls = ArrayList<String>()
        val d = DiscoveryRegistration(register = { calls += "register" }, unregister = { calls += "unregister" })
        d.stop()
        assertFalse(d.start())
        d.stop()
        assertEquals(listOf("unregister"), calls)
    }
}
