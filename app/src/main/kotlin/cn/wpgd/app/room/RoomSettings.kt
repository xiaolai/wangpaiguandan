package cn.wpgd.app.room

import cn.wpgd.app.data.KeyValueStore
import cn.wpgd.app.session.host.AbsencePolicy
import cn.wpgd.app.session.host.RecoveryPolicy
import cn.wpgd.app.session.host.RoomPolicy
import cn.wpgd.app.session.host.SeatPolicy
import cn.wpgd.app.session.wire.RoomMode

/**
 * What the host chooses when creating a room (B1) and may change in the lobby (D2): the table name,
 * assistance ([mode]: friendly allows hints and private follow, competitive turns both off; G4
 * PROVISIONAL) and what happens to an absent player ([absence] after [graceSeconds]; plan section 3
 * leaves the grace to the lobby). The rule profiles are fixed by the app build.
 */
data class RoomSettings(
    val tableName: String,
    val mode: RoomMode = RoomMode.FRIENDLY,
    val absence: AbsencePolicy = AbsencePolicy.SUBSTITUTE,
    val graceSeconds: Int = DEFAULT_GRACE_SECONDS,
) {
    init {
        require(validName(tableName)) { "bad table name" }
        require(graceSeconds in GRACE_CHOICES) { "grace $graceSeconds s is not offered" }
    }

    /** The host's room policy. With spectators switched off ([Features.SPECTATORS]) no hand can be followed. */
    fun policy(spectators: Boolean = Features.SPECTATORS): RoomPolicy {
        val base = if (mode == RoomMode.FRIENDLY) RoomPolicy.FRIENDLY else RoomPolicy.COMPETITIVE
        return if (spectators) base else base.copy(privateFollow = false)
    }

    /** The session recovery policy; [substitute] is the AI that stands in (the room's AI driver). */
    fun recovery(substitute: SeatPolicy): RecoveryPolicy = RecoveryPolicy(graceSeconds * 1_000L, absence, absence, substitute)

    companion object {
        /** Grace choices offered in the lobby (the ledger's 10 s and the prototype's 30 s, plus 60 s). */
        val GRACE_CHOICES = listOf(10, 30, 60)
        const val DEFAULT_GRACE_SECONDS = 30
        const val MAX_NAME_CODE_POINTS = 12

        fun validName(name: String): Boolean = name.isNotBlank() && name == name.trim() &&
            name.codePointCount(0, name.length) <= MAX_NAME_CODE_POINTS && name.length <= AdvertCodec.MAX_TABLE_NAME &&
            name.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
    }
}

/**
 * The settings of each saved hosted table, so a restored table runs under the settings it was
 * created with. Its own store; deleting a table removes its entry ([forget]).
 */
class RoomStore(private val store: KeyValueStore) {
    @Synchronized
    fun save(tableId: String, s: RoomSettings) {
        store.put(KEY + tableId, listOf(s.mode.name, s.absence.name, s.graceSeconds.toString(), s.tableName).joinToString(SEP))
    }

    /** The saved settings, or null when none are stored or they no longer parse. */
    @Synchronized
    fun load(tableId: String): RoomSettings? {
        val parts = store.get(KEY + tableId)?.split(SEP, limit = 4) ?: return null
        if (parts.size != 4) return null
        return try {
            RoomSettings(parts[3], RoomMode.valueOf(parts[0]), AbsencePolicy.valueOf(parts[1]), parts[2].toInt())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** WI-10: the medium a saved table was hosted on, so it is restored on the same one. */
    @Synchronized
    fun saveMedium(tableId: String, medium: RoomMedium) = store.put(MEDIUM + tableId, medium.name)

    /** The saved medium; LAN for tables saved before Bluetooth existed or when unreadable. */
    @Synchronized
    fun medium(tableId: String): RoomMedium = store.get(MEDIUM + tableId)?.let { m -> RoomMedium.entries.firstOrNull { it.name == m } } ?: RoomMedium.LAN

    @Synchronized
    fun forget(tableId: String) {
        store.remove(KEY + tableId)
        store.remove(MEDIUM + tableId)
    }

    private companion object {
        const val KEY = "settings."
        const val MEDIUM = "medium."
        const val SEP = "\u001f"
    }
}
