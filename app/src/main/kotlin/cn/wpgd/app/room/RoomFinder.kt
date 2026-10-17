package cn.wpgd.app.room

import cn.wpgd.app.session.transport.Cancellable
import cn.wpgd.app.session.transport.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The room list as the join screen shows it (C1–C3). */
data class FinderState(
    val searching: Boolean,
    /** Rooms found, in a stable order (table name, then code), never auto-joined. */
    val rooms: List<RoomAdvert>,
    /** Nothing was found within the search window (C3). */
    val noneFound: Boolean,
) {
    companion object {
        val IDLE = FinderState(searching = false, rooms = emptyList(), noneFound = false)
    }
}

/**
 * Searches for nearby rooms (GDN-009). Results stream in as found; after [noneFoundMs] with no
 * result the state says so (C3) while the search keeps running. Browser callbacks may arrive on any
 * thread; state changes go through [scheduler] (one thread).
 */
class RoomFinder(
    private val browser: () -> RoomBrowser,
    private val scheduler: Scheduler,
    private val noneFoundMs: Long = 8_000,
) {
    private val flow = MutableStateFlow(FinderState.IDLE)
    val state: StateFlow<FinderState> = flow.asStateFlow()

    private var active: RoomBrowser? = null
    private var timer: Cancellable? = null
    private var generation = 0L

    /** The current search's window ([noneFoundMs]) has elapsed: an empty list now means none found (C3). */
    private var windowElapsed = false

    /** Starts (or restarts) a search. */
    @Synchronized
    fun start() {
        stopLocked()
        val gen = ++generation
        windowElapsed = false
        flow.value = FinderState(searching = true, rooms = emptyList(), noneFound = false)
        val b = browser()
        active = b
        timer = scheduler.schedule(noneFoundMs) { onTimeout(gen) }
        b.start { rooms -> scheduler.schedule(0) { onRooms(gen, rooms) } }
    }

    @Synchronized
    fun stop() {
        stopLocked()
        flow.value = FinderState.IDLE
    }

    private fun stopLocked() {
        generation++
        timer?.cancel()
        timer = null
        active?.stop()
        active = null
    }

    @Synchronized
    private fun onRooms(gen: Long, rooms: List<RoomAdvert>) {
        if (gen != generation) return
        val sorted = rooms.distinctBy { it.roomId }.sortedWith(compareBy({ it.tableName }, { it.tableCode }, { it.roomId }))
        // Rooms that disappear after the window elapsed leave the list empty: say none found again.
        flow.update { it.copy(rooms = sorted, noneFound = windowElapsed && sorted.isEmpty()) }
    }

    @Synchronized
    private fun onTimeout(gen: Long) {
        if (gen != generation) return
        windowElapsed = true
        flow.update { it.copy(noneFound = it.rooms.isEmpty()) }
    }
}
