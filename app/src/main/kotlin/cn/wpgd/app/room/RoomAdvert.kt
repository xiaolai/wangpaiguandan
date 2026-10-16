package cn.wpgd.app.room

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.wire.PROTOCOL_VERSION
import java.security.MessageDigest

/**
 * What a hosted room announces on the local network (C2), and what a joiner needs to tell rooms apart
 * (GDN-009): the table name, the host's nickname and a 4-digit [tableCode] derived from the room id,
 * so two tables with the same name are still distinguishable. Carries no game data.
 *
 * Everything here arrives from the network and is untrusted until [AdvertCodec.decode] has bounded it.
 */
data class RoomAdvert(
    val roomId: String,
    val tableName: String,
    val hostName: String,
    val protocolMin: Int,
    val protocolMax: Int,
    val baseProfileHash: String,
    val matchProfileHash: String,
    /**
     * The fingerprint (base64url) of the room's host key (G2). A member that lost its host follows a
     * new address only when the advert names the key it pinned; the welcome is checked again on connect.
     */
    val hostKey: String,
    /** Seats a newcomer may ask for now (empty, or left by a departed player). */
    val openSeats: Set<Seat>,
    /** Where the room is reached, as discovery found it (never taken from the advert's attributes). */
    val endpoint: RoomEndpoint,
    /**
     * Seats whose player is away (grace running, AI standing in, or the room waiting for them): a
     * newcomer may ask the host to take one over (GDN-016); the host decides.
     */
    val replaceableSeats: Set<Seat> = emptySet(),
) {
    init {
        require(openSeats.none { it in replaceableSeats }) { "a seat is either open or replaceable" }
    }

    val tableCode: String get() = TableCode.of(roomId)

    /** Whether this device can join: the hello would be refused for these reasons anyway (C7). */
    fun compatibility(local: LocalVersion): Compatibility = when {
        local.protocolMax < protocolMin -> Compatibility.UPDATE_THIS_APP
        protocolMax < local.protocolMin -> Compatibility.HOST_MUST_UPDATE
        baseProfileHash != local.baseProfileHash || matchProfileHash != local.matchProfileHash -> Compatibility.OTHER_RULES
        else -> Compatibility.COMPATIBLE
    }
}

/** This app's protocol range and rule profiles, compared with a room's advert before joining. */
data class LocalVersion(val protocolMin: Int, val protocolMax: Int, val baseProfileHash: String, val matchProfileHash: String) {
    companion object {
        fun of(baseProfileHash: String, matchProfileHash: String) = LocalVersion(PROTOCOL_VERSION, PROTOCOL_VERSION, baseProfileHash, matchProfileHash)
    }
}

enum class Compatibility { COMPATIBLE, UPDATE_THIS_APP, HOST_MUST_UPDATE, OTHER_RULES }

/** The 4-digit table code (桌号) shown on the host and every joiner's screen. */
object TableCode {
    fun of(roomId: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(roomId.encodeToByteArray())
        val n = ((d[0].toLong() and 0xFF) shl 24) or ((d[1].toLong() and 0xFF) shl 16) or ((d[2].toLong() and 0xFF) shl 8) or (d[3].toLong() and 0xFF)
        return (n % 10_000).toString().padStart(4, '0')
    }
}

/**
 * Advert attributes as key/value strings (the DNS-SD TXT record), and their strict decoding:
 * a malformed or oversized value drops the advert instead of reaching the screen.
 */
object AdvertCodec {
    const val SERVICE_TYPE = "_wpgd._tcp"
    const val MAX_TABLE_NAME = 24
    const val MAX_HOST_NAME = 32
    private val ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val HASH = Regex("[0-9a-f]{64}")
    private val HOST_KEY = Regex("[A-Za-z0-9_-]{43}")

    /** Advert format. 2 (2026-10-16): adds `k`, the host key fingerprint (G2). A version-1 advert is ignored. */
    const val VERSION = "2"

    fun encode(a: RoomAdvert): Map<String, String> = linkedMapOf(
        "v" to VERSION,
        "id" to a.roomId,
        "n" to a.tableName,
        "h" to a.hostName,
        "p" to "${a.protocolMin}-${a.protocolMax}",
        "b" to a.baseProfileHash,
        "m" to a.matchProfileHash,
        "k" to a.hostKey,
        "s" to seatLetters(a.openSeats),
        "r" to seatLetters(a.replaceableSeats),
    )

    private fun seatLetters(seats: Set<Seat>): String = Seat.entries.filter { it in seats }.joinToString("") { it.name.take(1) }

    /** Seat letters, each at most once; null when malformed. */
    private fun seats(text: String?): Set<Seat>? {
        if (text == null || text.length > Seat.entries.size) return null
        val seats = text.map { c -> Seat.entries.firstOrNull { it.name.first() == c } ?: return null }
        return seats.toSet().takeIf { it.size == seats.size }
    }

    /** The advert, or null when anything is missing, malformed or out of bounds. */
    fun decode(attrs: Map<String, String>, endpoint: RoomEndpoint): RoomAdvert? {
        if (attrs["v"] != VERSION) return null
        val id = attrs["id"]?.takeIf { ID.matches(it) } ?: return null
        val name = attrs["n"]?.takeIf(::boundedName)?.takeIf { it.length <= MAX_TABLE_NAME } ?: return null
        val host = attrs["h"]?.takeIf(::boundedName)?.takeIf { it.length <= MAX_HOST_NAME } ?: return null
        val range = attrs["p"]?.split('-')?.takeIf { it.size == 2 }?.map { it.toIntOrNull() ?: return null }?.takeIf { it[0] in 1..it[1] } ?: return null
        val base = attrs["b"]?.takeIf { HASH.matches(it) } ?: return null
        val match = attrs["m"]?.takeIf { HASH.matches(it) } ?: return null
        val hostKey = attrs["k"]?.takeIf { HOST_KEY.matches(it) } ?: return null
        val open = seats(attrs["s"]) ?: return null
        val replaceable = seats(attrs["r"]) ?: return null
        if (open.any { it in replaceable }) return null
        return RoomAdvert(id, name, host, range[0], range[1], base, match, hostKey, open, endpoint, replaceable)
    }

    private fun boundedName(s: String): Boolean = s.isNotBlank() && s.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
}
