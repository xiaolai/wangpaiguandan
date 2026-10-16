package cn.wpgd.app.table

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.owner.RoomCondition

/** What the wake decision depends on. */
data class WakeInputs(
    /** This device's member holds a playing seat (spectators never keep the screen on). */
    val seated: Boolean,
    val phase: MatchPhase?,
    val room: RoomCondition,
    /** The table screen is in front (not the menu or settings home). */
    val tableVisible: Boolean,
)

/**
 * Keep the screen awake only while seated and playing (GDN-100): a seated member, the table on
 * screen, an active room, and a deal in progress. The menu, a finished match, a paused or ended
 * room, the result between deals and spectating all release it. Implemented on Android by the
 * window's keep-screen-on flag (no WAKE_LOCK permission).
 */
object WakeLockPolicy {
    private val PLAYING = setOf(MatchPhase.DEALT, MatchPhase.TRIBUTE, MatchPhase.RETURN, MatchPhase.READY, MatchPhase.PLAYING, MatchPhase.DEAL_ENDED)

    fun shouldHold(i: WakeInputs): Boolean = i.seated && i.tableVisible && i.room == RoomCondition.ACTIVE && i.phase in PLAYING
}

fun interface WakeLockSink {
    fun setHeld(held: Boolean)
}

/** Applies [WakeLockPolicy] to a [WakeLockSink], calling it only when the decision changes. */
class WakeLockController(private val sink: WakeLockSink) {
    var held: Boolean = false
        private set

    fun update(inputs: WakeInputs) {
        val next = WakeLockPolicy.shouldHold(inputs)
        if (next != held) {
            held = next
            sink.setHeld(next)
        }
    }

    /** Releases on exit (screen closed, owner shut down). */
    fun release() {
        if (held) {
            held = false
            sink.setHeld(false)
        }
    }
}
