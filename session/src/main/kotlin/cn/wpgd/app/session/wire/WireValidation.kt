package cn.wpgd.app.session.wire

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.StandardDeck

/**
 * Field bounds checked after decoding (zero trust at the boundary, GDN-027). Returns the first
 * problem, or null when the message is within bounds.
 */
object WireValidation {
    const val MAX_NICKNAME = 32
    const val MAX_ID = 64
    const val MAX_CARDS = 27
    const val MAX_KEY_B64 = 200
    const val MAX_GRANTS = 16
    const val MAX_OBSERVERS = 16
    const val DIGEST_BYTES = 32
    const val NONCE_BYTES = 32

    /** A capability or digest key ([Admitted]): 32 random bytes, base64url without padding. */
    const val CREDENTIAL_BYTES = 32
    private val ID = Regex("[A-Za-z0-9_-]{1,$MAX_ID}")
    private val HASH = Regex("[0-9a-f]{64}")

    /** A room listing's table name: the same bound as the discovery advert's (`AdvertCodec`). */
    const val MAX_TABLE_NAME = 24

    /** A host key fingerprint: 32 bytes, base64url without padding. */
    private val HOST_KEY = Regex("[A-Za-z0-9_-]{43}")

    fun problem(m: WireMessage): String? = when (m) {
        is Hello -> when {
            m.protocolMin < 1 || m.protocolMax < m.protocolMin -> "protocol range"
            !ID.matches(m.roomId) -> "roomId"
            !HASH.matches(m.baseProfileHash) || !HASH.matches(m.matchProfileHash) -> "profile hash"
            m.publicKey.length > MAX_KEY_B64 || B64.decode(m.publicKey) == null -> "publicKey"
            else -> null
        }
        is Welcome -> when {
            !ID.matches(m.roomId) -> "roomId"
            m.publicKey.length > MAX_KEY_B64 || B64.decode(m.publicKey) == null -> "publicKey"
            m.hostKey.length > MAX_KEY_B64 || B64.decode(m.hostKey) == null -> "hostKey"
            m.nonce.length > MAX_KEY_B64 || B64.decode(m.nonce)?.size != NONCE_BYTES -> "nonce"
            else -> null
        }
        is HelloRejected -> if (m.message.length > 200) "message" else null
        is RoomQuery -> if (m.protocolMin < 1 || m.protocolMax < m.protocolMin) "protocol range" else null
        is RoomListing -> when {
            m.protocolMin < 1 || m.protocolMax < m.protocolMin -> "protocol range"
            !ID.matches(m.roomId) -> "roomId"
            !listedName(m.tableName, MAX_TABLE_NAME) -> "tableName"
            !listedName(m.hostName, MAX_NICKNAME) -> "hostName"
            !HASH.matches(m.baseProfileHash) || !HASH.matches(m.matchProfileHash) -> "profile hash"
            !HOST_KEY.matches(m.hostKey) -> "hostKey"
            m.openSeats.toSet().size != m.openSeats.size || m.replaceableSeats.toSet().size != m.replaceableSeats.size -> "seats"
            m.openSeats.any { it in m.replaceableSeats } -> "seats"
            else -> null
        }
        is Join -> when {
            !nickname(m.nickname) -> "nickname"
            m.rejoinProof != null && (m.rejoinProof.length > MAX_KEY_B64 || B64.decode(m.rejoinProof)?.size != DIGEST_BYTES) -> "rejoinProof"
            else -> null
        }
        is Admitted -> when {
            !ID.matches(m.memberId) -> "ids"
            // Rejoin credentials are 32 random bytes; anything else could never be used to rejoin.
            !credential(m.capability) || !credential(m.digestKey) -> "credentials"
            else -> null
        }
        is CommandMessage -> when {
            !ID.matches(m.capability) || !ID.matches(m.commandId) -> "ids"
            m.expectedRevision < 0 -> "expectedRevision"
            else -> body(m.body)
        }
        is Receipt -> when {
            !ID.matches(m.commandId) -> "ids"
            // HMAC-SHA256: exactly 32 bytes, so the client can never skip verifying it.
            m.digest.length > MAX_ID || B64.decode(m.digest)?.size != DIGEST_BYTES -> "digest"
            else -> null
        }
        is RejectionReceipt -> if (!ID.matches(m.commandId) || m.reason.length > 200) "ids" else null
        is FollowSnapshot -> if (!ID.matches(m.grantId)) "grantId" else null
        is Status -> when {
            m.grants.size > MAX_GRANTS || m.grants.any { !ID.matches(it.grantId) } -> "grants"
            m.grants.any { !nickname(it.observerName) || !nickname(it.playerName) || (it.end != null) != (it.status == GrantStatus.REVOKED) } -> "grants"
            else -> room(m.room)
        }
        is Resync -> if (!ID.matches(m.capability)) "capability" else null
        is AdmissionPending, is AdmissionDenied, is Snapshot, is Heartbeat, is Closing -> null
    }

    /** Four seats in seat order, bounded names, a non-negative grace. */
    private fun room(r: RoomView): String? = when {
        r.seats.map { it.seat } != Seat.entries -> "seats"
        r.seats.any { s -> s.nickname?.let { !nickname(it) } == true || (s.graceRemainingMs ?: 0) < 0 } -> "seat"
        r.observers.size > MAX_OBSERVERS || r.observers.any { !nickname(it) } -> "observers"
        r.settings.graceMs < 0 || r.settingsRevision < 0 -> "settings"
        else -> null
    }

    /** Not blank, bounded, and no control or invisible formatting character (a listing reaches the screen). */
    private fun listedName(n: String, max: Int): Boolean =
        n.isNotBlank() && n.length <= max && n.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }

    private fun credential(c: String): Boolean = c.length <= MAX_ID && B64.decode(c)?.size == CREDENTIAL_BYTES

    private fun nickname(n: String): Boolean = n.isNotBlank() && n.length <= MAX_NICKNAME && n.none { it.isISOControl() }

    private fun body(b: CommandBody): String? = when (b) {
        is PlayBody -> when {
            b.cardIds.isEmpty() || b.cardIds.size > MAX_CARDS -> "cardIds"
            b.cardIds.any { it !in StandardDeck.ids } -> "unknown card"
            (b.declaration?.size ?: 0) > MAX_CARDS -> "declaration"
            else -> null
        }
        is GiveTributeBody -> if (b.cardId !in StandardDeck.ids) "unknown card" else null
        is ReturnTributeBody -> if (b.cardId !in StandardDeck.ids) "unknown card" else null
        is ConsentBody -> if (!ID.matches(b.grantId)) "grantId" else null
        is RevokeBody -> if (!ID.matches(b.grantId)) "grantId" else null
        is ReadyBody -> if (b.settingsRevision < 0) "settingsRevision" else null
        PassBody, ResistBody, LeaveBody, is AskCountBody, is FollowRequestBody -> null
    }
}
