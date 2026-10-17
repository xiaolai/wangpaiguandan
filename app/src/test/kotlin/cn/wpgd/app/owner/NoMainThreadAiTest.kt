package cn.wpgd.app.owner

import cn.wpgd.app.ScriptedHuman
import cn.wpgd.app.TestProfiles
import cn.wpgd.app.core.ai.AiAction
import cn.wpgd.app.core.ai.AiPolicies
import cn.wpgd.app.core.ai.AiPolicy
import cn.wpgd.app.core.ai.DecisionContext
import cn.wpgd.app.core.ai.PolicyInput
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.nickname
import cn.wpgd.app.table.TableViewModel
import cn.wpgd.app.testCrypto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * GDN-102, audit A27 (hint and AI ran synchronously on the UI): no AI work runs on the main thread. Real threads: a stand-in main thread (dispatching
 * like Dispatchers.Main.immediate) drives the screen, the session loop and the AI worker are the
 * owner's own. Every AI decision is recorded with its thread.
 */
class NoMainThreadAiTest {
    private val main = Executors.newSingleThreadExecutor { r -> Thread(r, "test-main") }
    private val mainThread: Thread = main.submit<Thread> { Thread.currentThread() }.get()

    /** Like Dispatchers.Main.immediate: inline when already on main, otherwise posted to it. */
    private val mainDispatcher = object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== mainThread
        override fun dispatch(context: CoroutineContext, block: Runnable) = main.execute(block)
    }

    private val aiThreads = Collections.synchronizedSet(HashSet<String>())

    private val recording: AiPolicy = object : AiPolicy {
        private val inner = AiPolicies[AiPolicies.BASELINE]
        override val id = inner.id
        override fun decide(input: PolicyInput, context: DecisionContext): AiAction {
            aiThreads += Thread.currentThread().name
            return inner.decide(input, context)
        }
    }

    @Test fun aiDecisionsRunOnTheWorkerNeverOnMainOrTheSessionLoop() {
        val owner = SessionOwner(
            OwnerDependencies(
                profiles = TestProfiles.profiles,
                crypto = testCrypto(13),
                loopFactory = { onError -> ExecutorSessionLoop(onError = onError) },
                aiPolicies = { recording },
                solo = SoloConfig(aiDelayMs = 2),
            ),
        )
        val vm = onMain { TableViewModel(owner, PreferencesRepository(InMemoryKeyValueStore()), CoroutineScope(SupervisorJob() + mainDispatcher)) }
        val human = ScriptedHuman(vm, { owner.state.value.table?.view }, seed = 5)
        assertTrue(onMain { owner.startSolo(nickname()) })

        val deadline = System.currentTimeMillis() + 60_000
        while (owner.state.value.table?.view?.match?.lastResult?.dealNumber != 1) {
            assertTrue("deal did not finish: ${owner.state.value.lifecycle} ${owner.state.value.failure}", System.currentTimeMillis() < deadline)
            onMain { human.act() }
            Thread.sleep(2)
        }
        val failure = owner.state.value.failure
        owner.shutdown()
        main.shutdown()
        main.awaitTermination(2, TimeUnit.SECONDS)

        assertNull(failure)
        assertTrue("the AI decided", aiThreads.isNotEmpty())
        assertEquals("every AI decision ran on the AI worker", setOf("wpgd-ai"), aiThreads.toSet())
        assertTrue(human.actions > 0)
    }

    private fun <T> onMain(block: () -> T): T = main.submit<T> { block() }.get(30, TimeUnit.SECONDS)
}
