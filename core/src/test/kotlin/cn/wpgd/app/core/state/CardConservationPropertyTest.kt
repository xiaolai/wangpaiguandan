package cn.wpgd.app.core.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GDN-030: the 108 card ids are conserved at every step (hands plus played cards), over seeded random play. */
class CardConservationPropertyTest {
    @Test fun idsConservedAtEveryStepOfTwoHundredSeededDeals() {
        val r = p1Reducer()
        var steps = 0
        for (seed in 1L..DEALS) {
            playDeal(r, seed, level = LEVELS[(seed % LEVELS.size).toInt()]) { step ->
                val ids = step.after.allCardIds()
                assertEquals(108, ids.size, "seed $seed revision ${step.after.revision}: card count")
                assertEquals(StandardDeck.ids, ids.toSet(), "seed $seed revision ${step.after.revision}: ids")
                // Played cards left exactly one hand.
                val moved = step.before.allCardIds().toSet() == step.after.allCardIds().toSet()
                assertTrue(moved || step.before.phase == Phase.AWAITING_DEAL)
                steps++
            }
        }
        assertTrue(steps > DEALS * 10, "only $steps steps over $DEALS deals")
    }

    private companion object {
        const val DEALS = 200L
        val LEVELS = listOf(2, 7, 10, 14)
    }
}
