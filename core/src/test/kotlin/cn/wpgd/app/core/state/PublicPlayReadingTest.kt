package cn.wpgd.app.core.state

import cn.wpgd.app.core.rules.Classifier
import cn.wpgd.app.core.rules.CombinationType
import cn.wpgd.app.core.rules.Judgment
import cn.wpgd.app.core.rules.Profiles
import cn.wpgd.app.core.rules.RuleSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A public play is read against the play it followed, as the reducer judged it: with both declaration
 * requirements off, an undeclared wildcard straight can read two ways while only one beats the table.
 */
class PublicPlayReadingTest {
    private val loose = Profiles.with("wildcard.alwaysDeclared" to "false", "wildcard.ambiguousNeedsDeclaration" to "false")
    private val classifier = Classifier(RuleSettings.of(loose), 2)

    private fun play(seat: Seat, vararg ids: String) =
        PublicPlay(seat, ids.map { id -> StandardDeck[id]!!.let { ViewCard(it.id, it.suit, it.rank) } }, null, CombinationType.STRAIGHT)

    @Test fun aFollowIsReadAgainstThePlayItBeat() {
        val lead = play(Seat.SOUTH, "0S2", "0S3", "0D4", "0S5", "0S6")
        val follow = play(Seat.EAST, "0C3", "0C4", "0D5", "0C6", "0H2") // 0H2 is a wildcard at level 2
        val leadReading = assertNotNull(lead.reading(classifier, null))
        // The premise: two straight readings (2–6 and 3–7), only 3–7 beats the lead, so the reducer accepts it.
        val judged = assertIs<Judgment.Readable>(classifier.judge(follow.cards.map { StandardDeck[it.id]!! }, null, leadReading))
        assertEquals(2, judged.readings.count { it.type == CombinationType.STRAIGHT })
        assertEquals(1, judged.beating.size)

        assertEquals(judged.beating.single(), listOf(lead, follow).topReading(classifier))
        assertNull(follow.reading(classifier, null), "without the table the follow is ambiguous")
        assertNull(emptyList<PublicPlay>().topReading(classifier))
    }
}
