package cn.wpgd.app.table

import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.rules.Combination
import cn.wpgd.app.core.rules.CombinationType
import cn.wpgd.app.core.rules.ImpossibleCardsException
import cn.wpgd.app.core.rules.WildcardAssignment
import cn.wpgd.app.core.state.ViewCard
import cn.wpgd.app.session.wire.PlayBody
import cn.wpgd.app.session.wire.WireAssignment

/**
 * One way to play the selected cards: its type, what each wildcard stands for, and the exact
 * command body that will be sent (GDN-076: the payload is built from this option and nothing else).
 */
data class PlayOption(
    /** Stable for the same cards: type plus declaration. */
    val key: String,
    val type: CombinationType,
    val declaration: List<WildcardAssignment>,
    /** Every card's rank after the declaration, sorted (what the preview shows). */
    val resolvedRanks: List<Int>,
    /** The suit of a straight flush; null otherwise. */
    val suit: Suit?,
    val payload: PlayBody,
)

enum class PreviewProblem(val messageKey: String) {
    NOT_A_COMBINATION("table.preview.not_a_combination"),
    DOES_NOT_BEAT("table.preview.does_not_beat"),
}

sealed interface PlayPreview {
    data object Empty : PlayPreview

    data class NotPlayable(val problem: PreviewProblem) : PlayPreview

    /**
     * Legal readings of the selection that beat the table. With more than one, the player must
     * choose ([needsChoice]); [payload] is the chosen (or only) option's body, else null.
     */
    data class Options(val options: List<PlayOption>, val chosenKey: String?) : PlayPreview {
        val chosen: PlayOption? get() = options.singleOrNull() ?: options.firstOrNull { it.key == chosenKey }
        val needsChoice: Boolean get() = chosen == null
        val payload: PlayBody? get() = chosen?.payload
    }

    companion object {
        /**
         * The preview of playing [selected] (cards of the member's own hand) at [level] on top of
         * [top] (null when leading), with [chosenKey] the option the player picked, if any.
         */
        fun of(rules: TableRules, selected: List<ViewCard>, level: Int, top: Combination?, chosenKey: String?): PlayPreview {
            if (selected.isEmpty()) return Empty
            val cards = selected.map(TableRules::card)
            val readings = try {
                rules.readings(cards, level)
            } catch (_: ImpossibleCardsException) {
                return NotPlayable(PreviewProblem.NOT_A_COMBINATION)
            }
            if (readings.isEmpty()) return NotPlayable(PreviewProblem.NOT_A_COMBINATION)
            val beating = readings.filter { top == null || it.combination.beats(top) }
            if (beating.isEmpty()) return NotPlayable(PreviewProblem.DOES_NOT_BEAT)
            val ids = selected.map { it.id }
            val options = beating
                .distinctBy { it.combination.type to it.declaration }
                .map { r ->
                    val c = r.combination
                    PlayOption(
                        key = c.type.name + ":" + r.declaration.joinToString(",") { "${it.rank}${it.suit?.name?.first() ?: '*'}" },
                        type = c.type,
                        declaration = r.declaration,
                        resolvedRanks = c.resolvedRanks,
                        suit = c.suit,
                        payload = PlayBody(ids, r.declaration.map { WireAssignment(it.rank, it.suit) }, c.type),
                    )
                }
                .sortedWith(compareBy({ it.type.ordinal }, { it.key }))
            return Options(options, chosenKey?.takeIf { k -> options.any { it.key == k } })
        }
    }
}
