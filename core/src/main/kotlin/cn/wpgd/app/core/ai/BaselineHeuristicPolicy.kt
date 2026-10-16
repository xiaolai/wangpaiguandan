package cn.wpgd.app.core.ai

import cn.wpgd.app.core.Card
import cn.wpgd.app.core.rules.LegalAction
import cn.wpgd.app.core.state.MemberView
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.nextInt

/**
 * The baseline heuristic policy, "baseline-heuristic" version 1.0.0 (WI-07). Clean-room: designed
 * from base-v1, match-v1 and the observation type only.
 *
 * It reads only its [PolicyInput]: its own hand, the public table (current trick, who leads, who has
 * played out), and the card counts the table has made public (threshold reports and answers, match-v1
 * section 8). It never sees another hand or a hidden count. Every candidate is scored by
 * [HandEvaluator] on the hand that would remain.
 *
 * 1. Resistance: claim it whenever it is offered.
 * 2. Tribute and return: give the legal card whose loss leaves the best-scoring hand.
 * 3. Any play that empties the hand is played at once, bombs included (audit A06, A07).
 * 4. Following a partner who holds the trick: pass. The one exception is (3): going out is the
 *    documented tactical benefit (audit A08, GDN-057).
 * 5. Following an opponent: the best non-bomb play if it costs at most a style margin over passing
 *    (more when that opponent or the partner has publicly reported a low count); otherwise a bomb only
 *    if the hand can then finish quickly or the opponent's reported count is low; otherwise pass.
 * 6. Leading: the play that leaves the best hand. Bombs are kept unless they end the hand. Small
 *    plays an opponent with a reported low count could follow are avoided; small low plays are
 *    preferred when the partner has reported a low count (feeding).
 * 7. Exact ties are broken by the context's seeded tie-break source.
 */
object BaselineHeuristicPolicy : AiPolicy {
    override val id = PolicyId("baseline-heuristic", "1.0.0")

    private const val EPSILON = 1e-9

    override fun decide(input: PolicyInput, context: DecisionContext): AiAction {
        val c = input.choices
        context.spend()
        if (c.mayResist) return AiAction.Resist.also(context::offer)
        if (c.tributeCards.isNotEmpty()) return bestCard(input, context, c.tributeCards, AiAction::GiveTribute)
        if (c.returnCards.isNotEmpty()) return bestCard(input, context, c.returnCards, AiAction::ReturnTribute)
        val deal = checkNotNull(input.observation.deal) { "a play decision needs a deal" }
        if (c.plays.isEmpty()) return AiAction.Pass.also(context::offer)
        return if (deal.table.trick.isEmpty()) lead(input, deal, context) else follow(input, deal, context)
    }

    private fun bestCard(input: PolicyInput, context: DecisionContext, cards: List<String>, make: (String) -> AiAction): AiAction {
        val best = Best(context)
        for (id in cards) {
            context.spend()
            best.consider(make(id), HandEvaluator.score(input.hand.filter { it.id != id }, input.level))
        }
        return best.pick()
    }

    private fun lead(input: PolicyInput, deal: MemberView, context: DecisionContext): AiAction {
        val seat = input.seat
        val table = PublicCounts(input, deal)
        val opponentBound = table.lowestOpponentBound(seat)
        val partnerBound = table.partnerBound(seat)
        val best = Best(context)
        // Rule 6: a bomb that does not end the hand is led only when no other play exists.
        val bombs = Best(context, offering = false)
        for (a in input.choices.plays) {
            context.spend()
            val remaining = remove(input.hand, a)
            if (remaining.isEmpty()) return AiAction.Play(a).also(context::offer)
            var s = HandEvaluator.score(remaining, input.level)
            val bomb = a.combination.type.isBombClass
            if (!bomb && opponentBound != null && opponentBound <= 3 && a.cards.size <= opponentBound) s += 0.6
            if (!bomb && partnerBound != null && a.combination.key <= 10) {
                if (partnerBound == 1 && a.cards.size == 1) s -= 2.0
                else if (partnerBound <= 5 && a.cards.size <= partnerBound) s -= 0.3
            }
            s -= 0.01 * a.cards.size
            s += 0.001 * a.combination.key
            if (bomb) {
                bombs.consider(AiAction.Play(a), s)
                if (best.leader() == null) bombs.leader()?.let(context::offer)
            } else {
                best.consider(AiAction.Play(a), s)
            }
        }
        return best.pickOrNull() ?: bombs.pick()
    }

    private fun follow(input: PolicyInput, deal: MemberView, context: DecisionContext): AiAction {
        val seat = input.seat
        context.offer(AiAction.Pass)
        input.choices.plays.firstOrNull { it.cards.size == input.hand.size }?.let { return AiAction.Play(it).also(context::offer) }
        val top = deal.table.trick.last()
        // Never overtake the partner's winning play except to go out (handled above).
        if (top.seat == seat.partner) return AiAction.Pass
        val table = PublicCounts(input, deal)
        val topBound = table.bound(top.seat)
        val partnerBound = table.partnerBound(seat)
        val style = context.style
        var margin = when (style) {
            AiStyle.CAUTIOUS -> 0.0
            AiStyle.BALANCED -> 0.3
            AiStyle.BOLD -> 0.6
        }
        if (topBound != null) margin += 0.5
        if (partnerBound != null && partnerBound <= 5) margin += 0.3
        val before = HandEvaluator.score(input.hand, input.level)
        val plain = Best(context, offering = false)
        val bombs = Best(context, offering = false)
        for (a in input.choices.plays) {
            context.spend()
            val remaining = remove(input.hand, a)
            val shape = HandEvaluator.shape(remaining, input.level)
            val s = shape.score + 0.001 * a.combination.key
            if (!a.combination.type.isBombClass) {
                if (s - before <= margin) plain.consider(AiAction.Play(a), s)
            } else if (bombAllowed(style, shape.plays, topBound)) {
                bombs.consider(AiAction.Play(a), s)
            }
            (plain.leader() ?: bombs.leader())?.let(context::offer)
        }
        return plain.pickOrNull() ?: bombs.pickOrNull() ?: AiAction.Pass
    }

    /** A bomb is spent on an opponent's play only when the hand can then finish soon, or that opponent is publicly close to going out. */
    private fun bombAllowed(style: AiStyle, playsLeft: Int, opponentBound: Int?): Boolean = when (style) {
        AiStyle.CAUTIOUS -> playsLeft <= 1 || (opponentBound != null && opponentBound <= 3)
        AiStyle.BALANCED -> playsLeft <= 2 || (opponentBound != null && opponentBound <= 6)
        AiStyle.BOLD -> playsLeft <= 3 || opponentBound != null
    }

    private fun remove(hand: List<Card>, action: LegalAction): List<Card> {
        val gone = action.cards.mapTo(HashSet()) { it.id }
        return hand.filter { it.id !in gone }
    }

    /** Lowest score wins; exact ties are broken by the seeded tie-break source when picked. */
    private class Best(private val context: DecisionContext, private val offering: Boolean = true) {
        private var score = Double.POSITIVE_INFINITY
        private val tied = ArrayList<AiAction>()

        fun consider(action: AiAction, s: Double) {
            when {
                s < score - EPSILON -> { score = s; tied.clear(); tied += action; if (offering) context.offer(action) }
                s <= score + EPSILON -> tied += action
            }
        }

        fun leader(): AiAction? = tied.firstOrNull()

        fun pickOrNull(): AiAction? = when (tied.size) {
            0 -> null
            1 -> tied[0]
            else -> tied[context.tieBreak.nextInt(tied.size)]
        }

        fun pick(): AiAction = checkNotNull(pickOrNull()) { "no candidate" }
    }
}

/**
 * Card counts the table has made public in this deal (match-v1 section 8): threshold reports and
 * answers to an opponent's question. Each is an upper bound on that seat's current count. Seats that
 * have played out hold zero. No other count is known to any seat.
 */
internal class PublicCounts(input: PolicyInput, deal: MemberView) {
    private val finished = deal.table.finishOrder.toSet()
    private val bounds: Map<Seat, Int> = buildMap {
        for (r in input.observation.match.reports) merge(r.seat, r.count, ::minOf)
        for (a in input.observation.match.answers) merge(a.seat, a.count, ::minOf)
    }

    /** The public upper bound on [seat]'s count, or null when nothing is public (or it has played out). */
    fun bound(seat: Seat): Int? = if (seat in finished) null else bounds[seat]

    fun partnerBound(seat: Seat): Int? = bound(seat.partner)

    fun lowestOpponentBound(seat: Seat): Int? = listOf(seat.next, seat.partner.next).mapNotNull(::bound).minOrNull()
}
