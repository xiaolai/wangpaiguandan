package cn.wpgd.app.core.match

import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.rules.LegalAction
import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.core.rules.RuleProfile
import cn.wpgd.app.core.state.Command
import cn.wpgd.app.core.state.DealReducer
import cn.wpgd.app.core.state.ReduceResult
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.SetupCommand
import cn.wpgd.app.core.state.StandardDeck
import cn.wpgd.app.core.state.nextInt

/**
 * The deterministic match reducer: `(state, command) -> (state', events) | rejection` for everything
 * between and around deals (match-v1, gate G1b), under a base profile ([base], base-v1) and a match
 * profile ([match], match-v1). Plays and passes go to the WI-03 [DealReducer]; this class adds
 * dealing at the match level, tribute, return, resistance, the opening lead, card-count reporting
 * and settlement. Every new state is made by applying the emitted events with [MatchEvents.apply].
 */
class MatchReducer(private val base: RuleProfile, private val match: RuleProfile) {
    val settings: MatchSettings = MatchSettings.of(match)
    val dealReducer: DealReducer = DealReducer(base)

    /** A new match; [matchNumber] is 1 for a new room (a rematch numbers itself through [MatchCommand.Rematch]). */
    fun newMatch(matchNumber: Int = 1): MatchState = MatchState.initial(base.hash, match.hash, matchNumber)

    fun reduce(state: MatchState, command: MatchCommand): MatchReduceResult {
        require(state.baseProfileHash == base.hash && state.matchProfileHash == match.hash) { "state belongs to other profiles" }
        if (command == MatchCommand.Rematch) return rematch(state)
        if (state.phase == MatchPhase.OVER) return reject(MatchRejection.MatchOver)
        return when (command) {
            is MatchCommand.DealNext -> dealNext(state, command)
            MatchCommand.StartTribute -> startTribute(state)
            is MatchCommand.GiveTribute -> giveTribute(state, command)
            is MatchCommand.Resist -> resist(state, command)
            is MatchCommand.ReturnTribute -> returnTribute(state, command)
            MatchCommand.OpenPlay -> openPlay(state)
            is MatchCommand.InDeal -> inDeal(state, command.command)
            is MatchCommand.AskCardCount -> ask(state, command)
            MatchCommand.Settle -> settle(state)
            MatchCommand.Rematch -> error("handled above")
        }
    }

    /** GDN-017: a finished match restarts at level 2 under the same profiles, at the next revision. */
    private fun rematch(state: MatchState): MatchReduceResult {
        if (state.phase != MatchPhase.OVER) return reject(MatchRejection.WrongPhase(state.phase))
        return batch(state) { emit(MatchEvent.MatchRestarted(rev)) }
    }

    /** Tribute cards [seat] may give now; empty unless it owes one. */
    fun legalTributeCards(state: MatchState, seat: Seat): List<String> {
        val t = state.tribute ?: return emptyList()
        if (state.phase != MatchPhase.TRIBUTE || seat !in t.obligation.givers || seat in t.given) return emptyList()
        return TributeRules.legalTributeCards(hand(state, seat), state.dealLevel)
    }

    /** Return cards [seat] may give now; empty unless it owes one. Same code as the host check (GDN-036). */
    fun legalReturnCards(state: MatchState, seat: Seat): List<String> {
        val t = state.tribute ?: return emptyList()
        if (state.phase != MatchPhase.RETURN || seat !in t.routes.values || seat in t.returns) return emptyList()
        return TributeRules.legalReturnCards(hand(state, seat), state.dealLevel, settings)
    }

    /** True while a giver may claim resistance. */
    fun mayResist(state: MatchState): Boolean {
        val t = state.tribute ?: return false
        return state.phase == MatchPhase.TRIBUTE && settings.resistanceMode == ResistanceMode.CLAIMED && t.given.isEmpty() &&
            TributeRules.resistanceHolders(t.obligation.givers, checkNotNull(state.deal).hands, settings) != null
    }

    /** Legal plays of the current deal for [seat] (WI-03 generator). */
    fun legalActions(state: MatchState, seat: Seat): List<LegalAction> =
        if (state.phase == MatchPhase.PLAYING) dealReducer.legalActions(checkNotNull(state.deal), seat) else emptyList()

    private fun dealNext(state: MatchState, c: MatchCommand.DealNext): MatchReduceResult {
        if (state.phase != MatchPhase.AWAITING_DEAL) return reject(MatchRejection.WrongPhase(state.phase))
        return batch(state) {
            emit(MatchEvent.DealStarted(rev, s.dealNumber + 1, s.dealLevel))
            deal(dealReducer.setup(checkNotNull(s.deal), SetupCommand.Deal(c.random)))
            if (s.dealNumber == 1) {
                // M7.1: no tribute before the first deal; the profile names its leader.
                when (settings.firstDealLead) {
                    FirstDealLead.CUT_CARD -> {
                        val card = cutCard(c)
                        val holder = Seat.entries.first { card in checkNotNull(s.deal).hands.getValue(it) }
                        emit(MatchEvent.CutCardDrawn(rev, card, holder))
                        emit(MatchEvent.LeaderResolved(rev, holder, LeadReason.CUT_CARD))
                    }
                    FirstDealLead.EAST -> emit(MatchEvent.LeaderResolved(rev, Seat.EAST, LeadReason.FIXED_EAST))
                }
            }
        }
    }

    /** M7.1 (JS17:56): a card drawn uniformly from the deck; a joker or the heart 2 is drawn again. */
    private fun cutCard(c: MatchCommand.DealNext): String {
        while (true) {
            val card = StandardDeck.cards[c.random.nextInt(StandardDeck.cards.size)]
            if (Ranks.isJoker(card.rank) || (card.suit == Suit.HEART && card.rank == 2)) continue
            return card.id
        }
    }

    private fun startTribute(state: MatchState): MatchReduceResult {
        if (state.phase != MatchPhase.DEALT || state.leader != null) return reject(MatchRejection.WrongPhase(state.phase))
        val previous = checkNotNull(state.previous) { "a deal after the first has a previous result" }
        val o = TributeRules.obligation(previous)
        return batch(state) {
            emit(MatchEvent.TributeStarted(rev, o.kind, o.givers, o.receivers))
            if (settings.resistanceMode == ResistanceMode.AUTOMATIC) {
                TributeRules.resistanceHolders(o.givers, checkNotNull(s.deal).hands, settings)?.let { resisted(null, it) }
            }
        }
    }

    private fun giveTribute(state: MatchState, c: MatchCommand.GiveTribute): MatchReduceResult {
        if (state.phase != MatchPhase.TRIBUTE) return reject(MatchRejection.WrongPhase(state.phase))
        val t = checkNotNull(state.tribute)
        if (c.seat !in t.obligation.givers) return reject(MatchRejection.NotAGiver(c.seat))
        if (c.seat in t.given) return reject(MatchRejection.AlreadyGave(c.seat))
        val hand = hand(state, c.seat)
        if (c.cardId !in hand) return reject(MatchRejection.UnownedCard(c.cardId))
        if (checkNotNull(StandardDeck[c.cardId]).isWildcard(state.dealLevel)) return reject(MatchRejection.WildcardNotTributable(c.cardId))
        if (c.cardId !in TributeRules.legalTributeCards(hand, state.dealLevel)) return reject(MatchRejection.NotHighestCard(c.cardId))
        return batch(state) {
            emit(MatchEvent.TributeGiven(rev, c.seat, c.cardId))
            val now = checkNotNull(s.tribute)
            if (now.allGiven) {
                val routes = TributeRules.route(now.obligation, now.given, s.dealLevel)
                val transfers = now.obligation.givers.map { g -> CardTransfer(g, routes.getValue(g), now.given.getValue(g)) }
                emit(MatchEvent.TributeRouted(rev, transfers))
                for (tr in transfers) deal(dealReducer.setup(checkNotNull(s.deal), SetupCommand.MoveCard(tr.from, tr.to, tr.cardId)))
            }
        }
    }

    private fun resist(state: MatchState, c: MatchCommand.Resist): MatchReduceResult {
        if (state.phase != MatchPhase.TRIBUTE) return reject(MatchRejection.WrongPhase(state.phase))
        val t = checkNotNull(state.tribute)
        if (settings.resistanceMode != ResistanceMode.CLAIMED) return reject(MatchRejection.ResistanceIsAutomatic)
        if (c.seat !in t.obligation.givers) return reject(MatchRejection.NotAGiver(c.seat))
        if (t.given.isNotEmpty()) return reject(MatchRejection.ResistanceWaived)
        val holders = TributeRules.resistanceHolders(t.obligation.givers, checkNotNull(state.deal).hands, settings)
            ?: return reject(MatchRejection.ResistanceNotAvailable)
        return batch(state) { resisted(c.seat, holders) }
    }

    private fun returnTribute(state: MatchState, c: MatchCommand.ReturnTribute): MatchReduceResult {
        if (state.phase != MatchPhase.RETURN) return reject(MatchRejection.WrongPhase(state.phase))
        val t = checkNotNull(state.tribute)
        if (c.seat !in t.routes.values) return reject(MatchRejection.NotAReturner(c.seat))
        if (c.seat in t.returns) return reject(MatchRejection.AlreadyReturned(c.seat))
        val hand = hand(state, c.seat)
        if (c.cardId !in hand) return reject(MatchRejection.UnownedCard(c.cardId))
        if (c.cardId !in TributeRules.legalReturnCards(hand, state.dealLevel, settings)) return reject(MatchRejection.ReturnNotAllowed(c.cardId))
        return batch(state) {
            emit(MatchEvent.ReturnChosen(rev, c.seat, t.giverTo(c.seat), c.cardId))
            emit(MatchEvent.ReturnMade(rev, c.seat))
            val now = checkNotNull(s.tribute)
            if (now.allReturned) {
                // M5.5: returns are turned up together, then move to the givers.
                val transfers = now.obligation.receivers.map { r -> CardTransfer(r, now.giverTo(r), now.returns.getValue(r)) }
                emit(MatchEvent.ReturnsRevealed(rev, transfers))
                for (tr in transfers) deal(dealReducer.setup(checkNotNull(s.deal), SetupCommand.MoveCard(tr.from, tr.to, tr.cardId)))
                val first = now.obligation.receivers.first()
                when (now.obligation.kind) {
                    TributeKind.SINGLE -> emit(MatchEvent.LeaderResolved(rev, now.obligation.givers.single(), LeadReason.TRIBUTE_GIVER))
                    TributeKind.DOUBLE -> emit(MatchEvent.LeaderResolved(rev, now.giverTo(first), LeadReason.GIVER_TO_FIRST))
                }
            }
        }
    }

    private fun openPlay(state: MatchState): MatchReduceResult {
        if (state.phase != MatchPhase.READY) return reject(MatchRejection.WrongPhase(state.phase))
        return batch(state) { deal(dealReducer.setup(checkNotNull(s.deal), SetupCommand.BeginPlay(checkNotNull(s.leader)))) }
    }

    private fun inDeal(state: MatchState, command: Command): MatchReduceResult {
        if (command is Command.Start) return reject(MatchRejection.StartNotAllowed)
        if (state.phase != MatchPhase.PLAYING) return reject(MatchRejection.WrongPhase(state.phase))
        val result = dealReducer.reduce(checkNotNull(state.deal), command)
        if (result is ReduceResult.Rejected) return reject(MatchRejection.Deal(result.rejection))
        return batch(state) {
            deal(result)
            if (command is Command.Play) report(command.seat)
        }
    }

    private fun ask(state: MatchState, c: MatchCommand.AskCardCount): MatchReduceResult {
        if (!settings.answerWhenAsked) return reject(MatchRejection.AskingNotAllowed)
        if (state.phase != MatchPhase.PLAYING) return reject(MatchRejection.WrongPhase(state.phase))
        if (c.asker.team == c.target.team) return reject(MatchRejection.AskOwnTeam)
        val deal = checkNotNull(state.deal)
        if (deal.isFinished(c.target)) return reject(MatchRejection.AskTargetFinished)
        val count = deal.hands.getValue(c.target).size
        if (count > settings.askThreshold) return reject(MatchRejection.AskAboveThreshold)
        return batch(state) { emit(MatchEvent.CardCountAnswered(rev, c.asker, c.target, count)) }
    }

    private fun settle(state: MatchState): MatchReduceResult {
        if (state.phase != MatchPhase.DEAL_ENDED) return reject(MatchRejection.WrongPhase(state.phase))
        val result = Settlement.settle(state.dealNumber, state.dealLevel, checkNotNull(state.deal).finishOrder, state.levels, state.aFailures, settings)
        return batch(state) {
            emit(MatchEvent.DealSettled(rev, result))
            result.matchWinner?.let { emit(MatchEvent.MatchEnded(rev, it)) }
        }
    }

    /** Accumulates one command's events, applying each as it is emitted. */
    private inner class Batch(var s: MatchState) {
        val rev = s.revision + 1
        val events = ArrayList<MatchEvent>()

        fun emit(e: MatchEvent) {
            events += e
            s = MatchEvents.apply(s, e)
        }

        /** Emits the accepted deal events; a rejection here is a bug in this reducer, not bad input. */
        fun deal(result: ReduceResult) {
            val accepted = result as? ReduceResult.Accepted ?: error("deal layer refused a match step: $result")
            for (e in accepted.events) emit(MatchEvent.InDeal(rev, e))
            check(s.deal == accepted.state) { "deal replay diverged from the deal reducer" }
        }

        fun resisted(claimedBy: Seat?, holders: List<Seat>) {
            val jokers = holders.flatMap { TributeRules.bigJokers(checkNotNull(s.deal).hands.getValue(it)) }.sorted()
            emit(MatchEvent.Resisted(rev, claimedBy, holders, jokers))
            emit(MatchEvent.LeaderResolved(rev, checkNotNull(s.tribute).obligation.receivers.first(), LeadReason.RESISTANCE_FIRST_PLACE))
        }

        /** M8.1, M8.2: after [seat]'s play, report once when it holds 1..threshold cards. */
        fun report(seat: Seat) {
            val threshold = settings.reportingThreshold ?: return
            val count = checkNotNull(s.deal).hands.getValue(seat).size
            if (count in 1..threshold && s.reports.none { it.seat == seat }) emit(MatchEvent.CardCountReported(rev, seat, count))
        }
    }

    private inline fun batch(state: MatchState, block: Batch.() -> Unit): MatchReduceResult {
        val b = Batch(state)
        b.block()
        return MatchReduceResult.Accepted(b.s, b.events.toList())
    }

    private fun hand(state: MatchState, seat: Seat): Set<String> = checkNotNull(state.deal).hands.getValue(seat)

    private fun reject(r: MatchRejection) = MatchReduceResult.Rejected(r)
}
