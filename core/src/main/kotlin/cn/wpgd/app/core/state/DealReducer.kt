package cn.wpgd.app.core.state

import cn.wpgd.app.core.Card
import cn.wpgd.app.core.rules.Classifier
import cn.wpgd.app.core.rules.Judgment
import cn.wpgd.app.core.rules.LegalAction
import cn.wpgd.app.core.rules.LegalActionGenerator
import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.core.rules.RuleProfile
import cn.wpgd.app.core.rules.RuleSettings

/**
 * The deterministic reducer of one deal: `(state, command) -> (state', events) | rejection`.
 *
 * Legality comes from the WI-02 [Classifier] and [LegalActionGenerator] under [profile]'s effective
 * options; this class adds only turn order, ownership and the trick and deal lifecycle (base-v1
 * section 9, D19–D23). Every new state is made by applying the emitted events with
 * [DealEvents.apply], so the events alone reproduce it. No clock, no platform randomness: the only
 * randomness is the [RandomSource] passed in [Command.Start].
 */
class DealReducer(private val profile: RuleProfile) {
    private val settings = RuleSettings.of(profile)
    private val classifiers = Ranks.NATURAL.associateWith { lazy { Classifier(settings, it) } }
    private val generators = Ranks.NATURAL.associateWith { lazy { LegalActionGenerator(settings, it) } }

    /** A deal at [level] waiting for [Command.Start]. Revision 0. */
    fun newDeal(level: Int): DealState {
        require(level in Ranks.NATURAL) { "level must be a rank 2..A, got $level" }
        return DealState.awaiting(level, profile.hash)
    }

    fun reduce(state: DealState, command: Command): ReduceResult {
        require(state.profileHash == profile.hash) { "state belongs to profile ${state.profileHash}, reducer to ${profile.hash}" }
        return when (command) {
            is Command.Start -> start(state, command)
            is Command.Play -> play(state, command)
            is Command.Pass -> pass(state, command)
        }
    }

    /**
     * Applies a match-layer [SetupCommand] (WI-04): deal without a lead, move tribute and return
     * cards while [Phase.DEALT], then open play. Same contract as [reduce]: a rejection changes nothing.
     */
    fun setup(state: DealState, command: SetupCommand): ReduceResult {
        require(state.profileHash == profile.hash) { "state belongs to profile ${state.profileHash}, reducer to ${profile.hash}" }
        val rev = state.revision + 1
        return when (command) {
            is SetupCommand.Deal -> {
                if (state.phase != Phase.AWAITING_DEAL) return reject(Rejection.WrongPhase(state.phase))
                accept(state, listOf(DealEvent.Dealt(rev, state.level, state.profileHash)) + dealtHands(rev, command.random))
            }
            is SetupCommand.MoveCard -> when {
                state.phase != Phase.DEALT -> reject(Rejection.WrongPhase(state.phase))
                command.from == command.to -> reject(Rejection.SelfMove(command.from))
                command.cardId !in state.hands.getValue(command.from) -> reject(Rejection.UnownedCard(listOf(command.cardId)))
                else -> accept(state, listOf(DealEvent.CardMoved(rev, command.from, command.to, command.cardId)))
            }
            is SetupCommand.BeginPlay ->
                if (state.phase != Phase.DEALT) reject(Rejection.WrongPhase(state.phase))
                else accept(state, listOf(DealEvent.PlayBegan(rev, command.leader)))
        }
    }

    /**
     * Every legal play for [seat] now (empty unless it is [seat]'s turn). Passing is not listed;
     * a follower may always pass.
     */
    fun legalActions(state: DealState, seat: Seat): List<LegalAction> {
        require(state.profileHash == profile.hash) { "state belongs to another profile" }
        if (state.phase != Phase.PLAYING || state.turn != seat) return emptyList()
        return generators.getValue(state.level).value.generate(cards(state.hands.getValue(seat)), state.trick.top?.combination)
    }

    private fun start(state: DealState, c: Command.Start): ReduceResult {
        if (state.phase != Phase.AWAITING_DEAL) return reject(Rejection.WrongPhase(state.phase))
        val rev = state.revision + 1
        return accept(state, listOf(DealEvent.DealOpened(rev, state.level, state.profileHash, c.openingLeader)) + dealtHands(rev, c.random))
    }

    /** Shuffles with [random] and deals round-robin from SOUTH: card i goes to seat i mod 4 (cross-platform deal contract). */
    private fun dealtHands(rev: Long, random: RandomSource): List<DealEvent> {
        val deck = StandardDeck.cards.shuffledWith(random)
        return Seat.entries.map { seat ->
            DealEvent.HandDealt(rev, seat, deck.filterIndexed { i, _ -> i % Seat.entries.size == seat.ordinal }.map { it.id }.sorted())
        }
    }

    private fun play(state: DealState, c: Command.Play): ReduceResult {
        actorProblem(state, c.seat)?.let { return reject(it) }
        if (c.cardIds.isEmpty()) return reject(Rejection.EmptySelection)
        val repeated = c.cardIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (repeated.isNotEmpty()) return reject(Rejection.DuplicateCard(repeated.sorted()))
        val hand = state.hands.getValue(c.seat)
        val unowned = c.cardIds.filter { it !in hand }
        if (unowned.isNotEmpty()) return reject(Rejection.UnownedCard(unowned.sorted()))

        val table = state.trick.top?.combination
        val judgment = classifiers.getValue(state.level).value.judge(cards(c.cardIds), c.declaration, table)
        val beating = when (judgment) {
            is Judgment.Rejected -> return reject(Rejection.IllegalCombination(judgment.reason))
            is Judgment.Readable -> if (judgment.legal) judgment.beating.distinct() else return reject(Rejection.DoesNotBeatTrick)
        }
        val chosen = if (c.combinationType == null) beating else beating.filter { it.type == c.combinationType }
        val combination = when {
            chosen.isEmpty() -> return reject(Rejection.ReadingNotAvailable(checkNotNull(c.combinationType)))
            chosen.size > 1 -> return reject(Rejection.AmbiguousReading(chosen.map { it.type }))
            else -> chosen.single()
        }

        val rev = state.revision + 1
        return Emitter(state).run {
            // A copy: the caller's declaration list must not alias committed state or the emitted event.
            emit(DealEvent.Played(rev, c.seat, c.cardIds.sorted(), c.declaration?.toList(), combination))
            if (s.hands.getValue(c.seat).isEmpty()) emit(DealEvent.SeatFinished(rev, c.seat, s.finishOrder.size + 1))
            if (dealOver(s.finishOrder)) {
                emit(DealEvent.DealEnded(rev, s.finishOrder, Seat.entries.filter { !s.isFinished(it) }))
            } else {
                emit(DealEvent.TurnMoved(rev, nextActive(s, c.seat)))
            }
            accepted()
        }
    }

    private fun pass(state: DealState, c: Command.Pass): ReduceResult {
        actorProblem(state, c.seat)?.let { return reject(it) }
        val top = state.trick.top ?: return reject(Rejection.PassAsLeader)

        val rev = state.revision + 1
        return Emitter(state).run {
            emit(DealEvent.Passed(rev, c.seat))
            // D19: the trick ends when every seat still holding cards, other than the winner, has passed since the winning play.
            val mustPass = Seat.entries.filter { it != top.seat && !s.isFinished(it) }
            if (s.trick.passedSinceTop.containsAll(mustPass)) {
                val winnerOut = s.isFinished(top.seat)
                // D20 (接风): a winner who has played out hands the lead to the partner. If the partner had
                // also played out, D21 would already have ended the deal, so the partner is active here.
                val next = if (winnerOut) top.seat.partner else top.seat
                check(!s.isFinished(next)) { "lead would pass to $next, who has played out; D21 should have ended the deal" }
                emit(DealEvent.TrickEnded(rev, top.seat, next, partnerLead = winnerOut))
            } else {
                emit(DealEvent.TurnMoved(rev, nextActive(s, c.seat)))
            }
            accepted()
        }
    }

    /** Accumulates one command's events, applying each to the working state as it is emitted. */
    private class Emitter(var s: DealState) {
        private val events = ArrayList<DealEvent>()

        fun emit(e: DealEvent) {
            events += e
            s = DealEvents.apply(s, e)
        }

        fun accepted() = ReduceResult.Accepted(s, events)
    }

    /** Phase, finished-seat and turn checks shared by Play and Pass. */
    private fun actorProblem(state: DealState, seat: Seat): Rejection? = when {
        state.phase != Phase.PLAYING -> Rejection.WrongPhase(state.phase)
        state.isFinished(seat) -> Rejection.SeatAlreadyFinished(seat)
        state.turn != seat -> Rejection.WrongSeat(expected = checkNotNull(state.turn), actual = seat)
        else -> null
    }

    /** D22: the next seat in play order that still holds cards. */
    private fun nextActive(state: DealState, from: Seat): Seat {
        var s = from.next
        while (state.isFinished(s)) {
            check(s != from) { "no seat holds cards" }
            s = s.next
        }
        return s
    }

    private fun accept(state: DealState, events: List<DealEvent>) =
        ReduceResult.Accepted(DealEvents.replay(state, events), events)

    private fun reject(r: Rejection) = ReduceResult.Rejected(r)

    private fun cards(ids: Collection<String>): List<Card> = ids.map { checkNotNull(StandardDeck[it]) { "unknown card id $it" } }
}

/** D21: three seats have played out, or one team took first and second place (双下). */
internal fun dealOver(order: List<Seat>): Boolean = order.size >= 3 || (order.size == 2 && order[0].partner == order[1])
