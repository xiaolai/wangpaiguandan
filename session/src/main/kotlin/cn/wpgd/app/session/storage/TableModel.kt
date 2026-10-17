package cn.wpgd.app.session.storage

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.wire.B64
import cn.wpgd.app.session.wire.Role
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Storage schema of a table directory. Version history:
 * - 1: meta without epoch or retention fields (the first WI-06 draft envelope).
 * - 2: meta with epoch and retention.
 * - 3 (2026-10-16): current. Adds the `hostkey` file (the room key, G2) and stores match state format 2
 *   (with the match number) in checkpoints and record state hashes.
 * Schemas 1 and 2 were never released: a save in either is a pre-release reset, quarantined as
 * [QuarantineReason.UNSUPPORTED_OLD_SCHEMA] and kept until the user deletes it (no migration: its
 * records hash a state format this version no longer reads, and it has no room key).
 * A newer version is never read, rewritten or deleted (see [TableRecovery] and [TableRepository]).
 */
const val STORAGE_SCHEMA_VERSION = 3

/** File names of one table. Everything of a table lives under [dir]; deleting that prefix deletes it all. */
internal class TableFiles(val tableId: String) {
    init {
        require(tableId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "bad table id '$tableId'" }
    }

    val dir = "tables/$tableId/"
    val meta = dir + "meta"
    val hwm = dir + "hwm"
    val members = dir + "members"
    val hostKey = dir + "hostkey"
    val quarantine = dir + "quarantine"
    val tombstone = dir + "deleting"

    fun segment(n: Int) = dir + "log." + n.toString().padStart(6, '0')
    fun checkpoint(revision: Long) = dir + "checkpoint." + revision.toString().padStart(12, '0')

    fun segmentNumber(name: String): Int? = name.removePrefix(dir).takeIf { it.startsWith("log.") && name.startsWith(dir) }
        ?.removePrefix("log.")?.takeIf { it.length == 6 && it.all(Char::isDigit) }?.toInt()

    fun checkpointRevision(name: String): Long? = name.removePrefix(dir).takeIf { it.startsWith("checkpoint.") && name.startsWith(dir) }
        ?.removePrefix("checkpoint.")?.takeIf { it.length == 12 && it.all(Char::isDigit) }?.toLong()

    companion object {
        fun idOf(name: String): String? = name.removePrefix("tables/").takeIf { name.startsWith("tables/") }?.substringBefore('/', "")?.takeIf { it.isNotEmpty() }
    }
}

@Serializable
enum class TableStatus {
    /** Being played, or left by an accidental exit: recoverable, never expires. */
    ACTIVE,

    /** The host chose suspend: kept until [TableMeta.expiresAt], then removed by [TableRepository.sweep]. */
    SUSPENDED,

    /** The match ended or the host ended it: kept until [TableMeta.expiresAt]. */
    ENDED,
}

/** `meta`: identity and retention of a table. Times are wall-clock milliseconds. */
@Serializable
data class TableMeta(
    val schema: Int,
    val tableId: String,
    val epoch: Long,
    val baseProfileHash: String,
    val matchProfileHash: String,
    val createdAt: Long,
    val status: TableStatus,
    val statusAt: Long,
    val expiresAt: Long?,
) {
    init {
        require(schema == STORAGE_SCHEMA_VERSION) { "meta object of schema $schema" }
        require((expiresAt == null) == (status == TableStatus.ACTIVE)) { "only a suspended or ended table expires" }
    }
}

/**
 * `hostkey`: the room key of this table (G2), both halves base64url (SPKI and PKCS#8 DER).
 * Host-private like every file here (excluded from backup, audit A29); never sent to anyone.
 */
@Serializable
internal data class StoredHostKey(val publicKey: String, val privateKey: String)

/**
 * One admitted member and its rejoin credentials (base64url). Host-private (audit A29).
 * [privateView]: the one private viewpoint an observer held in a deal of the current match (P5),
 * so a restart cannot grant a second one. Credentials that are not 32 base64url-encoded bytes make the file unreadable.
 */
@Serializable
data class StoredMember(
    val id: String,
    val role: Role,
    val seat: Seat?,
    val nickname: String,
    val capability: String,
    val digestKey: String,
    val privateView: StoredPrivateView? = null,
) {
    init {
        require(B64.decode(capability)?.size == CREDENTIAL_BYTES && B64.decode(digestKey)?.size == CREDENTIAL_BYTES) {
            "stored credentials are not $CREDENTIAL_BYTES base64url-encoded bytes"
        }
        // A player holds a seat and an observer none; an observer with a seat would be projected its private view.
        require((role == Role.PLAYER) == (seat != null)) { "a stored $role ${if (seat == null) "without" else "with"} a seat" }
        require(privateView == null || role == Role.OBSERVER) { "only an observer holds a private viewpoint" }
    }

    companion object {
        /** Capabilities and digest keys are 32 random bytes; anything else can never rejoin. */
        const val CREDENTIAL_BYTES = 32
    }
}

/** (deal number, seat) of an observer's private viewpoint. */
@Serializable
data class StoredPrivateView(val dealNumber: Int, val seat: Seat)

/**
 * `members`: who may rejoin, and which seats the host gave to an AI. Rewritten whole on every change.
 * [standInSeats] and [awaitingSeats]: seats whose human left, now played by the stand-in AI or
 * waited for (the room paused); without them a restore would leave such a seat empty mid-deal.
 */
@Serializable
data class MembersFile(
    val nextMemberId: Long,
    val members: List<StoredMember>,
    val aiSeats: List<Seat>,
    val standInSeats: List<Seat> = emptyList(),
    val awaitingSeats: List<Seat> = emptyList(),
)

/** The issuer, command id and receipt digest of a committed command: enough to resend the original receipt. */
@Serializable
data class CommandRef(val memberId: String, val commandId: String, val digest: String)

/** Payload of a log record: one revision's typed events and the host-internal hash of the state after them. */
@Serializable
internal data class RecordPayload(
    val matchEventSchema: Int,
    val dealEventSchema: Int,
    val command: CommandRef?,
    val stateHash: String,
    val events: JsonElement,
)

@Serializable
internal data class CheckpointPayload(
    val format: Int,
    val revision: Long,
    val baseProfileHash: String,
    val matchProfileHash: String,
    val stateHash: String,
    val state: JsonElement,
)

@Serializable
internal data class QuarantinePayload(val reason: QuarantineReason, val detail: String)

/** Why a table may not be played on. Only an explicit user action removes a quarantined table. */
@Serializable
enum class QuarantineReason {
    /** The last committed revision in the log is below the highest acknowledged revision. */
    BELOW_HIGH_WATER_MARK,

    /** The high-water-mark file is missing or fails its checksum: what was acknowledged is unknown. */
    HIGH_WATER_MARK_UNREADABLE,
    META_UNREADABLE,
    MEMBERS_UNREADABLE,

    /** Records and markers out of order, a marker for an unknown record, or a checksummed record that does not decode. */
    LOG_INCONSISTENT,

    /** The events do not replay, or replay to a state whose hash differs from the one recorded. */
    REPLAY_FAILED,

    /** The restored state breaks an invariant: card count, duplicate card, turn, phase or revision. */
    VALIDATION_FAILED,

    /** The table, a checkpoint or the restored state belongs to other rule profiles. */
    PROFILE_MISMATCH,

    /** A checkpoint is newer than the last committed revision of the log. */
    CHECKPOINT_AHEAD_OF_LOG,

    /** A member presented a revision above the one the host recovered. */
    MEMBER_AHEAD,

    /** An old schema with no migration. */
    UNSUPPORTED_OLD_SCHEMA,

    /** The room key file (G2) is missing or fails its checksum or decoding: rejoin proofs cannot be checked. */
    HOST_KEY_UNREADABLE,
}

internal object StorageJson {
    val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    /**
     * Decodes a checksummed file of [kind]: null when [bytes] is missing, fails its envelope or
     * checksum, or does not decode as [serializer] (including a rejected invariant).
     */
    fun <T> decodeChecked(kind: LogFormat.FileKind, bytes: ByteArray?, serializer: KSerializer<T>): T? {
        val payload = bytes?.let { LogFormat.unwrap(kind, it) } ?: return null
        return try {
            json.decodeFromString(serializer, payload.decodeToString())
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
