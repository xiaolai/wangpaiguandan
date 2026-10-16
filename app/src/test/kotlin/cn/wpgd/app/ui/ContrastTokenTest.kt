package cn.wpgd.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * GDN-099: every colour pair the screens draw meets WCAG 2.x AA in both themes, computed from the
 * reviewable source `design/tokens.json` (not from the generated Kotlin): text at least 4.5:1,
 * non-text indicators (selection outline, button fill) at least 3:1.
 */
class ContrastTokenTest {
    private val tokens: Map<String, Map<String, String>> by lazy { parseTokens(File("../design/tokens.json").readText()) }

    @Test fun everyTextPairMeetsAaInBothThemes() {
        val failures = failures(ColorPairs.text, AA_TEXT)
        assertTrue("text pairs below 4.5:1:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun everyNonTextIndicatorMeetsThreeToOne() {
        val failures = failures(ColorPairs.nonText, AA_NON_TEXT)
        assertTrue("non-text pairs below 3:1:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun everyPairNamesARealTokenAndThePairListIsNotEmpty() {
        assertTrue(ColorPairs.text.size >= 20)
        for (p in ColorPairs.text + ColorPairs.nonText) {
            assertTrue("unknown token ${p.foreground}", p.foreground in tokens)
            assertTrue("unknown token ${p.background}", p.background in tokens)
        }
    }

    /** The calculator is not vacuous: known values, and a pair the design must avoid is caught. */
    @Test fun theCalculatorMatchesKnownRatiosAndCatchesALowPair() {
        assertEquals(21.0, ratio("#000000", "#FFFFFF"), 0.01)
        assertEquals(1.0, ratio("#777777", "#777777"), 0.0001)
        assertEquals(4.48, ratio("#777777", "#FFFFFF"), 0.01)
        // Dark-theme card text drawn in the wildcard colour would be about 2.2:1: why the 配 badge
        // draws `bg` on a `wild` fill instead.
        val wildOnCard = ratio(tokens.getValue("wild").getValue("dark"), tokens.getValue("card").getValue("dark"))
        assertTrue("wild on card (dark) should fail AA, was $wildOnCard", wildOnCard < AA_TEXT)
    }

    private fun failures(pairs: List<ColorPairs.Pair>, minimum: Double): List<String> = buildList {
        for (p in pairs) for (mode in listOf("light", "dark")) {
            val r = ratio(tokens.getValue(p.foreground).getValue(mode), tokens.getValue(p.background).getValue(mode))
            if (r < minimum) add("$mode ${p.foreground} on ${p.background} (${p.use}): ${"%.2f".format(r)}")
        }
    }

    companion object {
        const val AA_TEXT = 4.5
        const val AA_NON_TEXT = 3.0

        /** WCAG 2.x relative luminance of an sRGB `#RRGGBB` colour. */
        fun luminance(hex: String): Double {
            require(Regex("#[0-9A-Fa-f]{6}").matches(hex)) { "bad colour $hex" }
            fun channel(i: Int): Double {
                val c = hex.substring(1 + 2 * i, 3 + 2 * i).toInt(16) / 255.0
                return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(0) + 0.7152 * channel(1) + 0.0722 * channel(2)
        }

        fun ratio(a: String, b: String): Double {
            val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
            return (hi + 0.05) / (lo + 0.05)
        }

        /** name → (light, dark), from the `colors` array of tokens.json. */
        fun parseTokens(json: String): Map<String, Map<String, String>> {
            val entry = Regex("""\{\s*"name"\s*:\s*"([^"]+)"\s*,\s*"light"\s*:\s*"(#[0-9A-Fa-f]{6})"\s*,\s*"dark"\s*:\s*"(#[0-9A-Fa-f]{6})"\s*}""")
            val out = entry.findAll(json).associate { m -> m.groupValues[1] to mapOf("light" to m.groupValues[2], "dark" to m.groupValues[3]) }
            check(out.size == Regex("\"name\"").findAll(json).count()) { "tokens.json has an entry this parser does not read" }
            return out
        }
    }
}
