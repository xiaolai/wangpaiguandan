package cn.wpgd.app.core.rules

import cn.wpgd.app.core.Card
import cn.wpgd.app.core.Suit

/**
 * Golden-case card notation (rules/golden/README.md): suit letter S/H/C/D plus rank 2-10, J, Q, K, A;
 * `LJ` small joker, `BJ` big joker. Every parsed card gets a fresh id, because a deal holds two
 * copies of each exact card.
 */
internal object Notation {
    private var next = 0

    fun rank(label: String): Int = when (label) {
        "J" -> 11; "Q" -> 12; "K" -> 13; "A" -> 14; "LJ" -> Ranks.SMALL_JOKER; "BJ" -> Ranks.BIG_JOKER
        else -> label.toIntOrNull()?.takeIf { it in 2..10 } ?: error("bad rank '$label'")
    }

    fun label(rank: Int): String = when (rank) {
        11 -> "J"; 12 -> "Q"; 13 -> "K"; 14 -> "A"; Ranks.SMALL_JOKER -> "LJ"; Ranks.BIG_JOKER -> "BJ"
        else -> rank.toString()
    }

    fun suit(letter: String): Suit = when (letter) {
        "S" -> Suit.SPADE; "H" -> Suit.HEART; "C" -> Suit.CLUB; "D" -> Suit.DIAMOND
        else -> error("bad suit '$letter'")
    }

    fun letter(suit: Suit): String = when (suit) {
        Suit.SPADE -> "S"; Suit.HEART -> "H"; Suit.CLUB -> "C"; Suit.DIAMOND -> "D"; Suit.JOKER -> error("joker has no suit letter")
    }

    fun card(code: String): Card {
        val id = "n${next++}-$code"
        return when (code) {
            "LJ" -> Card(id, Suit.JOKER, Ranks.SMALL_JOKER)
            "BJ" -> Card(id, Suit.JOKER, Ranks.BIG_JOKER)
            else -> Card(id, suit(code.take(1)), rank(code.drop(1)))
        }
    }

    fun cards(codes: String): List<Card> = codes.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map(::card)

    fun code(card: Card): String = if (card.suit == Suit.JOKER) label(card.rank) else letter(card.suit) + label(card.rank)

    /** Declaration shorthand: "7", "10D" (rank then optional suit letter). */
    fun assignment(text: String): WildcardAssignment {
        val suitLetter = text.takeLast(1).takeIf { it in setOf("S", "H", "C", "D") && text.length > 1 && text != "LJ" && text != "BJ" }
        return if (suitLetter != null) WildcardAssignment(rank(text.dropLast(1)), suit(suitLetter)) else WildcardAssignment(rank(text))
    }

    fun declaration(vararg items: String): List<WildcardAssignment> = items.map(::assignment)
}

internal object Profiles {
    val p1: RuleProfile by lazy { loadOk(p1Json()) }

    /** A profile that overrides [options] (raw JSON values keyed by option id). */
    fun with(vararg options: Pair<String, String>): RuleProfile {
        val body = options.joinToString(",") { (k, v) -> "\"$k\":$v" }
        return loadOk("""{"id":"test-variant","version":"1.0.0","displayName":"T","provisional":true,"options":{$body}}""")
    }
}

/** Classifier plus generator for one profile and level. */
internal class Engine(profile: RuleProfile, val level: Int) {
    val settings = RuleSettings.of(profile)
    val classifier = Classifier(settings, level)
    val generator = LegalActionGenerator(settings, level)

    /** The single combination that [codes] (with [declaration]) form when led; fails otherwise. */
    fun combo(codes: String, vararg declaration: String): Combination {
        val j = classifier.judge(Notation.cards(codes), if (declaration.isEmpty()) null else Notation.declaration(*declaration), null)
        check(j is Judgment.Readable && j.readings.size == 1) { "'$codes' does not read as one combination: $j" }
        return j.readings.single()
    }

    fun judge(codes: String, vararg declaration: String, table: Combination? = null): Judgment =
        classifier.judge(Notation.cards(codes), if (declaration.isEmpty()) null else Notation.declaration(*declaration), table)
}

internal fun engine(level: Int, profile: RuleProfile = Profiles.p1) = Engine(profile, level)

/** Order-independent identity of an action: its face multiset, declaration and combination. */
internal data class ActionKey(val faces: List<String>, val declaration: List<WildcardAssignment>?, val combination: Combination)

internal fun LegalAction.key() = ActionKey(cards.map(Notation::code).sorted(), declaration, combination)

/** Pads [core] to 27 cards from a fresh two-deck set, never adding a card that [exclude] rejects. */
internal fun padTo27(core: List<Card>, exclude: (Card) -> Boolean): List<Card> {
    val used = core.groupingBy { Notation.code(it) }.eachCount().toMutableMap()
    val pad = ArrayList<Card>()
    for (c in cn.wpgd.app.core.fixtureDeck()) {
        if (core.size + pad.size == 27) break
        val code = Notation.code(c)
        if (exclude(c) || (used[code] ?: 0) >= 2) continue
        used[code] = (used[code] ?: 0) + 1
        pad += c
    }
    check(core.size + pad.size == 27)
    return core + pad
}
