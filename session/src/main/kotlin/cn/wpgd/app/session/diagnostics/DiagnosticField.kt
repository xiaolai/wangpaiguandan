package cn.wpgd.app.session.diagnostics

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.framing.Violation
import cn.wpgd.app.session.host.EndReason
import cn.wpgd.app.session.host.PauseReason
import cn.wpgd.app.session.storage.QuarantineReason
import cn.wpgd.app.session.wire.CloseReason
import cn.wpgd.app.session.wire.Role

/**
 * The only value shapes a diagnostics line may have (WI-12, P8). None of them can carry free text:
 * a number, a flag, an enum constant from the field's own list, or a rule profile's public id,
 * version and hash (the same on every device that carries that profile).
 */
enum class Shape(internal val pattern: Regex) {
    /** A non-negative counter. */
    COUNT(Regex("0|[1-9][0-9]{0,18}")),

    /** A revision; -1 means "none yet". */
    REVISION(Regex("-1|0|[1-9][0-9]{0,18}")),

    /** A duration in milliseconds; -1 means "no sample". */
    MILLIS(Regex("-1|0|[1-9][0-9]{0,18}")),
    FLAG(Regex("true|false")),

    /** One constant of the field's [DiagnosticField.tokens]. */
    TOKEN(Regex("[A-Z][A-Z0-9_]{0,47}")),

    /** Same grammar as the rule profile loader's ids. */
    PROFILE_ID(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")),
    PROFILE_VERSION(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")),

    /** The app's version name (`versionName` in app/build.gradle.kts), MAJOR.MINOR.PATCH only. */
    APP_VERSION(Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})")),

    /** SHA-256 of a rule profile's canonical form (public; identical on every device). */
    PROFILE_HASH(Regex("[0-9a-f]{64}")),
}

/** Whether the build that wrote the export is a development build or a release build (plan section 3). */
enum class DiagnosticBuild { DEVELOPMENT, RELEASE }

/** The room's state, without the quarantine detail text (which names members). */
enum class RoomState { ACTIVE, PAUSED, SUSPENDED, ENDED, QUARANTINED }

/** Which table this device runs, if any. */
enum class TableKindToken { NONE, SOLO, HOSTED, JOINED }

/** The kind of the first failure of the session owner; the message is never exported (it can name cards). */
enum class FailureKind { NONE, ILLEGAL_STATE, ILLEGAL_ARGUMENT, IO, OUT_OF_MEMORY, OTHER }

enum class ProfileRole { BASE, MATCH }

/**
 * The allow-list of the diagnostics export (WI-12, GDN-108, P8). Every line of an export is one of
 * these fields; a field with [keys] is written once per key (`host.paused.HOST=false`). Adding a
 * field is a reviewed change to this list: its value must fit one of the [Shape]s, so a field can
 * hold a count, a revision, a flag or a fixed token, and never a hand, seed, capability, key, room
 * id, member id, nickname or confirmation code.
 */
enum class DiagnosticField(
    val key: String,
    val shape: Shape,
    /** For [Shape.TOKEN]: the only constants this field accepts. */
    val tokens: List<Enum<*>>? = null,
    /** For a keyed field: the only sub-keys it accepts. */
    val keys: List<Enum<*>>? = null,
) {
    FORMAT("format", Shape.COUNT),
    PROTOCOL_VERSION("protocol.version", Shape.COUNT),
    BUILD_MODE("build.mode", Shape.TOKEN, tokens = DiagnosticBuild.entries),
    APP_VERSION_CODE("app.version_code", Shape.COUNT),
    APP_VERSION_NAME("app.version_name", Shape.APP_VERSION),

    PROFILE_BASE_ID("profile.base.id", Shape.PROFILE_ID),
    PROFILE_BASE_VERSION("profile.base.version", Shape.PROFILE_VERSION),
    PROFILE_BASE_HASH("profile.base.hash", Shape.PROFILE_HASH),
    PROFILE_BASE_PROVISIONAL("profile.base.provisional", Shape.FLAG),
    PROFILE_MATCH_ID("profile.match.id", Shape.PROFILE_ID),
    PROFILE_MATCH_VERSION("profile.match.version", Shape.PROFILE_VERSION),
    PROFILE_MATCH_HASH("profile.match.hash", Shape.PROFILE_HASH),
    PROFILE_MATCH_PROVISIONAL("profile.match.provisional", Shape.FLAG),

    HOST_STATUS("host.status", Shape.TOKEN, tokens = RoomState.entries),
    HOST_PAUSED("host.paused", Shape.FLAG, keys = PauseReason.entries),
    HOST_QUARANTINE("host.quarantine", Shape.TOKEN, tokens = QuarantineReason.entries),
    HOST_ENDED("host.ended", Shape.TOKEN, tokens = EndReason.entries),
    HOST_PHASE("host.phase", Shape.TOKEN, tokens = MatchPhase.entries),
    HOST_REVISION("host.revision", Shape.REVISION),
    HOST_DEAL_NUMBER("host.deal_number", Shape.COUNT),
    HOST_COMMITS("host.commits", Shape.COUNT),
    HOST_REJECTIONS("host.rejections", Shape.COUNT),
    HOST_DUPLICATE_COMMANDS("host.duplicate_commands", Shape.COUNT),
    HOST_OBSERVER_COMMANDS_REJECTED("host.observer_commands_rejected", Shape.COUNT),
    HOST_STALE_CALLBACKS("host.stale_callbacks", Shape.COUNT),
    HOST_STALE_AI_TASKS("host.stale_ai_tasks", Shape.COUNT),
    HOST_AI_REJECTED("host.ai_rejected", Shape.COUNT),
    HOST_AI_CANCELLED("host.ai_cancelled", Shape.COUNT),
    HOST_AI_DUPLICATE_DELIVERIES("host.ai_duplicate_deliveries", Shape.COUNT),
    HOST_ADMITTED("host.admitted", Shape.COUNT),
    HOST_REJOINED("host.rejoined", Shape.COUNT),
    HOST_REPLACEMENTS("host.replacements", Shape.COUNT),
    HOST_DENIED("host.denied", Shape.COUNT),
    HOST_HELLO_REJECTED("host.hello_rejected", Shape.COUNT),
    HOST_CODE_MISMATCHES("host.code_mismatches", Shape.COUNT),
    HOST_IGNORED("host.ignored", Shape.COUNT),
    HOST_RESYNCS("host.resyncs", Shape.COUNT),
    HOST_RESYNCS_THROTTLED("host.resyncs_throttled", Shape.COUNT),
    HOST_GRANTS_ACTIVATED("host.grants_activated", Shape.COUNT),
    HOST_GRANTS_REVOKED("host.grants_revoked", Shape.COUNT),
    HOST_FOLLOW_FRAMES_DROPPED("host.follow_frames_dropped", Shape.COUNT),
    HOST_HEARTBEAT_TIMEOUTS("host.heartbeat_timeouts", Shape.COUNT),
    HOST_TRANSPORT_CLOSED("host.transport_closed", Shape.COUNT),
    HOST_STORAGE_FAILURES("host.storage_failures", Shape.COUNT),
    HOST_CHECKPOINTS("host.checkpoints", Shape.COUNT),
    HOST_CHECKPOINT_FAILURES("host.checkpoint_failures", Shape.COUNT),
    HOST_MEMBERS_AHEAD("host.members_ahead", Shape.COUNT),
    HOST_SEAT_TRANSITIONS("host.seat_transitions", Shape.COUNT),
    HOST_VIOLATIONS("host.violations", Shape.COUNT, keys = Violation.entries),
    HOST_CLOSED("host.closed", Shape.COUNT, keys = CloseReason.entries),

    CLIENT_ROLE("client.role", Shape.TOKEN, tokens = Role.entries),
    CLIENT_PHASE("client.phase", Shape.TOKEN, tokens = ClientPhase.entries),
    CLIENT_CLOSE_REASON("client.close_reason", Shape.TOKEN, tokens = CloseReason.entries),
    CLIENT_REVISION("client.revision", Shape.REVISION),
    CLIENT_STATUS_REVISION("client.status_revision", Shape.COUNT),
    CLIENT_STALE("client.stale", Shape.FLAG),
    CLIENT_PENDING_COMMANDS("client.pending_commands", Shape.COUNT),
    CLIENT_DIGESTS_VERIFIED("client.digests_verified", Shape.COUNT),
    CLIENT_DIGEST_MISMATCHES("client.digest_mismatches", Shape.COUNT),
    CLIENT_UNVERIFIED_RECEIPTS("client.unverified_receipts", Shape.COUNT),
    CLIENT_RESYNC_REQUESTS("client.resync_requests", Shape.COUNT),
    CLIENT_STALE_FOLLOW_DISCARDED("client.stale_follow_discarded", Shape.COUNT),
    CLIENT_FOLLOW_CLEARED("client.follow_cleared", Shape.COUNT),
    CLIENT_OLDER_SNAPSHOTS_IGNORED("client.older_snapshots_ignored", Shape.COUNT),
    CLIENT_DUPLICATE_SNAPSHOTS("client.duplicate_snapshots", Shape.COUNT),
    CLIENT_OLDER_STATUS_IGNORED("client.older_status_ignored", Shape.COUNT),
    CLIENT_BAD_SNAPSHOTS("client.bad_snapshots", Shape.COUNT),
    CLIENT_STALE_EPOCH_FRAMES("client.stale_epoch_frames", Shape.COUNT),
    CLIENT_PROFILE_HASH_MISMATCHES("client.profile_hash_mismatches", Shape.COUNT),
    CLIENT_DUPLICATE_REPLIES("client.duplicate_replies", Shape.COUNT),
    CLIENT_REJECTIONS("client.rejections", Shape.COUNT),
    CLIENT_COMMAND_SENDS("client.command_sends", Shape.COUNT),
    CLIENT_STALE_CALLBACKS("client.stale_callbacks", Shape.COUNT),
    CLIENT_VIOLATIONS("client.violations", Shape.COUNT),
    CLIENT_LIVENESS_TIMEOUTS("client.liveness_timeouts", Shape.COUNT),
    CLIENT_IGNORED("client.ignored", Shape.COUNT),
    CLIENT_SEAT_MOVES("client.seat_moves", Shape.COUNT),
    CLIENT_HOST_KEY_MISMATCHES("client.host_key_mismatches", Shape.COUNT),

    /** The app's session owner (`:app`), written by the app. */
    OWNER_TABLE("owner.table", Shape.TOKEN, tokens = TableKindToken.entries),
    /** False when the session loop did not answer the export in time (a stall); the session lines are then missing. */
    OWNER_LOOP_RESPONSIVE("owner.loop_responsive", Shape.FLAG),
    OWNER_COMMANDS_SENT("owner.commands_sent", Shape.COUNT),
    OWNER_CONNECTIONS("owner.connections", Shape.COUNT),
    OWNER_REJOIN_ATTEMPTS("owner.rejoin_attempts", Shape.COUNT),
    OWNER_ROOM_MOVES("owner.room_moves", Shape.COUNT),
    OWNER_ROOM_MOVES_REFUSED("owner.room_moves_refused", Shape.COUNT),
    OWNER_FAILURES("owner.failures", Shape.COUNT),
    OWNER_FAILURE_KIND("owner.failure_kind", Shape.TOKEN, tokens = FailureKind.entries),
    OWNER_RECEIPTS("owner.receipts", Shape.COUNT),
    OWNER_RECEIPT_MS_P50("owner.receipt_ms_p50", Shape.MILLIS),
    OWNER_RECEIPT_MS_P95("owner.receipt_ms_p95", Shape.MILLIS),
    OWNER_RECEIPT_MS_MAX("owner.receipt_ms_max", Shape.MILLIS),
    OWNER_AI_DECISIONS("owner.ai_decisions", Shape.COUNT),
    OWNER_AI_DECISION_MS_P50("owner.ai_decision_ms_p50", Shape.MILLIS),
    OWNER_AI_DECISION_MS_P95("owner.ai_decision_ms_p95", Shape.MILLIS),
    OWNER_AI_DECISION_MS_MAX("owner.ai_decision_ms_max", Shape.MILLIS),

    /** Uncaught exceptions that ended the app's process, counted on this device (kill-switch input). */
    PROCESS_CRASHES("process.crashes", Shape.COUNT),
    ;

    init {
        require((shape == Shape.TOKEN) == (tokens != null)) { "$key: a token field and only a token field lists its tokens" }
        require(keys == null || shape != Shape.TOKEN) { "$key: a keyed field holds counts or flags" }
    }
}
