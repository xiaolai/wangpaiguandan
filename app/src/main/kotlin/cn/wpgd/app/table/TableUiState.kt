package cn.wpgd.app.table

import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.match.MatchMemberView
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchPublicView
import cn.wpgd.app.core.match.ResistanceMode
import cn.wpgd.app.core.match.Team
import cn.wpgd.app.core.match.TributeKind
import cn.wpgd.app.core.match.TributeView
import cn.wpgd.app.core.match.team
import cn.wpgd.app.core.rules.Ranks
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.ViewCard
import cn.wpgd.app.data.HandLayout
import cn.wpgd.app.data.TablePreferences
import cn.wpgd.app.owner.ConnectionState
import cn.wpgd.app.owner.OwnerLifecycle
import cn.wpgd.app.owner.OwnerState
import cn.wpgd.app.owner.PendingJoiner
import cn.wpgd.app.owner.RejectionKey
import cn.wpgd.app.owner.RoomCondition
import cn.wpgd.app.owner.TableKind
import cn.wpgd.app.owner.TableSnapshot
import cn.wpgd.app.session.wire.GrantStatus
import cn.wpgd.app.session.wire.Occupant

enum class TableScreen { NO_TABLE, STARTING, TABLE, DEAL_RESULT, MATCH_OVER, FAILED, CLOSED }

/** One card of the member's own hand as shown. */
data class CardUi(
    val id: String,
    val suit: Suit,
    val rank: Int,
    /** A heart level card (逢人配): shown with the 配 badge, never by color alone. */
    val wildcard: Boolean,
    val selected: Boolean,
    /** False when the card cannot take part in the current step (for example not a legal tribute card). */
    val eligible: Boolean,
)

data class HandGroupUi(val key: String, val kind: GroupKind, val cards: List<CardUi>)

/** The member's own hand in layout A: rows of columns. */
data class HandUi(
    val rows: List<List<HandGroupUi>>,
    val selectedIds: List<String>,
    val sort: HandSort,
    val layout: HandLayout,
) {
    val cardCount: Int get() = rows.sumOf { r -> r.sumOf { it.cards.size } }

    companion object {
        val EMPTY = HandUi(emptyList(), emptyList(), HandSort.RANK, HandLayout.A)
    }
}

/**
 * One seat around the table. Deliberately no hand size: another seat's card count is hidden
 * unless the profile made it public, and then only [publicCount] (a threshold report or an
 * answer, match-v1 section 8) carries it (GDN-079).
 */
data class SeatUi(
    val seat: Seat,
    val relation: SeatRelation,
    val team: Team,
    val acting: Boolean,
    val lastAction: SeatLastAction,
    val finishPlace: Int?,
    val publicCount: Int?,
    /** WI-09 rooms: the seat's player name and who holds the seat now (T6 away, T7 AI stands in). */
    val nickname: String? = null,
    val presence: Occupant? = null,
    /** Whole seconds of grace left while the seat's player is away (T6). */
    val graceSeconds: Int? = null,
)

/** T9: a watcher asks to see this player's hand. */
data class FollowRequestUi(val grantId: String, val observerName: String)

/** T10: who watches the table, and whether they currently see this player's hand. */
data class WatcherUi(val name: String, val seesYourHand: Boolean, val grantId: String?)

/** H1 (host): a seat whose player is away, with the grace left and whether an AI may stand in now. */
data class AbsentSeatUi(val seat: Seat, val nickname: String?, val graceSeconds: Int?, val waitingForPlayer: Boolean)

/**
 * One card handed over in the tribute step; every field is public table information (M4–M6). [to] is
 * null while a double tribute is not yet routed.
 */
data class TransferUi(val from: Seat, val to: Seat?, val card: ViewCard)

/**
 * The deal's tribute as shown (T4, T5, D6): who owes, what was given and returned in public, and a
 * resistance (抗贡) if one happened, with the jokers it showed.
 */
data class TributeUi(
    val kind: TributeKind,
    val givers: List<Seat>,
    val receivers: List<Seat>,
    val given: List<TransferUi>,
    val returned: List<TransferUi>,
    val resistedBy: List<Seat>?,
    val resistedJokers: List<ViewCard>,
)

data class LevelUi(val dealLevel: Int, val ownLevel: Int, val opponentLevel: Int, val ownAFailures: Int, val opponentAFailures: Int)

/** Which controls are usable now. Each is false while a command is pending or controls are stale. */
data class ActionsUi(
    val play: Boolean = false,
    val pass: Boolean = false,
    val giveTribute: Boolean = false,
    val returnTribute: Boolean = false,
    val resist: Boolean = false,
    val continueToNextDeal: Boolean = false,
) {
    companion object {
        val NONE = ActionsUi()
    }
}

/**
 * Everything the table screen shows, derived from the member's own projection, the owner's local
 * status, this screen's selection and the preferences. Immutable.
 */
data class TableUiState(
    val screen: TableScreen,
    val seat: Seat?,
    val phase: MatchPhase?,
    val dealNumber: Int,
    val level: LevelUi?,
    val hand: HandUi,
    val seats: List<SeatUi>,
    val trick: TrickDisplay,
    val turn: TurnClarity,
    val preview: PlayPreview,
    val actions: ActionsUi,
    val pending: Boolean,
    /** Why the last command was refused, as a message key; the selection is kept (GDN-050). */
    val rejection: RejectionKey?,
    val connection: ConnectionState?,
    val room: RoomCondition?,
    /** False while the connection is not live, the room is not active or a command is pending (GDN-088). */
    val controlsEnabled: Boolean,
    val result: ResultExplanation?,
    val preferences: TablePreferences,
    val motion: MotionMode,
    val keepScreenOn: Boolean,
    /** This deal's tribute, while the deal has one. */
    val tribute: TributeUi? = null,
    /** The host may pause now (solo: always the host). */
    val canPause: Boolean = false,
    /** The host may resume a paused table. */
    val canResume: Boolean = false,
    /** The link is stale or lost and a rejoin may be started (T8). */
    val canReconnect: Boolean = false,
    /** WI-09: where the table runs. */
    val kind: TableKind = TableKind.SOLO,
    /** T9: watchers asking to see this hand; offered only when it is not this player's turn (G3). */
    val followRequests: List<FollowRequestUi> = emptyList(),
    /** T10: the watchers of this table. */
    val watchers: List<WatcherUi> = emptyList(),
    /** H1 (host): seats whose players are away. */
    val absentSeats: List<AbsentSeatUi> = emptyList(),
    /** H1 (host): an AI may stand in now for an away player. */
    val canStandIn: Boolean = false,
    /** GDN-017 (host): the match is over and a rematch may start. */
    val canRematch: Boolean = false,
    /** H2: a joined player's host is unreachable; automatic rejoin attempts so far. */
    val hostLostAttempts: Int? = null,
    /** B8 at the table (host): watchers and replacements may ask to join mid-match. */
    val pendingJoiners: List<PendingJoiner> = emptyList(),
) {
    /** Group and Clear act on the selection, which is kept as it is while controls are disabled. */
    val selectionToolsEnabled: Boolean get() = controlsEnabled && hand.selectedIds.isNotEmpty()
}

/** This screen's own state, kept by [TableViewModel] (the table itself lives in the owner). */
data class LocalTableState(
    val selection: HandSelection = HandSelection(),
    val manualGroups: List<List<String>> = emptyList(),
    val chosenOption: String? = null,
    val tableVisible: Boolean = true,
    /**
     * The deal the selection and groups belong to; both reset when a new deal starts. Deal numbers
     * restart in every match, so a deal is (matchNumber, dealNumber) (round 3 #19).
     */
    val matchNumber: Int = -1,
    val dealNumber: Int = -1,
) {
    fun belongsTo(match: MatchPublicView): Boolean = matchNumber == match.matchNumber && dealNumber == match.dealNumber
}

/** What the tribute step asks of the viewer now, and which hand cards it allows. */
private class TributeDuty(
    val owesTribute: Boolean,
    val owesReturn: Boolean,
    val tributeCards: Set<String>,
    val returnCards: Set<String>,
    val resistOpen: Boolean,
) {
    fun eligible(id: String): Boolean = when {
        owesTribute -> id in tributeCards
        owesReturn -> id in returnCards
        else -> true
    }

    companion object {
        fun of(match: MatchPublicView, me: Seat, hand: List<ViewCard>, level: Int, rules: TableRules): TributeDuty {
            val tribute = match.tribute
            val owesTribute = match.phase == MatchPhase.TRIBUTE && tribute != null && me in tribute.givers &&
                tribute.given.none { it.from == me } && tribute.resistedBy == null
            val owesReturn = match.phase == MatchPhase.RETURN && tribute != null && tribute.given.any { it.to == me } && me !in tribute.returnsMade
            // Offered to a giver whose own jokers may resist: both in one hand, or (when the profile lets
            // partners split them, double tribute) one here and possibly the other with the partner. The
            // host decides from both hands (TributeRules.resistanceHolders).
            val ownBigJokers = hand.count { it.rank == Ranks.BIG_JOKER }
            val mayHoldResistance = ownBigJokers >= 2 ||
                (ownBigJokers >= 1 && tribute?.givers?.size == 2 && rules.matchSettings.resistanceSplitBetweenPartners)
            val resistOpen = owesTribute && tribute.given.isEmpty() && rules.matchSettings.resistanceMode == ResistanceMode.CLAIMED && mayHoldResistance
            return TributeDuty(
                owesTribute = owesTribute,
                owesReturn = owesReturn,
                tributeCards = if (owesTribute) rules.tributeCards(hand, level) else emptySet(),
                returnCards = if (owesReturn) rules.returnCards(hand, level) else emptySet(),
                resistOpen = resistOpen,
            )
        }
    }
}

/** Pure mapping from sources to [TableUiState]. */
class TableUiMapper(private val rules: TableRules) {
    fun map(owner: OwnerState, local: LocalTableState, prefs: TablePreferences): TableUiState {
        val t = owner.table
        val view = t?.view
        val motion = if (prefs.reducedMotion) MotionMode.REDUCED else MotionMode.FULL
        val screen = when {
            owner.lifecycle == OwnerLifecycle.SHUT_DOWN -> TableScreen.CLOSED
            owner.lifecycle == OwnerLifecycle.FAILED -> TableScreen.FAILED
            t == null -> if (owner.lifecycle == OwnerLifecycle.RUNNING) TableScreen.STARTING else TableScreen.NO_TABLE
            view == null -> TableScreen.STARTING
            view.match.phase == MatchPhase.OVER -> TableScreen.MATCH_OVER
            view.match.phase == MatchPhase.AWAITING_DEAL && view.match.lastResult != null -> TableScreen.DEAL_RESULT
            else -> TableScreen.TABLE
        }
        val running = owner.lifecycle == OwnerLifecycle.RUNNING
        val canPause = running && t?.hosting == true && t.room == RoomCondition.ACTIVE
        val canResume = running && t?.hosting == true && t.room == RoomCondition.PAUSED
        val canReconnect = running && (t?.connection == ConnectionState.STALE || t?.connection == ConnectionState.DISCONNECTED)
        if (t == null || view == null) {
            return TableUiState(
                screen, t?.seat, null, 0, null, HandUi.EMPTY.copy(sort = prefs.handSort, layout = prefs.handLayout), emptyList(), TrickDisplay.EMPTY,
                TurnClarity.NONE, PlayPreview.Empty, ActionsUi.NONE, t?.pending != null, t?.lastRejection?.key, t?.connection, t?.room,
                false, null, prefs, motion, false,
                canPause = canPause, canResume = canResume, canReconnect = canReconnect,
            )
        }
        val me = view.seat
        val match = view.match
        val deal = view.deal
        val level = deal?.table?.level ?: match.dealLevel
        val sameDeal = local.belongsTo(match)
        val hand = deal?.hand.orEmpty()
        val handIds = hand.map { it.id }.toSet()
        val selected = if (sameDeal) local.selection.prunedTo(handIds).ids else emptySet()
        val groups = if (sameDeal) local.manualGroups else emptyList()
        val pending = t.pending != null
        val controls = owner.lifecycle == OwnerLifecycle.RUNNING && t.connection == ConnectionState.CONNECTED &&
            t.room == RoomCondition.ACTIVE && !pending
        val turn = TurnClarity.of(view)
        val selectedCards = hand.filter { it.id in selected }

        val tribute = match.tribute
        val duty = TributeDuty.of(match, me, hand, level, rules)

        val playing = match.phase == MatchPhase.PLAYING && deal?.yourTurn == true
        val preview = if (playing) {
            PlayPreview.of(rules, selectedCards, level, rules.topCombination(deal.table.trick, level), if (sameDeal) local.chosenOption else null)
        } else {
            PlayPreview.Empty
        }
        val single = selected.singleOrNull()
        val actions = if (!controls) ActionsUi.NONE else ActionsUi(
            play = playing && (preview as? PlayPreview.Options)?.payload != null,
            pass = playing && deal.mayPass,
            giveTribute = duty.owesTribute && single != null && single in duty.tributeCards,
            returnTribute = duty.owesReturn && single != null && single in duty.returnCards,
            resist = duty.resistOpen,
            continueToNextDeal = t.hosting && match.phase == MatchPhase.AWAITING_DEAL && match.dealNumber > 0,
        )

        val arranged = HandArrangement.arrange(hand, level, prefs.handSort, groups)
        val groupUi = arranged.map { g ->
            HandGroupUi(g.key, g.kind, g.cards.map { CardUi(it.id, it.suit, it.rank, HandArrangement.isWildcard(it, level), it.id in selected, duty.eligible(it.id)) })
        }
        val handUi = HandUi(HandLayoutA.rows(groupUi), hand.map { it.id }.filter { it in selected }, prefs.handSort, prefs.handLayout)

        val trick = TrickDisplay.of(view)
        val seats = seats(t, view, turn, trick)
        val own = match.teams.first { it.team == me.team }
        val other = match.teams.first { it.team != me.team }
        val result = match.lastResult?.takeIf { match.phase == MatchPhase.AWAITING_DEAL || match.phase == MatchPhase.OVER }
            ?.let { ResultExplanation.of(it, Viewer.Player(me), rules.matchSettings.maxAFailures) }
        val wake = WakeLockPolicy.shouldHold(WakeInputs(seated = t.seat != null, phase = match.phase, room = t.room, tableVisible = local.tableVisible))
        return TableUiState(
            screen = screen,
            seat = me,
            phase = match.phase,
            dealNumber = match.dealNumber,
            level = LevelUi(level, own.level, other.level, own.aFailures, other.aFailures),
            hand = handUi,
            seats = seats,
            trick = trick,
            turn = turn,
            preview = preview,
            actions = actions,
            pending = pending,
            rejection = t.lastRejection?.key,
            connection = t.connection,
            room = t.room,
            controlsEnabled = controls,
            result = result,
            preferences = prefs,
            motion = motion,
            keepScreenOn = wake,
            tribute = tribute?.let(::tributeUi),
            canPause = canPause,
            canResume = canResume,
            canReconnect = canReconnect,
            kind = t.kind,
            followRequests = if (turn.youAct) emptyList() else t.grants.filter { it.status == GrantStatus.PENDING && it.seat == me }.map { FollowRequestUi(it.grantId, it.observerName) },
            watchers = watchers(t, me),
            absentSeats = absentSeats(t),
            canStandIn = t.hostSide?.canStandIn == true && running,
            canRematch = t.hostSide?.canRematch == true && running,
            hostLostAttempts = t.join?.takeIf { it.phase == cn.wpgd.app.owner.JoinPhase.HOST_LOST }?.reconnectAttempts,
            pendingJoiners = t.hostSide?.pending.orEmpty(),
        )
    }

    /** The four seats from the viewer round the table (self, 下家, 对家, 上家). */
    private fun seats(t: TableSnapshot, view: MatchMemberView, turn: TurnClarity, trick: TrickDisplay): List<SeatUi> {
        val me = view.seat
        val finish = view.deal?.table?.finishOrder.orEmpty()
        val counts = publicCounts(view.match)
        val roster = t.roomView?.seats?.associateBy { it.seat }.orEmpty()
        return listOf(me, me.next, me.partner, me.partner.next).map { s ->
            val r = roster[s]
            SeatUi(
                seat = s,
                relation = SeatRelation.of(me, s),
                team = s.team,
                acting = turn.actor == s || s in turn.waitingFor,
                lastAction = trick.lastActions.getValue(s),
                finishPlace = finish.indexOf(s).takeIf { it >= 0 }?.plus(1),
                publicCount = if (s == me) null else counts[s],
                nickname = r?.nickname,
                presence = r?.occupant,
                graceSeconds = r?.graceRemainingMs?.let { ((it + 999) / 1_000).toInt() },
            )
        }
    }

    private fun tributeUi(tv: TributeView) = TributeUi(
        kind = tv.kind,
        givers = tv.givers,
        receivers = tv.receivers,
        given = tv.given.map { TransferUi(it.from, it.to, it.card) },
        returned = tv.returnsRevealed.map { TransferUi(it.from, it.to, it.card) },
        resistedBy = tv.resistedBy,
        resistedJokers = tv.resistedJokers,
    )

    /** T10: every watcher by name, and which of them sees this player's hand now (GDN-067). */
    private fun watchers(t: TableSnapshot, me: Seat): List<WatcherUi> {
        val active = t.grants.filter { it.status == GrantStatus.ACTIVE && it.seat == me }
        val names = t.roomView?.observers.orEmpty()
        // The roster names watchers only by nickname, and nicknames may repeat: each roster entry takes
        // at most one grant, and each grant is shown once, so every grant keeps its own revoke control.
        val unmatched = active.toMutableList()
        val fromRoster = names.map { n ->
            val g = unmatched.firstOrNull { it.observerName == n }?.also { unmatched.remove(it) }
            WatcherUi(n, g != null, g?.grantId)
        }
        // A grant whose watcher is not (yet) in the roster still shows: never hide who sees the hand.
        val extra = unmatched.map { WatcherUi(it.observerName, true, it.grantId) }
        return fromRoster + extra
    }

    /** H1: seats whose players are away; only the host acts on them. */
    private fun absentSeats(t: TableSnapshot): List<AbsentSeatUi> {
        if (t.kind != TableKind.HOSTED) return emptyList()
        return t.roomView?.seats.orEmpty().filter { it.occupant == Occupant.ABSENT || it.occupant == Occupant.AWAITING_PLAYER }.map {
            AbsentSeatUi(it.seat, it.nickname, it.graceRemainingMs?.let { ms -> ((ms + 999) / 1_000).toInt() }, it.occupant == Occupant.AWAITING_PLAYER)
        }
    }

}

/**
 * Card counts the profile made public this deal: per seat, the newest threshold report or answer.
 * Counts only fall during a deal's play, so the newest is the smallest; reports and answers are
 * kept in separate lists, so their order between the two is not known. Shared by the player's and
 * the watcher's screens, so both show the same count.
 */
fun publicCounts(match: MatchPublicView): Map<Seat, Int> {
    val out = HashMap<Seat, Int>()
    match.reports.forEach { out.merge(it.seat, it.count, ::minOf) }
    match.answers.forEach { out.merge(it.seat, it.count, ::minOf) }
    return out
}
