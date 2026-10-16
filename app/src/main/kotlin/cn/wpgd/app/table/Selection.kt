package cn.wpgd.app.table

/**
 * The selected cards of the hand, by card id (GDN-074, GDN-075). Immutable; every operation returns
 * a new value. Ids, not positions, so sorting and regrouping never change what is selected.
 *
 * Drag selection: the first card touched decides the mode (add if it was not selected, otherwise
 * remove); every other card the finger passes over gets that mode exactly once per drag, so moving
 * back and forth never toggles a card twice. Only cards the UI reports as passed over change.
 */
data class HandSelection(val ids: Set<String> = emptySet(), val drag: Drag? = null) {
    data class Drag(val adding: Boolean, val seen: Set<String>)

    fun tap(id: String, hand: Set<String>): HandSelection {
        if (id !in hand) return this
        return copy(ids = if (id in ids) ids - id else ids + id)
    }

    fun dragStart(id: String, hand: Set<String>): HandSelection {
        if (id !in hand) return copy(drag = null)
        val adding = id !in ids
        return HandSelection(apply(ids, id, adding), Drag(adding, setOf(id)))
    }

    fun dragOver(id: String, hand: Set<String>): HandSelection {
        val d = drag ?: return this
        if (id !in hand || id in d.seen) return this
        return HandSelection(apply(ids, id, d.adding), d.copy(seen = d.seen + id))
    }

    fun dragEnd(): HandSelection = copy(drag = null)

    /** Long press: adds a whole column. */
    fun addAll(group: Collection<String>, hand: Set<String>): HandSelection = copy(ids = ids + group.filter { it in hand })

    fun clear(): HandSelection = HandSelection()

    /** Keeps only cards still in the hand (after a play or a tribute moved cards). */
    fun prunedTo(hand: Set<String>): HandSelection =
        if (ids.all { it in hand }) this else copy(ids = ids.filterTo(LinkedHashSet()) { it in hand })

    private fun apply(set: Set<String>, id: String, adding: Boolean) = if (adding) set + id else set - id
}
