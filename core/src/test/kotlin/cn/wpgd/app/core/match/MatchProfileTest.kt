package cn.wpgd.app.core.match

import cn.wpgd.app.core.rules.Decision
import cn.wpgd.app.core.rules.MatchOptions
import cn.wpgd.app.core.rules.RuleOptions
import cn.wpgd.app.core.rules.RuleProfileError
import cn.wpgd.app.core.rules.RuleProfileLoader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The M1 match profile and the match registry (rules are data, base-v1 R8.1). */
class MatchProfileTest {
    private val m1 get() = MatchProfiles.m1

    @Test fun m1LoadsAgainstTheMatchRegistryOnly() {
        assertEquals("M1-match-v1", m1.id)
        // G1b signed 2026-10-16 (dev-docs/decisions/G1b-match-profile.md): M1 is the released match profile.
        assertEquals(false, m1.provisional)
        assertEquals(emptyList(), m1.warnings)
        assertIs<RuleProfileError.UnknownOption>(RuleProfileLoader.parse(m1Json(), RuleOptions.registry).exceptionOrNull())
    }

    @Test fun m1SetsEveryDecisionOptionExplicitlyToItsRegistryDefault() {
        val decided = MatchOptions.registry.active.filter { it.decision != null }.toSet()
        assertEquals(decided, m1.options.keys.filter { it.decision != null }.toSet())
        for ((option, value) in m1.options) assertEquals(option.default, value, option.id)
        for (option in MatchOptions.registry.active) assertEquals(option.default, m1[option], option.id)
    }

    @Test fun matchDecisionsAreD25ToD40AndEachHasAnOption() {
        val used = MatchOptions.registry.active.mapNotNull { it.decision }.toSet()
        val range = Decision.entries.filter { it.ordinal >= Decision.D25.ordinal }.toSet()
        assertEquals(range, used, "every match decision D25–D40 has an option, and no option uses a base decision")
    }

    /** Pins M1's canonical hash. A change to M1, to a match option or to its default must update this on purpose. */
    @Test fun m1HashIsPinned() {
        assertEquals(M1_HASH, m1.hash)
    }

    @Test fun anUnimplementedChoiceIsRejectedNotIgnored() {
        assertIs<RuleProfileError.OutOfRange>(
            RuleProfileLoader.parse(m1Json().replace("\"clockwise\"", "\"counterClockwise\""), MatchOptions.registry).exceptionOrNull(),
        )
        assertIs<RuleProfileError.WrongType>(
            RuleProfileLoader.parse(m1Json().replace("\"endEarly\"", "1"), MatchOptions.registry).exceptionOrNull(),
        )
        assertIs<RuleProfileError.OutOfRange>(
            RuleProfileLoader.parse(m1Json().replace("\"returnTribute.maxRank\": 10", "\"returnTribute.maxRank\": 15"), MatchOptions.registry).exceptionOrNull(),
        )
    }

    @Test fun everyOptionIsListedInTheSpecWithItsDecision() {
        val spec = File("../rules/match-v1.md").readText()
        for (option in MatchOptions.registry.active) {
            val row = spec.lines().firstOrNull { it.startsWith("| `${option.id}` |") } ?: error("${option.id} missing from match-v1 section 9")
            val decision = option.decision?.name ?: "sourced"
            assertTrue(row.trimEnd().endsWith("| $decision |"), "${option.id}: $row")
        }
    }

    private companion object {
        const val M1_HASH = "832431cd63641b9812c9e146f7f4a0b48271548c9c6eca0fbe550f6fe2391715"
    }
}
