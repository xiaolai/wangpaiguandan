package cn.wpgd.app.owner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import cn.wpgd.app.TestProfiles
import cn.wpgd.app.VirtualSolo
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.nickname
import cn.wpgd.app.table.TableViewModel
import cn.wpgd.app.testCrypto
import cn.wpgd.app.untilMyPlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

/** GDN-089: the table outlives the screen; shutting the owner down cancels every job it runs. */
class SessionOwnerLifecycleTest {
    private fun store(vm: () -> TableViewModel): Pair<ViewModelStore, TableViewModel> {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = vm() as T
        }
        return store to ViewModelProvider.create(store, factory)[TableViewModel::class]
    }

    @Test fun recreatingTheScreenKeepsTheTable() {
        val solo = VirtualSolo().start()
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val (store1, vm1) = store { solo.viewModel(scope1) }
        assertTrue(solo.untilMyPlay(vm1))
        val before = vm1.uiState.value
        vm1.tap(before.hand.rows.first().first().cards.first().id)

        // Rotation / activity recreation: the old screen's view model is cleared, its scope cancelled.
        store1.clear()
        assertTrue(scope1.coroutineContext[Job]!!.isCancelled)
        assertFalse("a recreated Activity does not restart the table", solo.owner.startSolo(nickname()))

        val (_, vm2) = store { solo.viewModel() }
        val after = vm2.uiState.value
        assertEquals(before.dealNumber, after.dealNumber)
        assertEquals(before.trick, after.trick)
        assertEquals(before.hand.rows.flatten().flatMap { g -> g.cards.map { it.id } }.toSet(), after.hand.rows.flatten().flatMap { g -> g.cards.map { it.id } }.toSet())
        assertEquals(solo.table!!.revision, solo.owner.state.value.table!!.revision)
        assertEquals("same connection, nothing rejoined", 1L, solo.owner.metrics.connections.get())
        assertTrue(after.controlsEnabled)
        // The table keeps running after the old screen is gone.
        val human = cn.wpgd.app.ScriptedHuman(vm2, { solo.table?.view })
        assertTrue(human.act())
        val rev = solo.table!!.revision
        solo.run(2_000)
        assertTrue(solo.table!!.revision > rev)
    }

    @Test fun shutdownCancelsEveryJobOnRealThreads() {
        fun ownerThreads() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("wpgd-") }.toSet()
        val earlier = ownerThreads()
        val failures = ArrayList<Throwable>()
        val owner = SessionOwner(
            OwnerDependencies(
                profiles = TestProfiles.profiles,
                crypto = testCrypto(5),
                loopFactory = { onError -> ExecutorSessionLoop(onError = { failures += it; onError(it) }) },
                solo = SoloConfig(aiDelayMs = 5),
            ),
        )
        assertTrue(owner.startSolo(nickname()))
        val deadline = System.currentTimeMillis() + 20_000
        while (owner.state.value.table?.view?.match?.phase != MatchPhase.PLAYING) {
            assertTrue("table did not start", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
        assertTrue("heartbeats, retries and AI timers are scheduled", owner.scheduledTasks > 0)
        // The session thread runs now; the AI worker's thread starts with the first AI decision.
        assertTrue("wpgd-session" in (ownerThreads() - earlier).map { it.name })
        val collector = owner.scope.launch { awaitCancellation() }

        owner.shutdown()

        assertEquals(0, owner.scheduledTasks)
        assertTrue(owner.terminated)
        assertTrue(collector.isCancelled)
        assertEquals(OwnerLifecycle.SHUT_DOWN, owner.state.value.lifecycle)
        assertEquals(SubmitResult.Refused(SubmitRefusal.SHUT_DOWN), owner.submit(cn.wpgd.app.session.wire.PassBody))
        assertFalse(owner.startSolo(nickname()))
        assertEquals("this owner's threads have stopped", emptySet<Thread>(), ownerThreads() - earlier)
        val frozen = owner.state.value
        Thread.sleep(300)
        assertEquals("nothing runs after shutdown", frozen, owner.state.value)
        assertTrue(failures.isEmpty())
        owner.shutdown() // idempotent
    }

    @Test fun preferencesAreReadByANewViewModelWithoutTouchingTheOwner() {
        val solo = VirtualSolo().start()
        val prefs = PreferencesRepository(InMemoryKeyValueStore())
        val vm = TableViewModel(solo.owner, prefs, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        vm.updatePreferences { it.copy(sound = false) }
        val vm2 = TableViewModel(solo.owner, prefs, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        assertFalse(vm2.uiState.value.preferences.sound)
        assertNotNull(solo.table)
    }
}
