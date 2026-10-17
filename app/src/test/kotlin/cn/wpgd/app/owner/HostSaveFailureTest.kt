package cn.wpgd.app.owner

import cn.wpgd.app.TestProfiles
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.nickname
import cn.wpgd.app.room.InMemoryRoomNetwork
import cn.wpgd.app.room.RoomSettings
import cn.wpgd.app.room.RoomStore
import cn.wpgd.app.session.diagnostics.DiagnosticsReport
import cn.wpgd.app.session.storage.InMemoryStorage
import cn.wpgd.app.session.transport.VirtualScheduler
import cn.wpgd.app.testCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit #19: a hosted table whose first save fails is a storage failure in diagnostics, not an illegal state. */
class HostSaveFailureTest {
    @Test fun aFailedFirstSaveIsReportedAsAnIoFailure() {
        val storage = InMemoryStorage().apply { failWhen = { _, _ -> true } }
        val owner = SessionOwner(
            OwnerDependencies(
                profiles = TestProfiles.profiles,
                crypto = testCrypto(191),
                // The production loop: a task that throws is reported to the owner, which fails.
                loopFactory = { onError -> ExecutorSessionLoop(onError = onError) },
                network = InMemoryRoomNetwork(VirtualScheduler(), 191),
                storage = storage,
                rooms = RoomStore(InMemoryKeyValueStore()),
            ),
        )
        try {
            assertTrue(owner.hostRoom(nickname("阿梅"), RoomSettings("周末掼蛋")))
            val end = System.currentTimeMillis() + 10_000
            while (owner.state.value.lifecycle != OwnerLifecycle.FAILED) {
                check(System.currentTimeMillis() < end) { "the owner did not fail" }
                Thread.sleep(5)
            }
            assertNull(owner.state.value.table)
            val failure = owner.state.value.failure.orEmpty()
            assertTrue(failure, failure.startsWith("IOException: the table could not be saved"))

            val b = DiagnosticsReport.Builder()
            owner.writeCounters(b)
            assertEquals("owner.failure_kind=IO", b.build().render().lines().single { it.startsWith("owner.failure_kind=") })
        } finally {
            owner.shutdown()
        }
    }
}
