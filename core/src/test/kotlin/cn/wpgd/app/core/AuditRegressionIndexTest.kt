package cn.wpgd.app.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GDN-104: every audit finding the implementation plan claims as a regression is tagged in at least
 * one test, and every test the plan names exists. It reads `dev-docs/implementation-plan.md` and the
 * test sources of all three modules, so a renamed or deleted test, or a new audit claim without a
 * test, fails here instead of leaving the plan's traceability silently false.
 */
class AuditRegressionIndexTest {
    private val root = File("..").canonicalFile
    private val plan = File(root, "dev-docs/implementation-plan.md").also { check(it.isFile) { "plan not found at ${it.path}" } }.readText()

    private val testSources: List<File> by lazy {
        val dirs = listOf("core", "session", "app").flatMap { m -> listOf("test", "androidTest", "sharedTest").map { File(root, "$m/src/$it") } }
        dirs.filter { it.isDirectory }.flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    }
    private val testText: String by lazy { testSources.joinToString("\n") { it.readText() } }

    /** Audit ids each work item claims: its "Audit regressions" line and any "audit Axx" in its first failing tests. */
    private fun claimedAuditIds(): Map<String, Set<String>> {
        val sections = Regex("""(?m)^### (WI-\d+)\b""").findAll(plan).toList()
        assertTrue(sections.size >= 13, "found only ${sections.size} work items in the plan")
        return sections.mapIndexed { i, m ->
            val end = sections.getOrNull(i + 1)?.range?.first ?: plan.indexOf("\n## ", m.range.last).let { if (it < 0) plan.length else it }
            val body = plan.substring(m.range.first, end)
            val lines = body.lines().filter { it.startsWith("- **Audit regressions") || it.startsWith("- **First failing tests") }
            val ids = lines.flatMap { line ->
                val claims = if (line.startsWith("- **Audit regressions")) line else Regex("""audit (A\d{2})""").findAll(line).joinToString(" ") { it.value }
                ID.findAll(claims).map { it.value }.toList()
            }
            m.groupValues[1] to ids.toSet()
        }.toMap()
    }

    @Test fun everyClaimedAuditRegressionIsTaggedInATest() {
        val claimed = claimedAuditIds()
        val all = claimed.values.flatten().toSet()
        assertTrue(all.size >= 25, "the plan parse found only ${all.size} audit ids: $all")
        assertTrue(testSources.size >= 100, "scanned only ${testSources.size} test files under $root")
        val untagged = claimed.flatMap { (wi, ids) -> ids.filter { !Regex("""(?<![A-Za-z0-9-])${Regex.escape(it)}(?![0-9])""").containsMatchIn(testText) }.map { "$wi $it" } }
        assertEquals(emptyList(), untagged, "audit regressions claimed by the plan with no test naming them")
    }

    @Test fun everyTestThePlanNamesExists() {
        val named = Regex("""`([A-Z][A-Za-z0-9]*Test)`""").findAll(plan).map { it.groupValues[1] }.toSortedSet()
        assertTrue(named.size >= 100, "the plan parse found only ${named.size} test names")
        val classes = Regex("""\bclass ([A-Z][A-Za-z0-9]*Test)\b""").findAll(testText).map { it.groupValues[1] }.toSet()
        assertEquals(emptyList(), named.filter { it !in classes }, "tests named in the plan with no class of that name")
    }

    private companion object {
        val ID = Regex("""\b(A\d{2}|KEEP-\d{2})\b""")
    }
}
