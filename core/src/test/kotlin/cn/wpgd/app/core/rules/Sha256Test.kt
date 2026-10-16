package cn.wpgd.app.core.rules

import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Sha256Test {
    private fun jvm(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    @Test fun knownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc"))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test fun matchesJvmDigestAcrossPaddingBoundaries() {
        val random = Random(256)
        for (n in (0..200) + listOf(1000, 4096, 10_001)) {
            val bytes = random.nextBytes(n)
            assertTrue(jvm(bytes).contentEquals(Sha256.digest(bytes)), "length $n")
        }
    }
}

/** The iOS decision (KMP) depends on `:core` main code using the Kotlin stdlib only. */
class CoreMultiplatformGuardTest {
    @Test fun coreMainUsesNoJavaApis() {
        val files = File("src/main").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.size >= 8, "guard scanned only ${files.size} files from ${File("src/main").absolutePath}")
        val hits = files.flatMap { f -> platformHits(f.readText()).map { "${f.name}:$it" } }
        assertEquals(emptyList(), hits)
    }

    /** F13: fully qualified uses outside import lines, and android imports, are caught; comments are not code. */
    @Test fun guardCatchesFullyQualifiedUsesButNotComments() {
        val sample = """
            |import android.os.Bundle
            |import java.util.UUID
            |/** Mentions java.util.UUID in KDoc only. */
            |class Planted {
            |    // a line comment naming android.os.Bundle
            |    val id = java.util.UUID.randomUUID() /* java.io.File */
            |    fun f(b: android.os.Bundle?) = b
            |    val url = "https://example.org/x" + '"' // after a string holding // and a quote char
            |    /* block
            |       javax.inject.Inject
            |    */
            |    val t = javax.inject.Provider::class
            |}
        """.trimMargin()
        // WI-03 acceptance: no android import and no java.util.UUID (import or fully qualified) in :core.
        assertEquals(listOf(1, 2, 6, 7, 12), platformHits(sample).map { it.substringBefore(':').toInt() })
    }

    private companion object {
        val FORBIDDEN = Regex("""\b(java|javax|android)\.[a-z]|\bString\.format\b|"\s*\.format\(|\bCharsets\.|\bSystem\.""")

        /** "line: text" for every line whose code (comments removed) uses a JVM or Android API. */
        fun platformHits(source: String): List<String> {
            val code = stripComments(source).lines()
            val original = source.lines()
            return code.indices.filter { FORBIDDEN.containsMatchIn(code[it]) }.map { "${it + 1}: ${original[it].trim()}" }
        }

        /**
         * Blanks out `//` and block comments, keeping line breaks so line numbers survive. String and
         * char literals are copied as they are, so a `//` or `"` inside one does not change state.
         */
        fun stripComments(src: String): String {
            val out = StringBuilder(src.length)
            var i = 0
            while (i < src.length) {
                when {
                    src.startsWith("\"\"\"", i) -> {
                        val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it + 3 }
                        out.append(src, i, end); i = end
                    }
                    src[i] == '"' -> {
                        var j = i + 1
                        while (j < src.length && src[j] != '"' && src[j] != '\n') j += if (src[j] == '\\') 2 else 1
                        val end = minOf(j + 1, src.length)
                        out.append(src, i, end); i = end
                    }
                    src[i] == '\'' -> { // char literal such as '"' or '\''
                        val end = minOf(i + (if (src.getOrNull(i + 1) == '\\') 4 else 3), src.length)
                        out.append(src, i, end); i = end
                    }
                    src.startsWith("//", i) -> {
                        while (i < src.length && src[i] != '\n') i++
                    }
                    src.startsWith("/*", i) -> {
                        val end = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                        for (k in i until end) if (src[k] == '\n') out.append('\n')
                        i = end
                    }
                    else -> { out.append(src[i]); i++ }
                }
            }
            return out.toString()
        }
    }
}
