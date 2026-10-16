package cn.wpgd.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/** Audit-fix #44: a fast swipe with sparse pointer events still crosses every card between them. */
class HandHitTest {
    // Three cards side by side, 40 px wide and tall with 10 px gaps.
    private val cards = mapOf(
        "a" to Rect(0f, 0f, 40f, 40f),
        "b" to Rect(50f, 0f, 90f, 40f),
        "c" to Rect(100f, 0f, 140f, 40f),
    )

    @Test fun aSparseSwipeCrossesEveryCardBetweenTwoEvents() {
        assertEquals(listOf("a", "b", "c"), crossed(Offset(20f, 10f), Offset(120f, 10f), cards))
        assertEquals("within one card", listOf("a"), crossed(Offset(10f, 10f), Offset(30f, 10f), cards))
        assertEquals("backwards too", listOf("c", "b", "a"), crossed(Offset(120f, 10f), Offset(20f, 10f), cards))
        assertEquals("no movement", listOf("c"), crossed(Offset(120f, 10f), Offset(120f, 10f), cards))
        assertEquals("in a gap", emptyList<String>(), crossed(Offset(42f, 10f), Offset(48f, 30f), cards))
        assertEquals("leaving a card from its edge", emptyList<String>(), crossed(Offset(40f, 10f), Offset(45f, 10f), cards))
    }

    /** Round 2: a diagonal swipe that clips a card's corner for less than one sample step still crosses it. */
    @Test fun aDiagonalSwipeThatClipsACornerCrossesThatCard() {
        // From inside "a" up-right across "b"'s top-left corner (50, 0): inside "b" for about 1.4 px only.
        assertEquals(listOf("a", "b"), crossed(Offset(31f, 21f), Offset(71f, -19f), cards))
        // A brief clip far shorter than any sampling step.
        assertEquals(listOf("b"), crossed(Offset(49f, 1.5f), Offset(51f, -0.5f), cards))
    }
}
