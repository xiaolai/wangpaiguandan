package cn.wpgd.app.core.match

import cn.wpgd.app.core.Card
import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.StandardDeck
import kotlinx.serialization.Serializable

@Serializable
enum class TributeKind { SINGLE, DOUBLE }

/** Who owes tribute to whom before a deal (match-v1 M4.2, M4.5, M4.6). [receivers] lists 上游 first. */
data class TributeObligation(val kind: TributeKind, val givers: List<Seat>, val receivers: List<Seat>)

/**
 * The tribute, return and resistance rules of match-v1 sections 4–6 as pure functions over card
 * ids, so the host validator, the client pre-check and the bots all use the same code (GDN-036).
 */
object TributeRules {
    /** R1.5 comparison value of one card at [level]: level cards above A, jokers above them. Suit never matters. */
    fun power(card: Card, level: Int): Int = Ranks.power(card.rank, level)

    /** The tribute owed after [previous] (M4.2, M4.5). */
    fun obligation(previous: DealResult): TributeObligation {
        val first = previous.finishOrder.first()
        return when (previous.completion) {
            Completion.DOUBLE_UP_ENDED_EARLY ->
                TributeObligation(TributeKind.DOUBLE, first.team.other.seats, listOf(first, first.partner))
            Completion.THREE_FINISHED ->
                TributeObligation(TributeKind.SINGLE, listOf(checkNotNull(previous.lastSeat)), listOf(first))
        }
    }

    /** M4.3, M4.4: every card of the highest R1.5 rank in [hand], heart level cards (wildcards) excluded. */
    fun legalTributeCards(hand: Collection<String>, level: Int): List<String> {
        val candidates = cards(hand).filter { !it.isWildcard(level) }
        val top = candidates.maxOfOrNull { power(it, level) } ?: return emptyList()
        return candidates.filter { power(it, level) == top }.map { it.id }.sorted()
    }

    /** M5.2–M5.4: the cards [hand] may return. The lowest cards when none is within the limit. */
    fun legalReturnCards(hand: Collection<String>, level: Int, settings: MatchSettings): List<String> {
        val all = cards(hand)
        if (all.isEmpty()) return emptyList()
        val within = all.filter { withinReturnLimit(it, level, settings) }
        if (within.isNotEmpty()) return within.map { it.id }.sorted()
        val lowest = all.minOf { power(it, level) }
        return all.filter { power(it, level) == lowest }.map { it.id }.sorted()
    }

    /** M5.2, M5.3: a card meets the return limit. Jokers never do; a level card counts by face only under D29's variant. */
    fun withinReturnLimit(card: Card, level: Int, settings: MatchSettings): Boolean {
        val max = settings.returnMaxRank ?: return true
        if (Ranks.isJoker(card.rank)) return false
        val value = if (settings.returnLevelCardsByFace) card.rank else power(card, level)
        return value <= max
    }

    /** Big-joker ids in [hand], sorted. */
    fun bigJokers(hand: Collection<String>): List<String> = cards(hand).filter { it.rank == Ranks.BIG_JOKER }.map { it.id }.sorted()

    /**
     * M6.1, M6.2: the givers holding the two big jokers when the giving side may resist, or null.
     * One giver: it must hold both. Two givers: both in one hand, or (D30) one in each hand.
     */
    fun resistanceHolders(givers: List<Seat>, hands: Map<Seat, Set<String>>, settings: MatchSettings): List<Seat>? {
        val counts = givers.associateWith { bigJokers(hands.getValue(it)).size }
        val oneHand = counts.filterValues { it >= 2 }.keys
        if (oneHand.isNotEmpty()) return oneHand.toList()
        if (givers.size == 2 && settings.resistanceSplitBetweenPartners && counts.values.sum() >= 2) return givers.filter { counts.getValue(it) > 0 }
        return null
    }

    /**
     * M4.6, M4.7: giver → receiver once every tribute is given. Single: to 上游. Double: the higher card
     * to 上游 and the lower to 上游's partner; equal ranks go clockwise, each giver to its 上家.
     */
    fun route(obligation: TributeObligation, given: Map<Seat, String>, level: Int): Map<Seat, Seat> {
        require(given.keys == obligation.givers.toSet()) { "route needs every tribute, have ${given.keys}" }
        if (obligation.kind == TributeKind.SINGLE) return mapOf(obligation.givers.single() to obligation.receivers.single())
        val (a, b) = obligation.givers
        val pa = power(card(given.getValue(a)), level)
        val pb = power(card(given.getValue(b)), level)
        val (first, second) = obligation.receivers
        return when {
            pa > pb -> mapOf(a to first, b to second)
            pb > pa -> mapOf(b to first, a to second)
            else -> mapOf(a to a.upper, b to b.upper).also { r -> check(r.values.toSet() == obligation.receivers.toSet()) { "clockwise routing $r misses a receiver" } }
        }
    }

    private fun card(id: String): Card = checkNotNull(StandardDeck[id]) { "unknown card id $id" }

    private fun cards(ids: Collection<String>): List<Card> = ids.map(::card)
}
