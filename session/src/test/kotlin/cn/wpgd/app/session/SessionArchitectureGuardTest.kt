package cn.wpgd.app.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Module boundaries: `:session` main never touches Android or Compose; JCA stays in the one crypto
 * file behind the seam; `:core` never depends on `:session` (sources or build file).
 */
class SessionArchitectureGuardTest {
    private val sessionMain = File("src/main/kotlin")
    private val coreMain = File("../core/src/main")

    private fun kotlinFiles(dir: File) = dir.walkTopDown().filter { it.extension == "kt" }.toList()

    private fun codeLines(f: File) = f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }

    @Test fun sessionMainHasNoAndroidOrCompose() {
        val files = kotlinFiles(sessionMain)
        assertTrue(files.size >= 10, "guard scanned only ${files.size} files in ${sessionMain.absolutePath}")
        val hits = files.flatMap { f -> codeLines(f).filter { Regex("""\b(android|androidx)\.""").containsMatchIn(it) }.map { "${f.name}: $it" } }
        assertEquals(emptyList(), hits)
    }

    @Test fun platformCryptoStaysBehindTheSeam() {
        val hits = kotlinFiles(sessionMain).filter { it.name != "JcaCrypto.kt" }
            .flatMap { f -> codeLines(f).filter { Regex("""\b(javax\.crypto|java\.security)\.""").containsMatchIn(it) }.map { "${f.name}: $it" } }
        assertEquals(emptyList(), hits)
    }

    @Test fun coreNeverReferencesSession() {
        val files = kotlinFiles(coreMain)
        assertTrue(files.size >= 8, "guard scanned only ${files.size} core files")
        val hits = files.filter { it.readText().contains("cn.wpgd.app.session") }.map { it.name }
        assertEquals(emptyList(), hits)
        assertTrue(!File("../core/build.gradle.kts").readText().contains(":session"), ":core must not depend on :session")
    }
}
