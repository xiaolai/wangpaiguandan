package cn.wpgd.app.table

import cn.wpgd.app.core.match.MatchMemberView
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchPublicView
import cn.wpgd.app.core.match.MatchSpectatorView
import cn.wpgd.app.core.state.PublicTable
import cn.wpgd.app.core.rules.CombinationType
import cn.wpgd.app.core.state.Phase
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.ViewAssignment
import cn.wpgd.app.core.state.ViewCard

/** Where a seat sits relative to the viewer: the words the UI uses instead of compass names. */
enum class SeatRelation {
    SELF,

    /** 下家: plays right after the viewer. */
    NEXT,
    PARTNER,

    /** 上家: plays right before the viewer. */
    PREVIOUS,
    ;

    companion object {
        fun of(viewer: Seat, seat: Seat): SeatRelation = when (seat) {
            viewer -> SELF
            viewer.next -> NEXT
            viewer.partner -> PARTNER
            else -> PREVIOUS
        }
    }
}

/** One play of the current trick as shown. [isTop] marks the play to beat. */
data class TrickPlayUi(
    val seat: Seat,
    val relation: SeatRelation,
    val cards: List<ViewCard>,
    val declaration: List<ViewAssignment>?,
    val type: CombinationType,
    val isTop: Boolean,
)

/** A seat's last action in the current trick (GDN-078). */
sealed interface SeatLastAction {
    data object None : SeatLastAction
    data class Played(val cards: List<ViewCard>, val type: CombinationType, val declaration: List<ViewAssignment>?) : SeatLastAction
    data object Passed : SeatLastAction

    /** The seat has played out, in [place] (1 = 上游). */
    data class Finished(val place: Int) : SeatLastAction
}

/**
 * The current trick and every seat's last action, derived from the member's projection alone, so a
 * late reconnect (or a recreated screen) shows exactly the same thing (GDN-078).
 */
data class TrickDisplay(val plays: List<TrickPlayUi>, val lastActions: Map<Seat, SeatLastAction>) {
    val top: TrickPlayUi? get() = plays.lastOrNull()

    companion object {
        val EMPTY = TrickDisplay(emptyList(), Seat.entries.associateWith { SeatLastAction.None })

        fun of(view: MatchMemberView): TrickDisplay = of(view.deal?.table, view.seat)

        /** A watcher's trick (WI-09): the same public table, relations seen from [SPECTATOR_VIEWPOINT]. */
        fun of(view: MatchSpectatorView): TrickDisplay = of(view.deal?.table, SPECTATOR_VIEWPOINT)

        private fun of(table: PublicTable?, viewer: Seat): TrickDisplay {
            table ?: return EMPTY
            if (table.phase != Phase.PLAYING && table.phase != Phase.ENDED) return EMPTY
            val plays = table.trick.mapIndexed { i, p ->
                TrickPlayUi(p.seat, SeatRelation.of(viewer, p.seat), p.cards, p.declaration, p.type, i == table.trick.lastIndex)
            }
            val latest = latestActions(table)
            val actions = Seat.entries.associateWith { seat ->
                val place = table.finishOrder.indexOf(seat)
                latest[seat] ?: if (place >= 0) SeatLastAction.Finished(place + 1) else SeatLastAction.None
            }
            return TrickDisplay(plays, actions)
        }

        /**
         * Each seat's latest play or pass in the current trick, replayed from the public trick alone:
         * between two plays, every seat the turn passed over passed, except seats that had played out
         * by then (D22: the turn skips only those). After the latest play, [PublicTable.passedSinceLastPlay].
         */
        private fun latestActions(table: PublicTable): Map<Seat, SeatLastAction> {
            val out = HashMap<Seat, SeatLastAction>()
            fun outBy(seat: Seat, index: Int): Boolean =
                seat in table.finishOrder && table.trick.indexOfLast { it.seat == seat } <= index
            table.trick.forEachIndexed { i, p ->
                out[p.seat] = SeatLastAction.Played(p.cards, p.type, p.declaration)
                val following = table.trick.getOrNull(i + 1)?.seat ?: return@forEachIndexed
                var s = p.seat.next
                while (s != following && s != p.seat) {
                    if (!outBy(s, i)) out[s] = SeatLastAction.Passed
                    s = s.next
                }
            }
            table.passedSinceLastPlay.forEach { out[it] = SeatLastAction.Passed }
            return out
        }
    }
}

/** What the turn line says, as a key; seats are in [TurnClarity]. */
enum class TurnKey(val messageKey: String) {
    NO_DEAL("turn.no_deal"),
    YOU_LEAD("turn.you_lead"),
    YOU_BEAT_OR_PASS("turn.you_beat_or_pass"),
    OTHER_LEADS("turn.other_leads"),
    OTHER_FOLLOWS("turn.other_follows"),
    YOU_GIVE_TRIBUTE("turn.you_give_tribute"),
    YOU_RETURN_TRIBUTE("turn.you_return_tribute"),
    WAITING_FOR_TRIBUTE("turn.waiting_for_tribute"),
    WAITING_FOR_RETURN("turn.waiting_for_return"),
    BETWEEN_STEPS("turn.between_steps"),
    DEAL_OVER("turn.deal_over"),
    MATCH_OVER("turn.match_over"),
}

/**
 * Turn clarity (GDN-077), as data for both the visible text and the screen-reader text:
 * [trickOwner] played what must be beaten (null when leading), [actor] acts now, [waitingFor] lists
 * the seats that still owe a tribute or a return.
 */
data class TurnClarity(
    val key: TurnKey,
    val actor: Seat?,
    val actorRelation: SeatRelation?,
    val trickOwner: Seat?,
    val trickOwnerRelation: SeatRelation?,
    val topType: CombinationType?,
    val youAct: Boolean,
    val waitingFor: List<Seat>,
) {
    companion object {
        val NONE = TurnClarity(TurnKey.NO_DEAL, null, null, null, null, null, false, emptyList())

        fun of(view: MatchMemberView): TurnClarity = of(view.match, view.deal?.table, view.seat)

        /** A watcher never acts; seats are named, not related to a viewer (WI-09). */
        fun of(view: MatchSpectatorView): TurnClarity = of(view.match, view.deal?.table, null)

        private fun of(match: MatchPublicView, dealTable: PublicTable?, me: Seat?): TurnClarity {
            fun rel(s: Seat?) = if (me == null) null else s?.let { SeatRelation.of(me, it) }
            return when (match.phase) {
                MatchPhase.PLAYING -> {
                    val table = dealTable ?: return NONE
                    val top = table.trick.lastOrNull()
                    val actor = table.turn
                    val you = me != null && actor == me
                    val key = when {
                        you && top == null -> TurnKey.YOU_LEAD
                        you -> TurnKey.YOU_BEAT_OR_PASS
                        top == null -> TurnKey.OTHER_LEADS
                        else -> TurnKey.OTHER_FOLLOWS
                    }
                    TurnClarity(key, actor, rel(actor), top?.seat, rel(top?.seat), top?.type, you, emptyList())
                }
                MatchPhase.TRIBUTE -> {
                    val t = match.tribute ?: return NONE
                    val owing = t.givers.filter { g -> t.given.none { it.from == g } }
                    val you = me in owing && t.resistedBy == null
                    TurnClarity(if (you) TurnKey.YOU_GIVE_TRIBUTE else TurnKey.WAITING_FOR_TRIBUTE, null, null, null, null, null, you, owing)
                }
                MatchPhase.RETURN -> {
                    val t = match.tribute ?: return NONE
                    val owing = t.receivers.filter { it !in t.returnsMade }
                    val you = me in owing
                    TurnClarity(if (you) TurnKey.YOU_RETURN_TRIBUTE else TurnKey.WAITING_FOR_RETURN, null, null, null, null, null, you, owing)
                }
                MatchPhase.DEALT, MatchPhase.READY -> NONE.copy(key = TurnKey.BETWEEN_STEPS)
                MatchPhase.DEAL_ENDED -> NONE.copy(key = TurnKey.DEAL_OVER)
                MatchPhase.AWAITING_DEAL -> NONE.copy(key = if (match.lastResult != null) TurnKey.DEAL_OVER else TurnKey.NO_DEAL)
                MatchPhase.OVER -> NONE.copy(key = TurnKey.MATCH_OVER)
            }
        }
    }
}

/** The seat a watcher's table is drawn from (relations only place the panels; names are compass names). */
val SPECTATOR_VIEWPOINT: Seat = Seat.SOUTH
