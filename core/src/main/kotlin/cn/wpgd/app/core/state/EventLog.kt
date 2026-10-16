package cn.wpgd.app.core.state

/**
 * In-memory, append-only log of the events of accepted commands (WI-03; persisting it is WI-06).
 * Each appended batch is one command's events: one revision, exactly one above the last batch.
 */
class EventLog {
    private val entries = ArrayList<DealEvent>()

    val events: List<DealEvent> get() = entries.toList()

    val lastRevision: Long get() = entries.lastOrNull()?.revision ?: 0

    fun append(batch: List<DealEvent>) {
        require(batch.isNotEmpty()) { "an accepted command emits at least one event" }
        val revision = batch.first().revision
        require(batch.all { it.revision == revision }) { "one batch, one revision" }
        require(revision == lastRevision + 1) { "batch revision $revision does not follow $lastRevision" }
        entries += batch
    }

    /** The state reached by applying every logged event to [initial] (a fresh [DealReducer.newDeal]). */
    fun replay(initial: DealState): DealState = DealEvents.replay(initial, entries)
}
