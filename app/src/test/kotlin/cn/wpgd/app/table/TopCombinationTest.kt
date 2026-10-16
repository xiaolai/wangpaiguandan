package cn.wpgd.app.table

import cn.wpgd.app.card
import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.rules.CombinationType
import cn.wpgd.app.core.state.PublicPlay
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.ViewAssignment
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Audit-fix #41: the table reads the top play through the same core reading as the member's AI
 * inputs, so a declaration the host accepted with a stray suit (D16 off) reads instead of throwing.
 */
class TopCombinationTest {
    private fun profile(name: String) = File("../rules/profiles/$name.json").readText()

    @Test fun aStraySuitDeclarationReadsWhenTheProfileIgnoresIt() {
        val lax = profile("P1-base-v1").replace("\"wildcard.straySuitRejected\": true", "\"wildcard.straySuitRejected\": false")
        check(lax.contains("\"wildcard.straySuitRejected\": false"))
        val rules = TableRules(RuleProfiles.parse(lax, profile("M1-match-v1")))
        val play = PublicPlay(Seat.EAST, listOf("0S6", "0D6", "0C6", "0H2").map(::card), listOf(ViewAssignment(6, Suit.CLUB)), CombinationType.BOMB)
        val top = rules.topCombination(listOf(play), 2)
        assertEquals(CombinationType.BOMB, top?.type)
        assertEquals(6, top?.rank)
    }
}
