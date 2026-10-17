package cn.wpgd.app.owner

import cn.wpgd.app.core.match.MatchMemberView
import cn.wpgd.app.core.match.MatchSpectatorView
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.room.RoomMedium
import cn.wpgd.app.session.host.LobbyGate
import cn.wpgd.app.session.wire.AdmissionDenial
import cn.wpgd.app.session.wire.CloseReason
import cn.wpgd.app.session.wire.CommandBody
import cn.wpgd.app.session.wire.GrantView
import cn.wpgd.app.session.wire.HelloRejectReason
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.session.wire.RoomView

/** Whether the owner can run a table. */
enum class OwnerLifecycle {
    /** No table yet. */
    IDLE,

    /** A table runs (or is starting). */
    RUNNING,

    /** A session task failed (fail loud): the table stops accepting input; see [OwnerState.failure]. */
    FAILED,

    /** [SessionOwner.shutdown] ran: every job is cancelled; nothing restarts. */
    SHUT_DOWN,
}

/** This device's link to its table, as the member sees it (GDN-088). */
enum class ConnectionState {
    /** Handshake, admission or rejoin in progress. */
    CONNECTING,

    /** Admitted and hearing from the host. */
    CONNECTED,

    /** The host has been silent past the liveness limit: what is shown may be out of date. */
    STALE,

    /** The connection is gone; [SessionOwner.reconnect] rejoins with the stored ticket (a proof keyed with the capability, never the capability itself; G2). */
    DISCONNECTED,
}

/** The room's state (audit A22): only [ACTIVE] accepts game commands. */
enum class RoomCondition { ACTIVE, PAUSED, SUSPENDED, ENDED, QUARANTINED, UNKNOWN }

/**
 * A command sent and not yet answered. Its selection stays on screen until the answer (GDN-050).
 * [atEpoch] and [atRevision] are the table it was chosen against; it is never sent against another.
 */
data class PendingCommand(val ticket: Long, val body: CommandBody, val atRevision: Long, val atEpoch: Long? = null) {
    /** True when the table is no longer the one this command was chosen against: it must not be sent. */
    internal fun movedOn(revision: Long, epoch: Long?): Boolean = revision != atRevision || (atEpoch != null && epoch != atEpoch)
}

/**
 * Why the last command was not applied, as a typed key for the UI to translate (never translated
 * text). [code] is the host's code; null when the command never reached the host.
 */
data class CommandRejection(val ticket: Long, val code: RejectionCode?, val key: RejectionKey)

/** Everything the table UI may derive from: the member's own projection and its local status. */
data class TableSnapshot(
    val tableId: String,
    /** The seat the host gave this member; null until admitted. */
    val seat: Seat?,
    val epoch: Long?,
    /** Revision of [view]; -1 before the first snapshot. */
    val revision: Long,
    /** This member's own projection (WI-03/WI-04): public table plus its own hand. Nothing else. */
    val view: MatchMemberView?,
    val connection: ConnectionState,
    val room: RoomCondition,
    val pending: PendingCommand?,
    val lastRejection: CommandRejection?,
    /** True when this device hosts the table (solo): it may pause, resume and start the next deal. */
    val hosting: Boolean,
    /** WI-09: solo, a room this device hosts, or a room joined over the network. */
    val kind: TableKind = TableKind.SOLO,
    /** WI-09: player or watcher. A watcher has no [view]; it has [spectatorView] and maybe [follow]. */
    val role: Role = Role.PLAYER,
    /** Watcher: the public table only (P3). */
    val spectatorView: MatchSpectatorView? = null,
    /** Watcher: the one seat it may see now, while that player's consent is active (G4). */
    val follow: FollowedView? = null,
    /** A player's: the grants on its seat (who asks, who watches); a watcher's: its own. */
    val grants: List<GrantView> = emptyList(),
    /** WI-09: the roster and room settings from the host (null in solo and before admission). */
    val roomView: RoomView? = null,
    /** WI-09: the room's name and code (rooms only). */
    val info: RoomInfo? = null,
    /** WI-09: what only the hosting device sees (pending joiners, start blockers). */
    val hostSide: HostSide? = null,
    /** WI-09: a joined table's connection story (C5–C9, H2). */
    val join: JoinStatus? = null,
    /** WI-09: the last session request that was refused (ready, follow, consent), for one message. */
    val notice: SessionNotice? = null,
)

/** WI-09: where the table runs. */
enum class TableKind {
    /** One human and three AI seats on this device, no network (GDN-005). */
    SOLO,

    /** This device hosts a room others join over the local network. */
    HOSTED,

    /** This device joined someone else's room. */
    JOINED,
}

/** The room as joiners tell it apart (C2): name, 4-digit code, host's nickname. */
data class RoomInfo(
    val roomId: String,
    val tableName: String,
    val tableCode: String,
    val hostName: String,
    /** WI-10: the medium this device actually uses for the room, so no label claims Bluetooth over Wi-Fi. */
    val medium: RoomMedium = RoomMedium.LAN,
)

/** A watcher's view of one player's seat, under that player's active consent. */
data class FollowedView(val grantId: String, val seat: Seat, val view: MatchMemberView)

/** Someone waiting for the host's approval (B8): compare [code] with the joiner's screen. */
data class PendingJoiner(val connectionId: Long, val nickname: String, val role: Role, val seat: Seat?, val replace: Boolean, val code: String)

/** WI-09: the host's side of a room. */
data class HostSide(
    val pending: List<PendingJoiner>,
    /** What keeps the lobby from starting ([LobbyGate]); empty when the host may start. */
    val startBlockers: List<LobbyGate.Blocker>,
    /** The room's absence policy has an AI substitute, so the host may let it stand in now (H1). */
    val canStandIn: Boolean,
    /** The table is saved on this device, so the host may suspend it (H9). */
    val saved: Boolean,
    /** The match is over and the host may start a rematch (GDN-017). */
    val canRematch: Boolean,
    /** Hosting stopped working ([cn.wpgd.app.room.RoomListener.onFailure]): nearby devices cannot find the room. */
    val hostingFailed: Boolean = false,
    /**
     * Join by address: the port this room listens on over the LAN (null for Bluetooth or when not
     * listening). The lobby shows it with this phone's Wi-Fi or hotspot addresses.
     */
    val listenPort: Int? = null,
)

/** A joined table's connection, as the joiner sees it. */
enum class JoinPhase {
    /** Connecting, saying hello. */
    CONNECTING,

    /** Waiting for the host's approval (C5): [JoinStatus.code] is shown to compare. */
    AWAITING_APPROVAL,
    ADMITTED,

    /** The host said no (C6). */
    DENIED,

    /** The seat was taken meanwhile (C8). */
    SEAT_TAKEN,

    /** Watchers full (G7) or the room refused more joiners. */
    FULL,

    /** Different app version or rules (C7). */
    INCOMPATIBLE,

    /** Never reached the room (C9). */
    FAILED,

    /** Admitted before, now the host is unreachable: rejoining automatically (H2). */
    HOST_LOST,

    /** The host suspended the table (it may come back). */
    SUSPENDED,

    /** The host ended the table, or this member left or was replaced. */
    ENDED,
}

data class JoinStatus(
    val phase: JoinPhase,
    /** The six-digit confirmation code (PROVISIONAL, G2), once keys exist. */
    val code: String?,
    val denial: AdmissionDenial?,
    val helloRejected: HelloRejectReason?,
    val closeReason: CloseReason?,
    /** Automatic rejoin attempts since the host was lost (H2). */
    val reconnectAttempts: Int,
)

/** WI-09: a session request (not a game command) and why the host refused it. */
enum class SessionAction { READY, FOLLOW_REQUEST, CONSENT, REVOKE, LEAVE }

data class SessionNotice(val action: SessionAction, val code: RejectionCode)

/** The owner's published state. Immutable; a new value for every change. */
data class OwnerState(
    val lifecycle: OwnerLifecycle,
    val table: TableSnapshot?,
    /** The first failure, for diagnostics (no cards, no capabilities). */
    val failure: String? = null,
) {
    companion object {
        val IDLE = OwnerState(OwnerLifecycle.IDLE, null)
    }
}

/** The outcome of [SessionOwner.submit]. */
sealed interface SubmitResult {
    /** Queued; the answer arrives as a receipt or a [CommandRejection] for [ticket]. */
    data class Sent(val ticket: Long) : SubmitResult

    /** Not sent at all: nothing reached the host. */
    data class Refused(val reason: SubmitRefusal) : SubmitResult
}

enum class SubmitRefusal {
    /** Another command is still pending: a double tap sends exactly one command. */
    ALREADY_PENDING,
    NO_TABLE,
    NOT_CONNECTED,
    ROOM_NOT_ACTIVE,
    SHUT_DOWN,

    /** A session task failed ([OwnerLifecycle.FAILED]): the table accepts no more input. */
    FAILED,
}
