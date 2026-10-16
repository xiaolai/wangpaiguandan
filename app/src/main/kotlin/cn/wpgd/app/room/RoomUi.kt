package cn.wpgd.app.room

import cn.wpgd.app.core.match.Team
import cn.wpgd.app.core.match.team
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.owner.ConnectionState
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.owner.OwnerLifecycle
import cn.wpgd.app.owner.OwnerState
import cn.wpgd.app.owner.PendingJoiner
import cn.wpgd.app.owner.RoomInfo
import cn.wpgd.app.owner.SessionAction
import cn.wpgd.app.owner.TableKind
import cn.wpgd.app.session.host.LobbyGate
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.session.wire.RoomSettingsView

/** Which room screen to show for the owner's state (WI-09). Pure. */
enum class RoomScreen {
    /** Not admitted yet, or the connection story is the content (C5–C9, H2, ended). */
    JOIN_STATUS,

    /** Before the first deal of a match: seats, ready, rules, start (B7–B10, D1, D2). */
    LOBBY,

    /** A seated player at the table (the WI-08 table screen with the room parts). */
    TABLE,

    /** A watcher (G1–G7). */
    WATCH,

    /** Starting, failed or shut down: the table screen's own notices. */
    OTHER,
    ;

    companion object {
        fun of(state: OwnerState): RoomScreen {
            val t = state.table ?: return OTHER
            if (state.lifecycle != OwnerLifecycle.RUNNING) return OTHER
            val join = t.join
            if (join != null && join.phase != JoinPhase.ADMITTED && join.phase != JoinPhase.HOST_LOST) return JOIN_STATUS
            if (t.role == Role.OBSERVER) return if (t.spectatorView != null) WATCH else JOIN_STATUS
            val room = t.roomView ?: return if (t.kind == TableKind.SOLO) TABLE else JOIN_STATUS
            // The status and the view travel separately: a dealt view means the table, whatever arrives first.
            val dealt = (t.view?.match?.dealNumber ?: 0) > 0
            return if (room.lobby && !dealt) LOBBY else TABLE
        }
    }
}

/** One seat in the lobby. [isYou] comes from the host's seat assignment, never from a nickname. */
data class LobbySeatUi(
    val seat: Seat,
    val team: Team,
    val occupant: Occupant,
    val nickname: String?,
    val ready: Boolean,
    val isYou: Boolean,
    /** Whole seconds of grace left while the seat's player is away (T6, H4). */
    val graceSeconds: Int?,
)

/**
 * The lobby (B7, B9, D2): every seat with who holds it and whether they are ready, the watchers, the
 * settings, and why the room cannot start yet. The same [LobbyGate] the host enforces decides
 * [startBlockers], so the button and the host never disagree.
 */
data class LobbyUiState(
    val info: RoomInfo,
    val hosting: Boolean,
    val seats: List<LobbySeatUi>,
    val observers: List<String>,
    val settings: RoomSettingsView,
    val settingsRevision: Long,
    val yourSeat: Seat?,
    val youReady: Boolean,
    /** The player may say ready (or not ready) now: seated, connected, in the lobby. */
    val canReady: Boolean,
    /** D2: the settings changed since this player last looked at them; they must look again. */
    val rulesChanged: Boolean,
    val startBlockers: List<LobbyGate.Blocker>,
    /** Host only: every seat is filled and every human ready. */
    val canStart: Boolean,
    /** Host only: who waits for approval (B8). */
    val pending: List<PendingJoiner>,
    /** Host only: nearby devices can no longer find or reach this room; leave and host again. */
    val hostingFailed: Boolean = false,
)

object LobbyUi {
    /**
     * [seenSettingsRevision] is the settings revision this player last looked at (D1), or null if it
     * never opened the rules; a player who has seen older settings than the current ones is told.
     */
    fun of(state: OwnerState, seenSettingsRevision: Long?): LobbyUiState? {
        val t = state.table ?: return null
        val room = t.roomView?.takeIf { it.lobby } ?: return null
        val info = t.info ?: return null
        val hosting = t.kind == TableKind.HOSTED
        val connected = state.lifecycle == OwnerLifecycle.RUNNING && t.connection == ConnectionState.CONNECTED
        val me = room.yourSeat
        val mine = room.seats.firstOrNull { it.seat == me }
        val blockers = if (hosting) t.hostSide?.startBlockers.orEmpty() else LobbyGate.blockers(room.seats)
        val changedNotice = t.notice?.action == SessionAction.READY && t.notice.code == RejectionCode.SETTINGS_CHANGED
        val youReady = mine?.ready == true
        return LobbyUiState(
            info = info,
            hosting = hosting,
            seats = room.seats.map { s ->
                LobbySeatUi(s.seat, s.seat.team, s.occupant, s.nickname, s.ready, s.seat == me, s.graceRemainingMs?.let { ((it + 999) / 1_000).toInt() })
            },
            observers = room.observers,
            settings = room.settings,
            settingsRevision = room.settingsRevision,
            yourSeat = me,
            youReady = youReady,
            canReady = connected && t.role == Role.PLAYER && mine?.occupant == Occupant.HUMAN,
            rulesChanged = !youReady && (changedNotice || (seenSettingsRevision != null && seenSettingsRevision < room.settingsRevision)),
            startBlockers = blockers,
            canStart = hosting && connected && blockers.isEmpty(),
            pending = t.hostSide?.pending.orEmpty(),
            hostingFailed = t.hostSide?.hostingFailed == true,
        )
    }
}

/**
 * What the joiner chooses before asking (C4, GDN-003, GDN-065): play at one of the open seats, or
 * watch. Both are decided before admission, so a watcher never takes a seat.
 */
data class PlayOrWatch(
    val advert: RoomAdvert,
    val compatibility: Compatibility,
    /** Seats offered to play; empty means the table is full (C8): watching is still offered. */
    val playSeats: List<Seat>,
    val canWatch: Boolean,
    /** Seats whose player is away: the joiner may ask the host to take one over (GDN-016). */
    val replaceSeats: List<Seat> = emptyList(),
) {
    companion object {
        /** [spectators] is the build's watch switch ([Features.SPECTATORS]); off hides watching. */
        fun of(advert: RoomAdvert, local: LocalVersion, spectators: Boolean = Features.SPECTATORS): PlayOrWatch {
            val compat = advert.compatibility(local)
            val ok = compat == Compatibility.COMPATIBLE
            return PlayOrWatch(
                advert,
                compat,
                if (ok) Seat.entries.filter { it in advert.openSeats } else emptyList(),
                ok && spectators,
                if (ok) Seat.entries.filter { it in advert.replaceableSeats } else emptyList(),
            )
        }
    }
}

/**
 * Build switches. [SPECTATORS] is the WI-09 rollback: off, nobody is offered watching and every room
 * runs with private follow off, so no hand can be granted; players are unaffected.
 * [BLE_TRANSPORT] is the WI-10 kill switch, set by the Gradle property `wpgd.bleTransport` (default
 * off): off, there is no Bluetooth entry and the manifest declares no Bluetooth permission; Wi-Fi
 * rooms and solo play are unaffected.
 * [TRIBUTE_PROFILE] is the WI-04 rollback: off, no profile is offered for a new table (solo play and
 * hosting are hidden and refused); saved tables restore and rooms are joined under their frozen
 * profile, so the rules never change inside a room.
 */
object Features {
    const val SPECTATORS: Boolean = true
    const val TRIBUTE_PROFILE: Boolean = true
    const val BLE_TRANSPORT: Boolean = cn.wpgd.app.BuildConfig.BLE_TRANSPORT
}
