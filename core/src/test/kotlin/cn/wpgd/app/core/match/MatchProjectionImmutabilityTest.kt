package cn.wpgd.app.core.match

import cn.wpgd.app.core.refusesJavaClear
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Round 3 #39: match projections are snapshots; clearing one (from Java) never changes host state. */
class MatchProjectionImmutabilityTest {
    private val r = m1Reducer()

    @Test fun projectionListsRefuseJavaMutationAndLeaveTheStateAlone() {
        val start = matchAtTribute(r, listOf(S, E, N), handsWith(mapOf(W to low(26) + "0SA")))
        val tribute = r.reduce(start, MatchCommand.StartTribute).accepted().state
        val projector = MatchProjector(p1, MatchProfiles.m1)
        val views = listOf(projector.spectatorView(tribute).match, projector.memberView(tribute, S).match, projector.aiObservation(tribute, W).match)
        for (v in views) {
            val t = v.tribute!!
            assertTrue(refusesJavaClear(t.givers), "givers")
            assertTrue(refusesJavaClear(t.receivers), "receivers")
            val last = v.lastResult!!
            assertTrue(refusesJavaClear(last.finishOrder), "lastResult.finishOrder")
            assertTrue(refusesJavaClear(last.placements), "lastResult.placements")
            assertTrue(refusesJavaClear(last.teams), "lastResult.teams")
            assertTrue(refusesJavaClear(v.reports), "reports")
            assertTrue(refusesJavaClear(v.answers), "answers")
        }
        assertEquals(listOf(W), tribute.tribute!!.obligation.givers)
        assertEquals(listOf(S, E, N), tribute.previous!!.finishOrder)
    }
}
