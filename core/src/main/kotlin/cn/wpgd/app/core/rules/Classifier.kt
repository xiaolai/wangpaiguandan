package cn.wpgd.app.core.rules

import cn.wpgd.app.core.Card
import cn.wpgd.app.core.Suit

/** Why a submitted play was refused before any comparison with the table. */
enum class RejectReason(val code: String) {
    /** The cards (without wildcards) form no legal type. */
    NO_COMBINATION("no_combination"),
    /** The play holds a wildcard but no declaration, and the profile or an ambiguity requires one (R7.7, R7.8). */
    MISSING_DECLARATION("missing_declaration"),
    /** The declaration does not have one entry per wildcard in the play. */
    DECLARATION_MISMATCH("declaration_mismatch"),
    /** A wildcard was declared as a joker (R6.3, R7.3). */
    WILDCARD_AS_JOKER("wildcard_as_joker"),
    /** Wildcards played alone were declared as another rank (R7.9, `wildcard.aloneMayStandIn`). */
    LONE_WILDCARD_REDECLARED("lone_wildcard_redeclared"),
    /** The declaration is well formed but describes no legal play of these cards (R7.8). */
    DECLARED_READING_ILLEGAL("declared_reading_illegal"),
    /** The same physical card was submitted twice. */
    DUPLICATE_CARD("duplicate_card"),
    /** More wildcards than one deal holds (R1.8). */
    TOO_MANY_WILDCARDS("too_many_wildcards"),
    /** More cards of one face (rank and suit, jokers included) than the two decks of a deal hold (R1.1). */
    TOO_MANY_COPIES("too_many_copies"),
}

/** A deal holds exactly two wildcards: the two heart level cards (R1.8). */
internal const val WILDCARDS_PER_DEAL = 2

/** A deal is two decks: every face (rank and suit, each joker included) exists twice (R1.1). */
internal const val COPIES_PER_FACE = 2

/**
 * Cards that no deal can produce were passed to [Classifier.readings] or
 * [LegalActionGenerator.generate]. [Classifier.judge] returns the same [reason] as a [Judgment.Rejected].
 */
class ImpossibleCardsException(val reason: RejectReason) : IllegalArgumentException("Impossible cards: ${reason.code}")

/** Why [cards] cannot come from one deal at [level], or null if they can. */
internal fun impossibleCards(cards: List<Card>, level: Int): RejectReason? = when {
    cards.map { it.id }.toSet().size != cards.size -> RejectReason.DUPLICATE_CARD
    cards.count { it.isWildcard(level) } > WILDCARDS_PER_DEAL -> RejectReason.TOO_MANY_WILDCARDS
    cards.groupingBy { it.rank to it.suit }.eachCount().values.any { it > COPIES_PER_FACE } -> RejectReason.TOO_MANY_COPIES
    else -> null
}

sealed interface Judgment {
    data class Rejected(val reason: RejectReason) : Judgment

    /**
     * The play reads as [readings] under its declaration. [beating] is the subset that beats the
     * table (all of them when leading). The play is legal when [beating] is not empty.
     */
    data class Readable(val readings: List<Combination>, val beating: List<Combination>) : Judgment {
        val legal: Boolean get() = beating.isNotEmpty()
    }
}

/**
 * Classifies a selection of cards at one level under one profile.
 * The two heart level cards are wildcards (R1.8); every other card counts as itself.
 */
class Classifier(settings: RuleSettings, val level: Int) {
    private val shapes = Shapes(settings, level)
    private val settings = shapes.settings

    /**
     * Every legal reading of [cards]: one entry per (declaration, combination). A wildcard may stand
     * for any non-joker rank (R7.2, R7.3); a suit is declared only for a straight flush (R7.10).
     * Throws [ImpossibleCardsException] for cards no deal can produce.
     */
    fun readings(cards: List<Card>): List<Reading> {
        impossibleCards(cards, level)?.let { throw ImpossibleCardsException(it) }
        val (wild, physical) = cards.partition { it.isWildcard(level) }
        if (wild.isEmpty()) return shapes.readings(physical, emptyList()).map { Reading(it, emptyList()) }

        // A declared suit is useful only when every physical card shares it (straight flush).
        val physicalSuits = physical.map { it.suit }.toSet()
        val flushSuit = physicalSuits.singleOrNull()?.takeIf { it != Suit.JOKER }
        val out = ArrayList<Reading>()
        for (ranks in rankMultisets(wild.size)) {
            if (physical.isEmpty() && !settings.wildcardAloneMayStandIn && ranks.any { it != level }) continue
            for (suit in listOf(null) + listOfNotNull(flushSuit)) {
                val declaration = ranks.map { WildcardAssignment(it, suit) }.canonical()
                shapes.readings(physical, declaration).forEach { out += Reading(it, declaration) }
            }
        }
        return out
    }

    /**
     * Judges a submitted play: its cards, the declaration for its wildcards (null if none given),
     * and the combination on the table (null when leading).
     */
    fun judge(cards: List<Card>, declaration: List<WildcardAssignment>?, table: Combination?): Judgment {
        if (cards.isEmpty()) return Judgment.Rejected(RejectReason.NO_COMBINATION)
        impossibleCards(cards, level)?.let { return Judgment.Rejected(it) }
        val (wild, physical) = cards.partition { it.isWildcard(level) }

        val combinations: List<Combination> = when {
            wild.isEmpty() -> {
                if (!declaration.isNullOrEmpty()) return Judgment.Rejected(RejectReason.DECLARATION_MISMATCH)
                shapes.readings(physical, emptyList()).ifEmpty { return Judgment.Rejected(RejectReason.NO_COMBINATION) }
            }
            declaration == null -> {
                if (settings.wildcardAlwaysDeclared) return Judgment.Rejected(RejectReason.MISSING_DECLARATION)
                val all = readings(cards)
                when {
                    all.isEmpty() -> return Judgment.Rejected(RejectReason.NO_COMBINATION)
                    // D15: an ambiguous play still needs a declaration unless the profile says otherwise.
                    all.size > 1 && settings.wildcardAmbiguousNeedsDeclaration -> return Judgment.Rejected(RejectReason.MISSING_DECLARATION)
                    else -> all.map { it.combination }.distinct()
                }
            }
            else -> {
                if (declaration.size != wild.size) return Judgment.Rejected(RejectReason.DECLARATION_MISMATCH)
                if (declaration.any { Ranks.isJoker(it.rank) }) return Judgment.Rejected(RejectReason.WILDCARD_AS_JOKER)
                if (physical.isEmpty() && !settings.wildcardAloneMayStandIn && declaration.any { it.rank != level }) {
                    return Judgment.Rejected(RejectReason.LONE_WILDCARD_REDECLARED)
                }
                val declared = declaration.canonical()
                shapes.readings(physical, declared)
                    .ifEmpty {
                        // D16: a suit that makes no straight flush rejects the play, or is ignored when the profile says so.
                        val unsuited = declared.map { it.copy(suit = null) }.canonical()
                        if (settings.wildcardStraySuitRejected || unsuited == declared) emptyList() else shapes.readings(physical, unsuited)
                    }
                    .ifEmpty { return Judgment.Rejected(RejectReason.DECLARED_READING_ILLEGAL) }
            }
        }
        val beating = if (table == null) combinations else combinations.filter { it.beats(table) }
        return Judgment.Readable(combinations, beating)
    }

    /** Non-decreasing rank lists of length [n] over 2..A (multisets: the two wildcards are alike). */
    private fun rankMultisets(n: Int): List<List<Int>> = when (n) {
        0 -> listOf(emptyList())
        else -> rankMultisets(n - 1).flatMap { prefix ->
            val from = prefix.lastOrNull() ?: Ranks.NATURAL.first
            (from..Ranks.NATURAL.last).map { prefix + it }
        }
    }
}
