package cn.wpgd.app.table

import cn.wpgd.app.FakeSession
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.playingView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GDN-080: a sound or haptic is performed once per cue id of one table, and a later table whose
 * cue ids repeat the earlier table's (a new host starts at epoch 1) still gets its sound and haptics.
 */
class CueGuardTest {
    private val bomb = TableCue("e1:r11:BOMB:WEST:0", CueKind.BOMB, Seat.WEST, 11)

    /** Opens a table on [sink] (the activity's one sink) and emits [cues] on it. */
    private fun table(sink: MutableList<EffectOutput>, vararg cues: TableCue) {
        val session = FakeSession(playingView(hand = listOf("0S3")))
        val prefs = PreferencesRepository(InMemoryKeyValueStore()).also { it.update { p -> p.copy(sound = true, vibration = true) } }
        val vm = TableViewModel(session, prefs, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).launch { vm.effects.collect { sink += it } }
        cues.forEach { check(session.cueFlow.tryEmit(it)) }
    }

    private fun List<EffectOutput>.platform() = count { it is EffectOutput.Sound || it is EffectOutput.Haptic }

    @Test fun aRepeatedCueIdOfOneTableIsPerformedOnce() {
        val sink = ArrayList<EffectOutput>()
        table(sink, bomb, bomb)
        assertEquals(2, sink.platform()) // one sound and one haptic
    }

    @Test fun aLaterTableReusingCueIdsStillGetsSoundAndHaptics() {
        val sink = ArrayList<EffectOutput>()
        table(sink, bomb)
        table(sink, bomb)
        assertEquals(4, sink.platform())
    }
}
