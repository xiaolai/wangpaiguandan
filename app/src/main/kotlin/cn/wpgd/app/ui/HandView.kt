package cn.wpgd.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import cn.wpgd.app.table.CardUi
import cn.wpgd.app.table.HandLayoutA
import cn.wpgd.app.table.HandUi

/** What the hand reports; the view model decides what each means (GDN-074). */
class HandCallbacks(
    val onTap: (String) -> Unit,
    val onDragStart: (String) -> Unit,
    val onDragOver: (String) -> Unit,
    val onDragEnd: () -> Unit,
)

/** Where each card is, so a drag can name the card under the finger. Holds no state of the hand. */
private class CardHitMap {
    var hand: LayoutCoordinates? = null
    val cards = HashMap<String, LayoutCoordinates>()

    fun at(position: Offset): String? = bounds().entries.firstOrNull { it.value.contains(position) }?.key

    /** Every attached card's bounds in the hand's coordinates. */
    fun bounds(): Map<String, Rect> {
        val h = hand?.takeIf { it.isAttached } ?: return emptyMap()
        cards.entries.removeAll { !it.value.isAttached }
        return cards.mapValues { h.localBoundingBoxOf(it.value, clipBounds = false) }
    }
}

/**
 * Hand layout A (two rows by rank, GDN-073): the columns come from the state; this only decides how
 * many fit across the width at the measured card size. When they do not fit in two rows (narrow
 * screen, large text, large cards) it uses more rows of fewer columns ([HandLayoutA.rows] with a
 * column limit): never sideways scrolling, never smaller than a 48 dp target.
 *
 * Tap toggles one card. A drag that starts sideways selects (or, starting on a selected card,
 * deselects) exactly the cards the finger passes over; a drag that starts vertically scrolls.
 */
@Composable
fun HandView(
    hand: HandUi,
    describe: (CardUi) -> String,
    fourColor: Boolean,
    largeCards: Boolean,
    callbacks: HandCallbacks,
    modifier: Modifier = Modifier,
) {
    val metrics = rememberCardMetrics(largeCards)
    val hits = remember { CardHitMap() }
    val cb by rememberUpdatedState(callbacks)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = Tokens.space(2)
        val columns = maxOf(1, ((maxWidth + gap) / (metrics.width + gap)).toInt())
        val rows = HandLayoutA.rows(hand.rows.flatten(), columns)
        Column(
            verticalArrangement = Arrangement.spacedBy(Tokens.space(3)),
            modifier = Modifier
                .testTag("hand")
                .onGloballyPositioned { hits.hand = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = hits.at(down.position) ?: return@awaitEachGesture
                        val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            ?: return@awaitEachGesture
                        cb.onDragStart(start)
                        // A fast swipe moves far between two pointer events: every card along the way counts.
                        crossed(down.position, slop.position, hits.bounds()).forEach(cb.onDragOver)
                        var last = slop.position
                        try {
                            drag(slop.id) { change ->
                                change.consume()
                                crossed(last, change.position, hits.bounds()).forEach(cb.onDragOver)
                                last = change.position
                            }
                        } finally {
                            cb.onDragEnd()
                        }
                    }
                },
        ) {
            for (row in rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (group in row) {
                        key(group.key) {
                            Column {
                                group.cards.forEachIndexed { i, card ->
                                    key(card.id) {
                                        CardView(
                                            card = card,
                                            full = i == group.cards.lastIndex,
                                            metrics = metrics,
                                            description = describe(card),
                                            fourColor = fourColor,
                                            onTap = { cb.onTap(card.id) },
                                            modifier = Modifier.onGloballyPositioned { hits.cards[card.id] = it },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The cards whose [bounds] the segment from [from] (excluded) to [to] (included) passes through, in
 * the order the segment enters them. Exact (segment against rectangle, no sampling), so a diagonal
 * swipe that only clips a card's corner still crosses it. No movement: the cards at [to]. Pure.
 */
internal fun crossed(from: Offset, to: Offset, bounds: Map<String, Rect>): List<String> {
    if (from == to) return bounds.filterValues { it.contains(to) }.keys.toList()
    return bounds.mapNotNull { (id, r) -> entry(from, to, r)?.let { id to it } }.sortedBy { it.second }.map { it.first }
}

/**
 * Where the segment [from]..[to] enters [r], as a fraction of its length clamped to 0, or null when
 * no part of it after [from] lies in [r] (Liang–Barsky clipping; the edges count as inside).
 */
private fun entry(from: Offset, to: Offset, r: Rect): Float? {
    val dx = to.x - from.x
    val dy = to.y - from.y
    var t0 = 0f
    var t1 = 1f
    // One clipping edge: p * t <= q keeps the segment inside it.
    fun clip(p: Float, q: Float): Boolean {
        if (p == 0f) return q >= 0f
        val t = q / p
        if (p < 0f) {
            if (t > t1) return false
            if (t > t0) t0 = t
        } else {
            if (t < t0) return false
            if (t < t1) t1 = t
        }
        return true
    }
    val inside = clip(-dx, from.x - r.left) && clip(dx, r.right - from.x) && clip(-dy, from.y - r.top) && clip(dy, r.bottom - from.y)
    return if (inside && t1 > 0f) t0 else null
}
