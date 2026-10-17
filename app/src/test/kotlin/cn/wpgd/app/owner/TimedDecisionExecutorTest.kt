package cn.wpgd.app.owner

import cn.wpgd.app.core.ai.AiAction
import cn.wpgd.app.core.ai.AiPolicies
import cn.wpgd.app.core.ai.DecisionOutcome
import cn.wpgd.app.core.ai.DecisionSource
import cn.wpgd.app.diagnostics.LatencySamples
import cn.wpgd.app.session.ai.DecisionExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * WI-07 acceptance ("p95 decision ≤ 1 s on the named minimum Android device") needs the decision
 * time recorded on the device: every AI decision's compute time goes into the owner's samples,
 * including one that throws, whichever executor runs it.
 */
class TimedDecisionExecutorTest {
    private var nanos = 0L
    private val samples = LatencySamples()
    private val timed = TimedDecisionExecutor(DecisionExecutor.INLINE, samples) { nanos }
    private val outcome = DecisionOutcome(AiAction.Pass, DecisionSource.POLICY, steps = 1, policy = AiPolicies.BASELINE)

    @Test fun eachDecisionRecordsItsComputeTime() {
        var delivered: DecisionOutcome? = null
        timed.execute({ nanos += 7_400_000; outcome }, { delivered = it })
        assertSame(outcome, delivered)
        timed.execute({ nanos += 900_000_000; outcome }, { })
        assertEquals(2L, samples.count)
        assertEquals(900L, samples.max)
        assertEquals(7L, samples.percentile(50.0))
    }

    @Test fun aDecisionThatThrowsIsStillTimed() {
        var delivered: DecisionOutcome? = null
        val failing = runCatching { timed.execute({ nanos += 3_000_000; error("policy bug") }, { delivered = it }) }
        assertEquals("policy bug", failing.exceptionOrNull()?.message)
        assertNull(delivered)
        assertEquals(1L, samples.count)
        assertEquals(3L, samples.max)
    }
}
