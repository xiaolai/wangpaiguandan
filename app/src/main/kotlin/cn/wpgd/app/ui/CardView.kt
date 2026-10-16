package cn.wpgd.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import cn.wpgd.app.R
import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.table.CardUi

/**
 * Sizes of one card of the hand, measured from the real text at the current font scale (GDN-098):
 * a card is a [strip] (rank, then suit or the 配 badge) and the last card of a column is [full]
 * height. Both sides are at least 48 dp, so every visible part of every card is a full touch target.
 */
@Immutable
data class CardMetrics(val width: Dp, val strip: Dp, val full: Dp, val rankStyle: TextStyle, val badgeStyle: TextStyle, val pad: Dp)

private const val LARGE_CARD_SCALE = 1.3f
private const val TEXT_PRESENTATION = "︎"

@Composable
fun rememberCardMetrics(large: Boolean): CardMetrics {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = LocalTextStyle.current
    val scale = if (large) LARGE_CARD_SCALE else 1f
    val rankStyle = base.merge(TextStyle(fontSize = Tokens.type("title") * scale, fontWeight = FontWeight.Bold))
    val badgeStyle = base.merge(TextStyle(fontSize = Tokens.type("badge") * scale, fontWeight = FontWeight.Bold))
    val badgeText = stringResource(R.string.card_wildcard_badge)
    val jokerRank = stringResource(R.string.rank_big_joker_short)
    val jokerSlot = stringResource(R.string.rank_joker_slot)
    return remember(density, rankStyle, badgeStyle, badgeText, jokerRank, jokerSlot) {
        fun w(t: String, s: TextStyle) = measurer.measure(t, s, maxLines = 1, softWrap = false).size.width
        val pad = Tokens.space(2)
        val badgePad = Tokens.space(1)
        val rankW = listOf("10", "Q", "K", jokerRank).maxOf { w(it, rankStyle) }
        val slotW = maxOf(
            listOf(Suit.SPADE, Suit.HEART, Suit.CLUB, Suit.DIAMOND).maxOf { w(it.glyph + TEXT_PRESENTATION, rankStyle) },
            w(jokerSlot, rankStyle),
            w(badgeText, badgeStyle) + with(density) { (badgePad * 2).roundToPx() },
        )
        // The tallest line a strip can hold: ranks, suit glyphs (taller than digits in most fonts), 王, 配.
        fun h(t: String, s: TextStyle) = measurer.measure(t, s, maxLines = 1, softWrap = false).size.height
        val glyphs = listOf(Suit.SPADE, Suit.HEART, Suit.CLUB, Suit.DIAMOND).map { it.glyph + TEXT_PRESENTATION }
        val textH = maxOf((listOf("10", jokerRank, jokerSlot) + glyphs).maxOf { h(it, rankStyle) }, h(badgeText, badgeStyle))
        with(density) {
            // +1 dp of slack for rounding between px and dp.
            val width = max(Tokens.touch, (rankW + slotW).toDp() + pad * 2 + Tokens.space(1) + 1.dp)
            val strip = max(Tokens.touch, textH.toDp() + pad * 2 + 1.dp)
            CardMetrics(width, strip, strip + max(Tokens.space(8), textH.toDp()), rankStyle, badgeStyle, pad)
        }
    }
}

/** Ink of a card face: red suits and the big joker red, the rest black; four-colour clubs and diamonds. */
@Composable
private fun faceColor(card: CardUi, fourColor: Boolean): Color {
    val c = Wpgd.colors
    return when {
        card.suit == Suit.JOKER -> if (card.rank == Ranks.BIG_JOKER) c.suitRed else c.suitBlack
        fourColor && card.suit == Suit.CLUB -> c.club4
        fourColor && card.suit == Suit.DIAMOND -> c.diamond4
        card.suit.isRed -> c.suitRed
        else -> c.suitBlack
    }
}

/**
 * One card of the hand. Selection is shown by a thick outline and fill and announced as a state
 * ("已选"/"未选"); the wildcard role is the 配 badge (text, not colour alone) and part of the
 * description. Tapping toggles it; dragging is handled by the hand.
 */
@Composable
fun CardView(card: CardUi, full: Boolean, metrics: CardMetrics, description: String, fourColor: Boolean, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val c = Wpgd.colors
    val r = Tokens.radius("card")
    val shape = if (full) RoundedCornerShape(r) else RoundedCornerShape(topStart = r, topEnd = r)
    val ink = faceColor(card, fourColor)
    val selected = stringResource(R.string.card_selected)
    val notSelected = stringResource(R.string.card_not_selected)
    val joker = card.suit == Suit.JOKER
    val rank = when (card.rank) {
        Ranks.SMALL_JOKER -> stringResource(R.string.rank_small_joker_short)
        Ranks.BIG_JOKER -> stringResource(R.string.rank_big_joker_short)
        11 -> "J"
        12 -> "Q"
        13 -> "K"
        Ranks.ACE -> "A"
        else -> card.rank.toString()
    }
    val slot = if (joker) stringResource(R.string.rank_joker_slot) else card.suit.glyph + TEXT_PRESENTATION
    Box(
        modifier
            .size(metrics.width, if (full) metrics.full else metrics.strip)
            .alpha(if (card.eligible) 1f else DISABLED_ALPHA)
            .background(if (card.selected) c.selFill else c.card, shape)
            .border(if (card.selected) 3.dp else 1.dp, if (card.selected) c.sel else c.line, shape)
            .testTag("card:${card.id}")
            .selectable(selected = card.selected, enabled = card.eligible, role = Role.Checkbox, onClick = onTap)
            .semantics {
                contentDescription = description
                stateDescription = if (card.selected) selected else notSelected
            },
    ) {
        Column(Modifier.padding(metrics.pad)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.space(1))) {
                Text(rank, style = metrics.rankStyle, color = ink, maxLines = 1, softWrap = false)
                if (card.wildcard) {
                    Text(
                        stringResource(R.string.card_wildcard_badge),
                        style = metrics.badgeStyle,
                        color = c.bg,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .background(c.wild, RoundedCornerShape(Tokens.radius("badge")))
                            .padding(horizontal = Tokens.space(1))
                            .testTag("badge:${card.id}"),
                    )
                } else {
                    Text(slot, style = metrics.rankStyle, color = ink, maxLines = 1, softWrap = false)
                }
            }
            if (full) {
                Text(
                    slot,
                    style = metrics.rankStyle,
                    color = ink,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth().wrapContentSize(Alignment.Center),
                )
            }
        }
    }
}

private const val DISABLED_ALPHA = 0.4f
