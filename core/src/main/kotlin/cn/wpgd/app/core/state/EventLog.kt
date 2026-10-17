package cn.wpgd.app.core.state

/**
 * In-memory, append-only log of the events of accepted commands (WI-03; persisting it is WI-06).
 * Each appended batch is one command's events: one revision, exactly one above the last batch.
 *
 * The log keeps its own read-only copy of every event's lists, so neither the caller that appended
 * an event nor a reader of [events] can change logged history.
 */
class EventLog {
    private val entries = ArrayList<DealEvent>()

    val events: List<DealEvent> get() = ReadOnlyList(entries.toList())

    val lastRevision: Long get() = entries.lastOrNull()?.revision ?: 0

    fun append(batch: List<DealEvent>) {
        require(batch.isNotEmpty()) { "an accepted command emits at least one event" }
        val revision = batch.first().revision
        require(batch.all { it.revision == revision }) { "one batch, one revision" }
        require(revision == lastRevision + 1) { "batch revision $revision does not follow $lastRevision" }
        batch.mapTo(entries, ::snapshot)
    }

    /** The state reached by applying every logged event to [initial] (a fresh [DealReducer.newDeal]). */
    fun replay(initial: DealState): DealState = DealEvents.replay(initial, entries)

    /** A list no caller can change, even through a cast to a mutable type (stdlib only: `:core` stays multiplatform). */
    private class ReadOnlyList<T>(private val items: List<T>) : AbstractList<T>() {
        override val size: Int get() = items.size
        override fun get(index: Int): T = items[index]
    }

    private companion object {
        fun <T> frozen(list: List<T>): List<T> = ReadOnlyList(list.toList())

        /** [e] with every list, nested ones included, replaced by a read-only copy. The other fields are immutable values. */
        fun snapshot(e: DealEvent): DealEvent = when (e) {
            is DealEvent.HandDealt -> e.copy(cardIds = frozen(e.cardIds))
            is DealEvent.Played -> e.copy(
                cardIds = frozen(e.cardIds),
                declaration = e.declaration?.let(::frozen),
                // The combination is part of the logged play: its lists are copied too.
                combination = e.combination.copy(sequence = frozen(e.combination.sequence), resolvedRanks = frozen(e.combination.resolvedRanks)),
            )
            is DealEvent.DealEnded -> e.copy(finishOrder = frozen(e.finishOrder), unfinished = frozen(e.unfinished))
            is DealEvent.DealOpened, is DealEvent.Dealt, is DealEvent.CardMoved, is DealEvent.PlayBegan,
            is DealEvent.Passed, is DealEvent.SeatFinished, is DealEvent.TrickEnded, is DealEvent.TurnMoved,
            -> e
        }
    }
}
