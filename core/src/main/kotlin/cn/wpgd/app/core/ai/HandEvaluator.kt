package cn.wpgd.app.core.ai

import cn.wpgd.app.core.Card
import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.rules.Ranks

/**
 * A cheap count-based estimate of how hard a hand is to play out. Lower is better. It is a
 * heuristic, not a solver: it decomposes the hand greedily and never enumerates card subsets.
 *
 * Decomposition, in order: four jokers; same-rank bombs (four or more); a triple plus a wildcard as
 * a bomb; straights of five lone ranks (a wildcard may fill one gap); runs of three pairs; runs of
 * two triples; spare wildcards joining the lowest natural groups (a pair and two wildcards is a
 * bomb); then singles, pairs and triples, each triple carrying one pair.
 *
 * score = plays + low-card penalty − [BOMB_VALUE] × bombs − [CONTROL_VALUE] × high cards
 *
 * - plays: how many plays the decomposition needs.
 * - low-card penalty: lone low singles and pairs are hard to get rid of.
 * - bombs and high cards (A, level cards, jokers, wildcards) win the lead back, so keeping them is
 *   worth something. [BOMB_VALUE] above 1 makes spending a bomb cost more than the play it saves.
 */
internal object HandEvaluator {
    const val BOMB_VALUE = 1.5
    const val CONTROL_VALUE = 0.25

    class Shape(val plays: Int, val bombs: Int, val control: Int, val lowPenalty: Double) {
        val score: Double get() = plays + lowPenalty - BOMB_VALUE * bombs - CONTROL_VALUE * control
    }

    fun score(cards: Collection<Card>, level: Int): Double = shape(cards, level).score

    fun shape(cards: Collection<Card>, level: Int): Shape {
        if (cards.isEmpty()) return Shape(0, 0, 0, 0.0)
        val counts = IntArray(Ranks.ACE + 1)
        var small = 0
        var big = 0
        var wild = 0
        var control = 0
        for (c in cards) {
            when {
                c.isWildcard(level) -> wild++
                c.suit == Suit.JOKER -> if (c.rank == Ranks.SMALL_JOKER) small++ else big++
                else -> counts[c.rank]++
            }
            if (Ranks.power(c.rank, level) >= Ranks.ACE || c.isWildcard(level)) control++
        }
        var plays = 0
        var bombs = 0
        var lowPenalty = 0.0
        val jokerBomb = small == 2 && big == 2
        if (jokerBomb) {
            bombs++
            small = 0
            big = 0
        }
        for (r in Ranks.NATURAL) if (counts[r] >= 4) {
            bombs++
            counts[r] = 0
        }
        while (wild > 0) {
            val r = Ranks.NATURAL.firstOrNull { counts[it] == 3 } ?: break
            bombs++
            counts[r] = 0
            wild--
        }
        val (straights, gapsFilled) = takeRuns(counts, 5, 1, wildAvailable = wild)
        wild -= gapsFilled
        plays += straights
        plays += takeRuns(counts, 3, 2, wildAvailable = 0).first
        plays += takeRuns(counts, 2, 3, wildAvailable = 0).first
        // Spare wildcards join natural groups, and the decomposition records what they make: a pair
        // and two wildcards is a bomb; one wildcard makes a single a pair, a pair a triple. The lowest
        // such rank first (it is the hardest to get rid of). Never a joker. (A triple and one wildcard
        // is a bomb too, but no triple is left here while a wildcard is: the loop above made every
        // triple a bomb, runs only remove ranks, and a pair becomes a triple only with the last wildcard.)
        while (wild > 0) {
            val lowest = { n: Int -> Ranks.NATURAL.filter { counts[it] == n }.minByOrNull { Ranks.power(it, level) } }
            val pair = lowest(2)
            when {
                pair != null && wild >= 2 -> { bombs++; counts[pair] = 0; wild -= 2 }
                else -> {
                    val r = lowest(1) ?: pair ?: break
                    counts[r]++
                    wild--
                }
            }
        }
        // Same-rank bombs can absorb a spare wildcard; the four-joker bomb cannot.
        val naturalBombs = bombs - (if (jokerBomb) 1 else 0)

        var singles = 0
        var pairs = 0
        var triples = 0
        for (r in Ranks.NATURAL) {
            val power = Ranks.power(r, level)
            when (counts[r]) {
                1 -> { singles++; if (power <= 10) lowPenalty += (11 - power) * 0.04 }
                2 -> { pairs++; if (power <= 10) lowPenalty += (11 - power) * 0.03 }
                3 -> triples++
            }
        }
        for (j in listOf(small, big)) when (j) {
            1 -> singles++
            2 -> pairs++
        }
        plays += singles + pairs + triples - minOf(triples, pairs) + bombs
        // Wildcards still spare had no natural group to join: a same-rank bomb absorbs them (a bigger
        // bomb, the same play); with no such bomb they need one play of their own.
        if (wild > 0 && naturalBombs == 0) plays++
        return Shape(plays, bombs, control, lowPenalty)
    }

    /**
     * Greedily removes runs of [length] consecutive ranks each holding exactly [width] cards, low
     * runs first (A-low first). While [wildAvailable] lasts, one rank of a single-card run may be
     * missing and filled by a wildcard. Returns (runs taken, wildcards used).
     */
    /** Rank windows of each run length [takeRuns] uses (A-low first), built once: they depend only on the length. */
    private val WINDOWS: Map<Int, List<List<Int>>> = listOf(2, 3, 5).associateWith { length ->
        buildList {
            add(listOf(Ranks.ACE) + (2 until 2 + length - 1).toList())
            for (start in 2..Ranks.ACE - length + 1) add((start until start + length).toList())
        }
    }

    private fun takeRuns(counts: IntArray, length: Int, width: Int, wildAvailable: Int): Pair<Int, Int> {
        var runs = 0
        var usedWild = 0
        var wildLeft = wildAvailable
        val windows = WINDOWS.getValue(length)
        var progress = true
        while (progress) {
            progress = false
            for (w in windows) {
                val missing = w.count { counts[it] == 0 }
                if (w.any { counts[it] != 0 && counts[it] != width }) continue
                if (missing == 0 || (missing == 1 && width == 1 && wildLeft > 0)) {
                    for (r in w) if (counts[r] > 0) counts[r] -= width
                    if (missing == 1) { usedWild++; wildLeft-- }
                    runs++
                    progress = true
                    break
                }
            }
        }
        return runs to usedWild
    }
}
