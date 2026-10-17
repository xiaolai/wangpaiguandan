package cn.wpgd.app.session.ai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GDN-059: the AI is offline. Neither the policy package (`cn.wpgd.app.core.ai`) nor the session
 * driver and hints (`cn.wpgd.app.session.ai`) may use anything network-capable, and neither module
 * declares a network or inference dependency. HP-1/HP-2 traffic capture remains the device check.
 */
class OfflineAiGuardTest {
    private val dirs = listOf(File("../core/src/main/kotlin/cn/wpgd/app/core/ai"), File("src/main/kotlin/cn/wpgd/app/session/ai"))

    @Test fun aiSourcesUseNothingNetworkCapable() {
        val files = dirs.flatMap { d -> d.walkTopDown().filter { it.extension == "kt" }.toList() }
        assertTrue(files.size >= 9, "guard scanned only ${files.size} files: ${dirs.map { it.absolutePath }}")
        val hits = files.flatMap { f -> networkHits(f.readText()).map { "${f.name}:$it" } }
        assertEquals(emptyList(), hits)
    }

    @Test fun modulesDeclareNoNetworkDependency() {
        for (build in listOf(File("../core/build.gradle.kts"), File("build.gradle.kts"))) {
            val text = build.readText()
            val found = NETWORK_DEPENDENCIES.filter { it.containsMatchIn(text) }
            assertEquals(emptyList(), found.map { it.pattern }, build.path)
        }
    }

    /** Positive control: each forbidden form is caught, and comments are not code. */
    @Test fun guardCatchesPlantedNetworkUse() {
        val sample = """
            |import java.net.URL
            |import okhttp3.OkHttpClient
            |// a comment naming java.net.Socket is fine
            |/** KDoc: no HttpURLConnection here. */
            |class Planted {
            |    val s = java.net.Socket("example.org", 80)
            |    val c = HttpURLConnection::class
            |    val k = io.ktor.client.HttpClient()
            |    val ok = 1
            |}
        """.trimMargin()
        assertEquals(listOf(1, 2, 6, 7, 8), networkHits(sample).map { it.substringBefore(':').toInt() })
    }

    private companion object {
        val FORBIDDEN = Regex(
            """\b(java\.net|javax\.net|java\.nio\.channels|android\.net|okhttp3|io\.ktor|retrofit2|io\.grpc|com\.squareup\.okhttp)\b|""" +
                """\b(Socket|ServerSocket|DatagramSocket|HttpURLConnection|URLConnection|InetAddress|HttpClient|WebSocket)\b""",
        )
        val NETWORK_DEPENDENCIES = listOf(Regex("okhttp"), Regex("ktor"), Regex("retrofit"), Regex("grpc"), Regex("tensorflow|onnx|tflite|litert", RegexOption.IGNORE_CASE))

        fun networkHits(source: String): List<String> {
            val code = stripComments(source).lines()
            val original = source.lines()
            return code.indices.filter { FORBIDDEN.containsMatchIn(code[it]) }.map { "${it + 1}: ${original[it].trim()}" }
        }

        /** Blanks `//` and block comments, keeping line breaks; string literals are kept as they are. */
        fun stripComments(src: String): String {
            val out = StringBuilder(src.length)
            var i = 0
            while (i < src.length) {
                when {
                    src[i] == '"' -> {
                        var j = i + 1
                        while (j < src.length && src[j] != '"' && src[j] != '\n') j += if (src[j] == '\\') 2 else 1
                        val end = minOf(j + 1, src.length)
                        out.append(src, i, end); i = end
                    }
                    src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') i++
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
