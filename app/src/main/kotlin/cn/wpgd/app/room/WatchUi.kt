package cn.wpgd.app.room

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.Team
import cn.wpgd.app.core.match.team
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.owner.ConnectionState
import cn.wpgd.app.owner.FollowedView
import cn.wpgd.app.owner.OwnerLifecycle
import cn.wpgd.app.owner.OwnerState
import cn.wpgd.app.owner.RoomCondition
import cn.wpgd.app.owner.RoomInfo
import cn.wpgd.app.owner.SessionAction
import cn.wpgd.app.owner.SessionNotice
import cn.wpgd.app.session.wire.GrantStatus
import cn.wpgd.app.session.wire.GrantView
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.table.CardUi
import cn.wpgd.app.table.HandArrangement
import cn.wpgd.app.table.HandSort
import cn.wpgd.app.table.ResultExplanation
import cn.wpgd.app.table.SeatLastAction
import cn.wpgd.app.table.TrickDisplay
import cn.wpgd.app.table.TurnClarity
import cn.wpgd.app.table.Viewer

/** One seat as a watcher sees it: public facts only (no hand size unless the profile made it public). */
data class WatchSeatUi(
    val seat: Seat,
    val team: Team,
    val nickname: String?,
    val occupant: Occupant?,
    val acting: Boolean,
    val lastAction: SeatLastAction,
    val finishPlace: Int?,
    val publicCount: Int?,
    /** A human sits here, so the watcher may ask to see this hand (G2). */
    val canAsk: Boolean,
)

/** The one hand a watcher may see, while its player's consent is active (G4). Read-only. */
data class FollowUi(val grantId: String, val seat: Seat, val nickname: String?, val hand: List<CardUi>)

/** What happened to the watcher's last request (G5, G6, refusals). */
sealed interface WatchNotice {
    /** The player said no. */
    data class Denied(val seat: Seat, val nickname: String?) : WatchNotice

    /** G5: the player took the permission back; the private view is gone. */
    data class Revoked(val seat: Seat, val nickname: String?) : WatchNotice

    /** G6: the followed player left the seat; consent never passes to whoever plays it next. */
    data class PlayerLeft(val seat: Seat, val nickname: String?) : WatchNotice

    /** The host refused the request (one view per deal, private follow off, nobody there). */
    data class Refused(val code: RejectionCode) : WatchNotice
}

/**
 * The watcher's screen (G1–G7), derived only from the public spectator projection and, while a grant
 * is active, the one followed seat's projection. Nothing else exists to leak (P3, P4).
 */
data class WatchUiState(
    val info: RoomInfo?,
    val phase: MatchPhase?,
    val dealNumber: Int,
    val dealLevel: Int?,
    val levels: Map<Team, Int>,
    val seats: List<WatchSeatUi>,
    val trick: TrickDisplay,
    val turn: TurnClarity,
    val follow: FollowUi?,
    /** G3: a request waits for this seat's player. */
    val waitingFor: Seat?,
    val waitingGrantId: String?,
    val notice: WatchNotice?,
    /** Private follow is allowed in this room and no request or follow is live. */
    val canRequest: Boolean,
    /** Competitive room: nobody's hand can be watched. */
    val followDisabled: Boolean,
    val observers: List<String>,
    val result: ResultExplanation?,
    val connection: ConnectionState?,
    val room: RoomCondition?,
)

object WatchUi {
    /**
     * [dismissedThrough] is the newest grant revision whose outcome the watcher already dismissed;
     * [maxAFailures] comes from the profile (for the neutral result wording, R4).
     */
    fun of(state: OwnerState, dismissedThrough: Long, maxAFailures: Int?): WatchUiState? {
        val t = state.table ?: return null
        val view = t.spectatorView ?: return null
        val match = view.match
        val table = view.deal?.table
        val room = t.roomView
        val names = room?.seats?.associate { it.seat to it.nickname }.orEmpty()
        val occupants = room?.seats?.associate { it.seat to it.occupant }.orEmpty()
        val privateFollow = room?.settings?.policy?.privateFollow ?: false
        val latest = t.grants.maxByOrNull { it.grantRevision }
        val live = latest?.takeIf { it.status == GrantStatus.PENDING || it.status == GrantStatus.ACTIVE }
        val turn = TurnClarity.of(view)
        val trick = TrickDisplay.of(view)
        val counts = HashMap<Seat, Int>().apply {
            match.reports.forEach { put(it.seat, it.count) }
            match.answers.forEach { put(it.seat, it.count) }
        }
        val finish = table?.finishOrder.orEmpty()
        val level = table?.level ?: match.dealLevel
        val follow = followed(t.follow, latest, level, names)
        val notice = notice(t.notice, latest, dismissedThrough, names, occupants)
        val running = state.lifecycle == OwnerLifecycle.RUNNING && t.connection == ConnectionState.CONNECTED
        return WatchUiState(
            info = t.info,
            phase = match.phase,
            dealNumber = match.dealNumber,
            dealLevel = level.takeIf { match.dealNumber > 0 },
            levels = match.teams.associate { it.team to it.level },
            seats = Seat.entries.map { s ->
                val occ = occupants[s]
                WatchSeatUi(
                    seat = s,
                    team = s.team,
                    nickname = names[s],
                    occupant = occ,
                    acting = turn.actor == s || s in turn.waitingFor,
                    lastAction = trick.lastActions.getValue(s),
                    finishPlace = finish.indexOf(s).takeIf { it >= 0 }?.plus(1),
                    publicCount = counts[s],
                    canAsk = running && privateFollow && live == null && occ == Occupant.HUMAN,
                )
            },
            trick = trick,
            turn = turn,
            follow = follow,
            waitingFor = live?.takeIf { it.status == GrantStatus.PENDING }?.seat,
            waitingGrantId = live?.takeIf { it.status == GrantStatus.PENDING }?.grantId,
            notice = notice,
            canRequest = running && privateFollow && live == null,
            followDisabled = room != null && !privateFollow,
            observers = room?.observers.orEmpty(),
            result = match.lastResult?.takeIf { match.phase == MatchPhase.AWAITING_DEAL || match.phase == MatchPhase.OVER }
                ?.let { ResultExplanation.of(it, Viewer.Spectator, maxAFailures) },
            connection = t.connection,
            room = t.room,
        )
    }

    /** The followed hand: only while the latest grant is ACTIVE and the view carries that very grant. */
    private fun followed(f: FollowedView?, latest: GrantView?, level: Int, names: Map<Seat, String?>): FollowUi? {
        if (f == null || latest == null || latest.status != GrantStatus.ACTIVE || latest.grantId != f.grantId || latest.seat != f.seat) return null
        val hand = f.view.deal?.hand.orEmpty()
        val arranged = HandArrangement.arrange(hand, level, HandSort.RANK, emptyList()).flatMap { it.cards }
        return FollowUi(f.grantId, f.seat, names[f.seat], arranged.map { CardUi(it.id, it.suit, it.rank, HandArrangement.isWildcard(it, level), selected = false, eligible = true) })
    }

    /** The outcome of the watcher's last request, unless already dismissed. */
    private fun notice(
        sessionNotice: SessionNotice?,
        latest: GrantView?,
        dismissedThrough: Long,
        names: Map<Seat, String?>,
        occupants: Map<Seat, Occupant>,
    ): WatchNotice? = when {
        sessionNotice?.action == SessionAction.FOLLOW_REQUEST -> WatchNotice.Refused(sessionNotice.code)
        latest == null || latest.grantRevision <= dismissedThrough -> null
        latest.status == GrantStatus.DENIED -> WatchNotice.Denied(latest.seat, names[latest.seat])
        latest.status == GrantStatus.REVOKED -> {
            val occ = occupants[latest.seat]
            val stillThere = (occ == Occupant.HUMAN || occ == Occupant.ABSENT) && names[latest.seat] != null
            if (stillThere) WatchNotice.Revoked(latest.seat, names[latest.seat]) else WatchNotice.PlayerLeft(latest.seat, names[latest.seat])
        }
        else -> null
    }
}
