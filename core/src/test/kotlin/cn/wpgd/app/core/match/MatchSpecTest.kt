package cn.wpgd.app.core.match

import java.io.File
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `rules/match-v1.md` against its sources: every evidence quote is verbatim at its stated line, every
 * rule row ends with a tag, every cited line has a quote, every decision a row names is defined.
 */
class MatchSpecTest {
    private val spec = File("../rules/match-v1.md").readText()
    private val lines = spec.lines()

    private val ruleRows: Map<String, String> = lines.mapNotNull { l ->
        Regex("""^\| (M\d+\.\d+) \|.*\|\s*$""").find(l)?.let { it.groupValues[1] to l }
    }.toMap()

    private val evidence: Map<String, String> = lines.mapNotNull { l ->
        Regex("""^\| (\w+:\d+) \| (.+?) \| .+ \|\s*$""").find(l)?.let { it.groupValues[1] to it.groupValues[2] }
    }.toMap()

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).replace(Regex("\\s+"), "")

    @Test fun everyQuoteIsVerbatimAtItsLine() {
        assertTrue(evidence.size >= 50, "only ${evidence.size} evidence rows parsed")
        val bad = evidence.filter { (ref, quote) ->
            val (key, n) = ref.split(':')
            val text = File("../rules/sources/text/$key.txt").readLines()
            val line = n.toInt()
            val window = norm(text.subList(line - 1, minOf(text.size, line + 2)).joinToString(""))
            val at = window.indexOf(norm(quote))
            at < 0 || at >= norm(text[line - 1]).length.coerceAtLeast(1)
        }
        assertEquals(emptyMap(), bad, "quotes not found starting on their stated line")
    }

    @Test fun everyRuleRowEndsWithATag() {
        assertTrue(ruleRows.size >= 40, "only ${ruleRows.size} rule rows parsed")
        for ((id, row) in ruleRows) assertTrue(Regex("""\[[^\]]+\]\s*\|\s*$""").containsMatchIn(row), "$id has no tag")
    }

    @Test fun everyCitedLineHasAnEvidenceQuote() {
        val cited = ruleRows.values.flatMap { row -> Regex("""\b(JS17|HA17|CG22a|WG25|CUP|SEU|YZU|NJMU):(\d+)""").findAll(row.substringAfterLast('[')).map { it.value }.toList() }.toSet()
        assertTrue(cited.size >= 40)
        assertEquals(emptySet(), cited - evidence.keys, "cited without a quote")
        assertEquals(emptySet(), evidence.keys - cited, "quotes no rule cites")
    }

    @Test fun everyDecisionNamedInARowIsDefinedAndUsed() {
        val defined = lines.mapNotNull { Regex("""^\| (D\d+) \|""").find(it)?.groupValues?.get(1) }.toSet()
        assertEquals((25..40).map { "D$it" }.toSet(), defined)
        // Decisions in a row's tag (prose may mention base-v1 decisions such as D21).
        val named = ruleRows.values.flatMap { row -> Regex("""\bD\d+\b""").findAll(row.substringAfterLast('[')).map { it.value }.toList() }.toSet()
        assertEquals(defined, named, "every decision is used by a rule row, and every row's decision is defined")
    }

    /**
     * WI-04 acceptance ("each fixture cites a clause of the profile"): every test of the match rules
     * names the match-v1 rule row (M<n>.<n>) or decision (D25–D40) it checks, in the comment directly
     * above it or in its body, and every id it names exists in this spec.
     */
    @Test fun everyRuleTestCitesAMatchClause() {
        val files = listOf("SettlementTest", "TributeTest", "ReturnTributeTest", "ResistanceTest", "OpeningLeadTest", "PassingATest", "CardCountReportingTest")
        val uncited = ArrayList<String>()
        val unknown = ArrayList<String>()
        var parsed = 0
        for (name in files) {
            val f = File("src/test/kotlin/cn/wpgd/app/core/match/$name.kt")
            assertTrue(f.isFile, "missing ${f.path}")
            val src = f.readLines()
            val tests = src.indices.filter { Regex("""@Test\s+fun\s+""").containsMatchIn(src[it]) }
            assertTrue(tests.isNotEmpty(), "no @Test parsed in $name")
            for ((k, at) in tests.withIndex()) {
                parsed++
                var top = at
                while (top > 0 && src[top - 1].trim().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/**") }) top--
                val end = tests.getOrNull(k + 1) ?: src.size
                val text = src.subList(top, end).joinToString("\n")
                val test = Regex("""fun\s+(\w+)""").find(src[at])!!.groupValues[1]
                val rows = Regex("""\bM\d+\.\d+\b""").findAll(text).map { it.value }.toSet()
                val decisions = Regex("""\bD(\d+)\b""").findAll(text).map { it.groupValues[1].toInt() }.toSet()
                unknown += rows.filter { it !in ruleRows }.map { "$name.$test cites $it" }
                unknown += decisions.filter { it !in 25..40 }.map { "$name.$test cites D$it" }
                if (rows.none { it in ruleRows } && decisions.none { it in 25..40 }) uncited += "$name.$test"
            }
        }
        assertTrue(parsed >= 30, "only $parsed match rule tests parsed")
        assertEquals(emptyList(), unknown, "citations of rows or decisions match-v1 does not define")
        assertEquals(emptyList(), uncited, "match rule tests citing no match-v1 row or decision")
    }
}
