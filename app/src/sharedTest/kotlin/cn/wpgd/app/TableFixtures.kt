package cn.wpgd.app

import cn.wpgd.app.core.match.CountReport
import cn.wpgd.app.core.match.LeadReason
import cn.wpgd.app.core.match.MatchMemberView
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchPublicView
import cn.wpgd.app.core.match.Team
import cn.wpgd.app.core.match.TeamView
import cn.wpgd.app.core.match.TributeView
import cn.wpgd.app.core.state.MemberView
import cn.wpgd.app.core.state.Phase
import cn.wpgd.app.core.state.PublicPlay
import cn.wpgd.app.core.state.PublicTable
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.StandardDeck
import cn.wpgd.app.core.state.ViewCard
import cn.wpgd.app.owner.CommandRejection
import cn.wpgd.app.owner.ConnectionState
import cn.wpgd.app.owner.OwnerLifecycle
import cn.wpgd.app.owner.OwnerState
import cn.wpgd.app.owner.PendingCommand
import cn.wpgd.app.owner.RoomCondition
import cn.wpgd.app.owner.SubmitRefusal
import cn.wpgd.app.owner.SubmitResult
import cn.wpgd.app.owner.TableSession
import cn.wpgd.app.owner.TableSnapshot
import cn.wpgd.app.session.wire.CommandBody
import cn.wpgd.app.table.RuleProfiles
import cn.wpgd.app.table.TableCue
import cn.wpgd.app.table.TableRules
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections

/**
 * A [TableSession] whose state the test sets directly. Records every command the screen tried to
 * send ([attempts]) and every one it accepted ([submitted]); at most one is pending, like the owner.
 * Shared by the JVM tests (`app/src/test`) and the on-device tests (`app/src/androidTest`).
 */
open class RecordingSession(view: MatchMemberView?, override val rules: TableRules) : TableSession {
    val submitted: MutableList<CommandBody> = Collections.synchronizedList(ArrayList())
    val attempts: MutableList<CommandBody> = Collections.synchronizedList(ArrayList())
    @Volatile var reconnects = 0
        private set
    private var nextTicket = 1L

    val mutable = MutableStateFlow(
        OwnerState(
            OwnerLifecycle.RUNNING,
            TableSnapshot("solo-test", view?.seat, 1, view?.deal?.table?.revision ?: 0, view, ConnectionState.CONNECTED, RoomCondition.ACTIVE, null, null, true),
        ),
    )
    override val state: StateFlow<OwnerState> = mutable
    val cueFlow = MutableSharedFlow<TableCue>(extraBufferCapacity = 64)
    override val cues: SharedFlow<TableCue> = cueFlow

    @Synchronized
    override fun submit(body: CommandBody): SubmitResult {
        attempts += body
        val t = mutable.value.table!!
        if (t.pending != null) return SubmitResult.Refused(SubmitRefusal.ALREADY_PENDING)
        submitted += body
        val p = PendingCommand(nextTicket++, body, t.revision)
        mutable.value = mutable.value.copy(table = t.copy(pending = p))
        return SubmitResult.Sent(p.ticket)
    }

    override fun continueToNextDeal(): Boolean = false

    override fun dismissRejection() = update { it.copy(lastRejection = null) }

    override fun reconnect() {
        reconnects++
        update { it.copy(connection = ConnectionState.CONNECTED) }
    }

    override fun pause() = update { it.copy(room = RoomCondition.PAUSED) }

    override fun resume() = update { it.copy(room = RoomCondition.ACTIVE) }

    fun update(change: (TableSnapshot) -> TableSnapshot) {
        mutable.value = mutable.value.copy(table = change(mutable.value.table!!))
    }

    fun setView(view: MatchMemberView) = update { it.copy(view = view, revision = view.deal?.table?.revision ?: it.revision) }

    fun reject(r: CommandRejection) = update { it.copy(pending = null, lastRejection = r) }

    /** The host answered the pending command with a receipt. */
    fun accept() = update { it.copy(pending = null) }
}

/** Builders of member projections for screen tests. Every field comes from the public data classes. */
object TableFixtures {
    fun card(id: String): ViewCard = checkNotNull(StandardDeck[id]) { "no card $id" }.let { ViewCard(it.id, it.suit, it.rank) }

    /** A playing-phase (or other [phase]) view for [seat] at deal level [level]. */
    fun playingView(
        profiles: RuleProfiles,
        seat: Seat = Seat.SOUTH,
        hand: List<String>,
        turn: Seat = seat,
        trick: List<PublicPlay> = emptyList(),
        passed: List<Seat> = emptyList(),
        finish: List<Seat> = emptyList(),
        level: Int = 2,
        revision: Long = 10,
        phase: MatchPhase = MatchPhase.PLAYING,
        tribute: TributeView? = null,
        reports: List<CountReport> = emptyList(),
    ): MatchMemberView {
        val table = PublicTable(
            phase = if (phase == MatchPhase.PLAYING) Phase.PLAYING else Phase.DEALT,
            level = level,
            profileHash = profiles.base.hash,
            revision = revision,
            leader = trick.firstOrNull()?.seat ?: turn,
            turn = if (phase == MatchPhase.PLAYING) turn else null,
            trick = trick,
            passedSinceLastPlay = passed,
            finishOrder = finish,
        )
        val yourTurn = phase == MatchPhase.PLAYING && turn == seat
        val deal = MemberView(1, seat, table, hand.map(::card), yourTurn, yourTurn && trick.isNotEmpty(), yourTurn)
        val match = MatchPublicView(
            phase = phase,
            matchNumber = 1,
            baseProfileHash = profiles.base.hash,
            matchProfileHash = profiles.match.hash,
            teams = Team.entries.map { TeamView(it, level, 0) },
            dealNumber = 1,
            dealLevel = level,
            leader = table.leader,
            leadReason = LeadReason.CUT_CARD,
            cutCard = null,
            tribute = tribute,
            reports = reports,
            answers = emptyList(),
            lastResult = null,
            winner = null,
        )
        return MatchMemberView(1, seat, match, deal, null)
    }

    /**
     * A full 27-card hand at level 2 with every rank 2–A, both jokers and both heart-2 wildcards:
     * 15 columns in layout A, the widest hand the table has to fit (GDN-073).
     */
    val FULL_HAND: List<String> = listOf(
        "0H2", "1H2", "0S2", "0S3", "0C3", "0D4", "0S5", "0C5", "0S6", "0H6", "0S7", "0D7", "0C8",
        "0S9", "0D9", "0S10", "1S10", "0CJ", "0SQ", "1DQ", "0SK", "0HK", "0SA", "0DA", "1CA", "0LJ", "0BJ",
    )

    /**
     * A run of six clubs (3–8) for drag selection. Every run card has a neighbour of the same rank
     * above it in its column, and the run is followed by 9–A, so layout A (12 columns, two rows of
     * six) puts exactly 3–8 in the first row with the clubs at the bottom of each column.
     */
    val RUN_HAND: List<String> = listOf(
        "0S3", "0C3", "0H4", "0C4", "0S5", "0C5", "0H6", "0C6", "0S7", "0C7", "0S8", "0C8",
        "0S9", "0S10", "0SJ", "0SQ", "0SK", "0SA",
    )
    val RUN: List<String> = listOf("0C3", "0C4", "0C5", "0C6", "0C7", "0C8")
}
