package cn.wpgd.app.data

/**
 * One namespace of local, device-only string storage. Each kind of data (preferences, nickname,
 * history, identity) gets its own store, so clearing one can never touch another (GDN-008).
 * Implementations: [InMemoryKeyValueStore] (tests) and `SharedPreferencesKeyValueStore` (Android;
 * excluded from backup and device transfer by `data_extraction_rules.xml`, P7).
 */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>

    /** Removes every key of this store only. */
    fun clear()
}

/** Thread-safe in-memory store. */
class InMemoryKeyValueStore : KeyValueStore {
    private val map = LinkedHashMap<String, String>()

    @Synchronized override fun get(key: String): String? = map[key]

    @Synchronized override fun put(key: String, value: String) {
        map[key] = value
    }

    @Synchronized override fun remove(key: String) {
        map.remove(key)
    }

    @Synchronized override fun keys(): Set<String> = map.keys.toSet()

    @Synchronized override fun clear() = map.clear()
}

/** The separate stores of the app. */
class LocalStores(
    val preferences: KeyValueStore,
    val profile: KeyValueStore,
    val history: KeyValueStore,
    val identity: KeyValueStore,
    /** WI-09: the settings of saved hosted tables ([cn.wpgd.app.room.RoomStore]). */
    val rooms: KeyValueStore,
) {
    init {
        val all = listOf(preferences, profile, history, identity, rooms)
        require(all.toSet().size == all.size) { "each kind of data needs its own store" }
    }

    companion object {
        fun inMemory() = LocalStores(InMemoryKeyValueStore(), InMemoryKeyValueStore(), InMemoryKeyValueStore(), InMemoryKeyValueStore(), InMemoryKeyValueStore())
    }
}

/** Wall-clock time for stored timestamps (history). Not used for session timing. */
fun interface WallClock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM = WallClock { System.currentTimeMillis() }
    }
}
