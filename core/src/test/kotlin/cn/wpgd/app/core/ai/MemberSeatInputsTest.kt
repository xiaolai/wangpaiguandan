package cn.wpgd.app.core.ai

import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.rules.Profiles
import cn.wpgd.app.core.rules.WildcardAssignment
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The member side reads the table's top play exactly as the reducer judged it. */
class MemberSeatInputsTest {
    /**
     * D16 off (`wildcard.straySuitRejected=false`): a bomb of sixes declared with a stray club suit
     * is legal, so the member reconstructs it and still gets choices (here, a bigger bomb).
     */
    @Test fun aStraySuitDeclarationStillReadsWhenTheProfileIgnoresIt() {
        val lax = Profiles.with("wildcard.straySuitRejected" to "false")
        val base = playing(
            2,
            mapOf(S to listOf("0S9", "1S9", "0C9", "0D9"), E to listOf("0S3"), N to listOf("0S4"), W to listOf("0S5")),
            turn = S,
            trick = listOf(tp(E, "0S6", "0D6", "0C6", "0H2", declaration = listOf(WildcardAssignment(6)))),
        )
        val deal = checkNotNull(base.deal)
        val stray = deal.trick.plays.single().copy(declaration = listOf(WildcardAssignment(6, Suit.CLUB)))
        val state = base.copy(deal = deal.copy(trick = deal.trick.copy(plays = listOf(stray))))
        val input = assertNotNull(MemberSeatInputs(lax, AiProfiles.match).of(AiProfiles.projector.memberView(state, S)), "the stray-suit bomb reads")
        assertTrue(input.choices.plays.any { p -> p.cards.map { it.id }.toSet() == setOf("0S9", "1S9", "0C9", "0D9") }, "the nines bomb beats it")
    }
}
