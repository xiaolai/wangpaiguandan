package cn.wpgd.app.session.wire

import cn.wpgd.app.core.Suit
import cn.wpgd.app.core.rules.CombinationType
import cn.wpgd.app.core.state.Seat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire messages of the session protocol, version [PROTOCOL_VERSION]. JSON with a `type`
 * discriminator, decoded strictly (unknown or missing fields reject; see [WireCodec]). Fixtures for
 * every message live in `core/src/test/resources/fixtures/wire/` and are shared with the iPhone client.
 *
 * Plain (handshake, before keys exist): [Hello], [Welcome], [HelloRejected]. Everything else is
 * sent only inside a sealed frame. Binary values (keys, capabilities, digests) are base64url
 * without padding.
 */
/**
 * 2 (2026-10-16): G2 host authentication (welcome carries the host's room key and a fresh nonce; a
 * rejoin sends a proof instead of the capability) and the match number in every match view. Version
 * 1 was never released; a version-1 peer is refused at hello (`VERSION_UNSUPPORTED`).
 */
const val PROTOCOL_VERSION = 2

@Serializable
enum class Role { PLAYER, OBSERVER }

/** Room assistance policy (G4 proposed default; PROVISIONAL). */
@Serializable
enum class RoomMode { FRIENDLY, COMPETITIVE }

@Serializable
data class RoomPolicyView(val mode: RoomMode, val hints: Boolean, val privateFollow: Boolean)

@Serializable
sealed interface WireMessage

/** Messages that may travel in a PLAIN frame. */
sealed interface HandshakeMessage : WireMessage

// ---- Handshake (plain) ------------------------------------------------------------------------

/** Client → host. Version range, role and both profile hashes are checked before anything else. */
@Serializable
@SerialName("hello")
data class Hello(
    val protocolMin: Int,
    val protocolMax: Int,
    val role: Role,
    val roomId: String,
    val baseProfileHash: String,
    val matchProfileHash: String,
    val publicKey: String,
) : HandshakeMessage

/**
 * Host → client. Carries no game data: only what the joiner needs to derive keys. [publicKey] is the
 * host's ephemeral key for this connection; [hostKey] is its static room key (G2), which the keys
 * also depend on and which a returning member must find equal to the one it pinned; [nonce] (32
 * random bytes, fresh per connection) makes every rejoin proof single-use.
 */
@Serializable
@SerialName("welcome")
data class Welcome(
    val protocolVersion: Int,
    val roomId: String,
    val epoch: Long,
    val publicKey: String,
    val hostKey: String,
    val nonce: String,
    val policy: RoomPolicyView,
) : HandshakeMessage

@Serializable
enum class HelloRejectReason { VERSION_UNSUPPORTED, ROOM_MISMATCH, PROFILE_MISMATCH, ROOM_FULL, MALFORMED }

/** Host → client, then the host closes. [protocolMin]/[protocolMax] make a version mismatch actionable. */
@Serializable
@SerialName("hello_rejected")
data class HelloRejected(
    val reason: HelloRejectReason,
    val protocolMin: Int,
    val protocolMax: Int,
    val message: String,
) : HandshakeMessage

// ---- Admission (sealed) -----------------------------------------------------------------------

/**
 * Client → host. A player names the [seat] it claims; [replace] asks to take over an occupied seat
 * (host-approved). [rejoinProof] re-admits a returning member without approval: HMAC-SHA256 of this
 * connection's transcript keyed with the member's capability ([cn.wpgd.app.session.crypto.RejoinProof]).
 * The capability itself is never sent by a joiner.
 */
@Serializable
@SerialName("join")
data class Join(
    val epoch: Long,
    val nickname: String,
    val seat: Seat?,
    val replace: Boolean,
    val rejoinProof: String?,
) : WireMessage

@Serializable
@SerialName("admission_pending")
data class AdmissionPending(val epoch: Long) : WireMessage

/**
 * Host → client. [capability] authorizes this member's commands in this epoch; [digestKey] keys the
 * HMAC of its receipts. Both are fresh random values, sent only inside the sealed channel.
 */
@Serializable
@SerialName("admitted")
data class Admitted(
    val epoch: Long,
    val memberId: String,
    val role: Role,
    val seat: Seat?,
    val capability: String,
    val digestKey: String,
) : WireMessage

@Serializable
enum class AdmissionDenial { DENIED_BY_HOST, SEAT_TAKEN, SEAT_INVALID, OBSERVERS_FULL, PENDING_FULL, CAPABILITY_INVALID, ROLE_MISMATCH }

@Serializable
@SerialName("admission_denied")
data class AdmissionDenied(val epoch: Long, val reason: AdmissionDenial) : WireMessage

// ---- Commands and receipts (sealed) -----------------------------------------------------------

@Serializable
data class WireAssignment(val rank: Int, val suit: Suit?)

/** What a command asks for. The seat is never in the body: the host derives it from the capability. */
@Serializable
sealed interface CommandBody {
    /** True for bodies that act in the game; observers may never send these. */
    val isGame: Boolean
}

@Serializable
@SerialName("play")
data class PlayBody(val cardIds: List<String>, val declaration: List<WireAssignment>?, val combinationType: CombinationType?) : CommandBody {
    override val isGame: Boolean get() = true
}

@Serializable
@SerialName("pass")
data object PassBody : CommandBody {
    override val isGame: Boolean get() = true
}

@Serializable
@SerialName("give_tribute")
data class GiveTributeBody(val cardId: String) : CommandBody {
    override val isGame: Boolean get() = true
}

@Serializable
@SerialName("return_tribute")
data class ReturnTributeBody(val cardId: String) : CommandBody {
    override val isGame: Boolean get() = true
}

@Serializable
@SerialName("resist")
data object ResistBody : CommandBody {
    override val isGame: Boolean get() = true
}

@Serializable
@SerialName("ask_count")
data class AskCountBody(val target: Seat) : CommandBody {
    override val isGame: Boolean get() = true
}

/** Observer asks to follow [seat]'s private view (needs that player's consent). */
@Serializable
@SerialName("follow_request")
data class FollowRequestBody(val seat: Seat) : CommandBody {
    override val isGame: Boolean get() = false
}

/** The followed player answers a follow request. */
@Serializable
@SerialName("consent")
data class ConsentBody(val grantId: String, val approve: Boolean) : CommandBody {
    override val isGame: Boolean get() = false
}

/** Either side of a grant ends it. */
@Serializable
@SerialName("revoke")
data class RevokeBody(val grantId: String) : CommandBody {
    override val isGame: Boolean get() = false
}

@Serializable
@SerialName("leave")
data object LeaveBody : CommandBody {
    override val isGame: Boolean get() = false
}

/**
 * WI-09: a player says it is ready (or no longer ready) to start under the room settings at
 * [settingsRevision], which it has seen in [RoomView]. Only in the lobby; a settings change since
 * then is refused ([RejectionCode.SETTINGS_CHANGED]), so a player is never ready for rules it has not seen.
 */
@Serializable
@SerialName("ready")
data class ReadyBody(val ready: Boolean, val settingsRevision: Long) : CommandBody {
    override val isGame: Boolean get() = false
}

/**
 * Client → host. [commandId] makes retries idempotent; [expectedRevision] is the committed revision
 * the client acted on (game bodies are refused when it is not current).
 */
@Serializable
@SerialName("command")
data class CommandMessage(
    val epoch: Long,
    val capability: String,
    val commandId: String,
    val expectedRevision: Long,
    val body: CommandBody,
) : WireMessage

/**
 * Host → issuer. [revision] is the committed revision after the command; [digest] is
 * HMAC-SHA256(member digest key, canonical projection bytes of the issuer's own view at [revision]).
 * Never a hash of the full authoritative state.
 */
@Serializable
@SerialName("receipt")
data class Receipt(val epoch: Long, val commandId: String, val revision: Long, val digest: String) : WireMessage

@Serializable
enum class RejectionCode {
    /** The command named an epoch other than the room's current one. */
    STALE_EPOCH,

    /** The capability is unknown, revoked, or not this connection's. */
    BAD_CAPABILITY,

    /** [CommandMessage.expectedRevision] is not the committed revision. */
    STALE_REVISION,

    /** An observer sent a game command (GDN-070). */
    NOT_A_PLAYER,

    /** The game reducer refused it; the reason names the rule. */
    RULE,

    /** Field values out of bounds. */
    MALFORMED,

    /** Private follow is off in this room (competitive policy). */
    FOLLOW_DISABLED,

    /** The observer already had a private view of another seat in this deal (P5). */
    ONE_VIEW_PER_DEAL,

    /** The observer already has a pending or active grant. */
    ALREADY_FOLLOWING,

    /** No consenting human holds the seat. */
    NO_CONSENTING_PLAYER,

    /** No such grant for this member, or it is no longer pending. */
    GRANT_UNKNOWN,

    /** The game has not started or the seat is not this member's to act for. */
    NOT_SEATED,

    /** The room is paused (host, awaiting a player, or a failed save): nothing is applied; retry after it resumes (WI-06). */
    ROOM_PAUSED,

    /** The room's save is quarantined: nothing is applied until the host user acts (WI-06). */
    ROOM_QUARANTINED,

    /** WI-09: a lobby command (ready) after the match started. */
    NOT_IN_LOBBY,

    /** WI-09: the host changed the room settings since the revision the player saw; it must look again. */
    SETTINGS_CHANGED,
}

/** Host → issuer: a typed rejection (audit A20). [revision] is the current committed revision. */
@Serializable
@SerialName("rejection")
data class RejectionReceipt(
    val epoch: Long,
    val commandId: String,
    val revision: Long,
    val code: RejectionCode,
    val reason: String,
) : WireMessage

// ---- State delivery (sealed) ------------------------------------------------------------------

/**
 * Host → member. [view] is the recipient's canonical projection, exactly the bytes the digest covers
 * (a player's `MatchMemberView`, an observer's `MatchSpectatorView`, as UTF-8 JSON).
 */
@Serializable
@SerialName("snapshot")
data class Snapshot(val epoch: Long, val revision: Long, val view: String) : WireMessage

/** Host → observer with an active grant: the followed seat's view, tagged with the grant revision. */
@Serializable
@SerialName("follow_snapshot")
data class FollowSnapshot(
    val epoch: Long,
    val revision: Long,
    val grantId: String,
    val grantRevision: Long,
    val seat: Seat,
    val view: String,
) : WireMessage

@Serializable
enum class GrantStatus { PENDING, ACTIVE, DENIED, REVOKED }

@Serializable
data class GrantView(
    val grantId: String,
    val observerName: String,
    val seat: Seat,
    val status: GrantStatus,
    val grantRevision: Long,
)

/** WI-09: what happens to an absent human's seat (mirrors the host's recovery policy). */
@Serializable
enum class AbsenceMode { SUBSTITUTE, PAUSE }

/**
 * WI-09: the room settings every member sees before readying (D1) and at the table: assistance
 * (hints, private follow) and what happens to an absent player. The rule profiles themselves are
 * fixed by hash at hello.
 */
@Serializable
data class RoomSettingsView(val policy: RoomPolicyView, val graceMs: Long, val afterGrace: AbsenceMode, val afterLeave: AbsenceMode)

/** WI-09: who holds a seat, as every member may know it (no card data). */
@Serializable
enum class Occupant {
    EMPTY,

    /** A connected human. */
    HUMAN,

    /** Its human is disconnected and the grace is running (T6). */
    ABSENT,

    /** An AI plays for an absent or departed human (T7). */
    STAND_IN,

    /** The room waits for this seat's human (or a replacement). */
    AWAITING_PLAYER,

    /** The host gave the seat to an AI. */
    AI,
}

/** One seat of the roster. [graceRemainingMs] is set only while [occupant] is ABSENT, measured when sent. */
@Serializable
data class RosterSeat(val seat: Seat, val occupant: Occupant, val nickname: String?, val ready: Boolean, val graceRemainingMs: Long?)

/**
 * WI-09: the room as every member sees it. [lobby] is true before the first deal of a match (ready
 * and seat changes happen only then); [paused] is true while the room accepts no game command (the
 * host paused it, a seat waits for its player, or a save failed: H8). [seats] lists all four seats in [Seat] order; [observers] are
 * the admitted observers' nicknames (who watches, GDN-067). [yourSeat] is the recipient's own seat,
 * which the host may change in the lobby (B10).
 */
@Serializable
data class RoomView(
    val lobby: Boolean,
    val paused: Boolean,
    val settings: RoomSettingsView,
    val settingsRevision: Long,
    val seats: List<RosterSeat>,
    val observers: List<String>,
    val yourSeat: Seat?,
)

/**
 * Host → member: session state that is not game state. For a player, the grants on its seat (who
 * watches, who asks: the visibility indicator, GDN-067); for an observer, its own grants. Since
 * WI-09 also the room roster and settings ([room]). [statusRevision] increases on every change, and
 * heartbeats announce it, so a lost status is noticed and resent.
 */
@Serializable
@SerialName("status")
data class Status(val epoch: Long, val statusRevision: Long, val grants: List<GrantView>, val room: RoomView) : WireMessage

/**
 * Both ways. From the host: its committed [revision] and the member's [statusRevision] (null
 * before admission), so a client that missed a frame notices and resyncs.
 */
@Serializable
@SerialName("heartbeat")
data class Heartbeat(val epoch: Long, val revision: Long?, val statusRevision: Long?) : WireMessage

/** Client → host: send my current snapshot and status again. */
@Serializable
@SerialName("resync")
data class Resync(val epoch: Long, val capability: String) : WireMessage

@Serializable
enum class CloseReason {
    /** The seat was handed to an approved replacement; this member's capability is gone. */
    REPLACED,

    /** The same member rejoined on a newer connection. */
    SUPERSEDED,
    EPOCH_ENDED,
    LEFT,
    HELLO_TIMEOUT,
    HEARTBEAT_TIMEOUT,
    PROTOCOL_VIOLATION,
    QUEUE_OVERFLOW,
    RATE_LIMITED,
    DENIED,

    /** The host suspended the table (WI-06); the capability stays valid for when it resumes. */
    SUSPENDED,

    /**
     * Client side only, never sent by a host (G2): the welcome carried a host key other than the one
     * this member pinned. Nothing was sent after it; the member keeps its ticket and tries again later.
     */
    HOST_KEY_MISMATCH,
}

@Serializable
@SerialName("closing")
data class Closing(val epoch: Long, val reason: CloseReason) : WireMessage
