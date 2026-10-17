package cn.wpgd.app.data

import cn.wpgd.app.table.HandSort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Hand layouts. Only A (two rows by rank, the design default) exists until the HP-7 decision on B. */
enum class HandLayout { A }

/**
 * Local preferences, changeable at the table without touching the session (GDN-081).
 * [reducedMotion] removes bomb flashes and card motion (GDN-080); the UI also treats a system
 * animator scale of zero as reduced motion. [vibration] is opt-in (off until the player turns it
 * on). [fourColorSuits] draws clubs green and diamonds blue (state L9); suits keep their glyphs, so
 * colour is never the only cue.
 */
data class TablePreferences(
    val sound: Boolean = true,
    val vibration: Boolean = false,
    val reducedMotion: Boolean = false,
    val handSort: HandSort = HandSort.RANK,
    val handLayout: HandLayout = HandLayout.A,
    val largeCards: Boolean = false,
    val fourColorSuits: Boolean = false,
)

/** Persists [TablePreferences] in its own store. Unknown or missing values read as the default. */
class PreferencesRepository(private val store: KeyValueStore) {
    private val flow = MutableStateFlow(read())

    val preferences: StateFlow<TablePreferences> = flow.asStateFlow()

    @Synchronized
    fun update(change: (TablePreferences) -> TablePreferences) {
        val next = change(flow.value)
        write(next)
        flow.value = next
    }

    private fun read(): TablePreferences {
        val d = TablePreferences()
        return TablePreferences(
            sound = bool(SOUND, d.sound),
            vibration = bool(VIBRATION, d.vibration),
            reducedMotion = bool(REDUCED_MOTION, d.reducedMotion),
            handSort = store.get(SORT)?.let { v -> HandSort.entries.firstOrNull { it.name == v } } ?: d.handSort,
            handLayout = store.get(LAYOUT)?.let { v -> HandLayout.entries.firstOrNull { it.name == v } } ?: d.handLayout,
            largeCards = bool(LARGE_CARDS, d.largeCards),
            fourColorSuits = bool(FOUR_COLOR, d.fourColorSuits),
        )
    }

    private fun bool(key: String, default: Boolean) = when (store.get(key)) {
        "true" -> true
        "false" -> false
        else -> default
    }

    private fun write(p: TablePreferences) {
        store.put(SOUND, p.sound.toString())
        store.put(VIBRATION, p.vibration.toString())
        store.put(REDUCED_MOTION, p.reducedMotion.toString())
        store.put(SORT, p.handSort.name)
        store.put(LAYOUT, p.handLayout.name)
        store.put(LARGE_CARDS, p.largeCards.toString())
        store.put(FOUR_COLOR, p.fourColorSuits.toString())
    }

    private companion object {
        const val SOUND = "sound"
        const val VIBRATION = "vibration"
        const val REDUCED_MOTION = "reduced_motion"
        const val SORT = "hand_sort"
        const val LAYOUT = "hand_layout"
        const val LARGE_CARDS = "large_cards"
        const val FOUR_COLOR = "four_color_suits"
    }
}
