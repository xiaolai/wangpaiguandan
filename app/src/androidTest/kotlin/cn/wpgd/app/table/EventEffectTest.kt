package cn.wpgd.app.table

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.DeviceSolo
import cn.wpgd.app.ScreenUnderTest
import cn.wpgd.app.SimpleHuman
import cn.wpgd.app.TableFixtures
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.deviceView
import cn.wpgd.app.owner.ConnectionState
import cn.wpgd.app.owner.RoomCondition
import cn.wpgd.app.showTable
import cn.wpgd.app.ui.TableRoute
import cn.wpgd.app.ui.WpgdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * GDN-080: cues are keyed to event ids. A resync (rejoin over a new link) and the screen leaving
 * and re-entering composition (as on activity recreation) replay no cue; reduced motion shows no
 * bomb flash, only a static highlight.
 */
@RunWith(AndroidJUnit4::class)
class EventEffectTest {
    @get:Rule val rule = createComposeRule()

    private class RecordingSink : EffectSink {
        val outputs: MutableList<EffectOutput> = Collections.synchronizedList(ArrayList())
        override fun perform(output: EffectOutput) {
            outputs += output
        }
        fun keys() = synchronized(outputs) { outputs.map { it.cueId + "/" + it::class.simpleName } }
    }

    /** Revision a cue id was first seen at (`e<epoch>:r<revision>:…`); null for rejection cues. */
    private fun revisionOf(cueId: String): Long? = Regex(""":r(\d+):""").find(cueId)?.groupValues?.get(1)?.toLong()

    private fun playFor(solo: DeviceSolo, ms: Long, until: () -> Boolean = { false }) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end && !until()) {
            rule.runOnUiThread { SimpleHuman.actThroughViewModel(solo.vm) }
            Thread.sleep(50)
        }
    }

    @Test fun aResyncAndARecompositionReplayNoCue() {
        val solo = DeviceSolo().start()
        val sink = RecordingSink()
        var shown by mutableStateOf(true)
        rule.setContent { WpgdTheme { if (shown) TableRoute(solo.vm, sink, WakeLockSink { }, onLeave = {}) } }
        try {
            playFor(solo, 60_000) { sink.outputs.count { it is EffectOutput.Sound || it is EffectOutput.Announce } >= 12 }
            assertTrue("no cues reached the screen: ${sink.keys()}", sink.outputs.size >= 12)

            // Quiet the table, then take the snapshot every later cue must be newer than.
            solo.owner.pause()
            rule.waitUntil(5_000) { solo.owner.state.value.table?.room == RoomCondition.PAUSED }
            Thread.sleep(300)
            rule.waitForIdle()
            val before = sink.keys().size
            val revision = solo.owner.state.value.table!!.revision

            solo.owner.reconnect()
            rule.waitUntil(10_000) { solo.owner.metrics.connections.get() == 2L && solo.owner.state.value.table?.connection == ConnectionState.CONNECTED }
            rule.runOnIdle { shown = false }
            rule.waitForIdle()
            rule.runOnIdle { shown = true }
            rule.waitForIdle()
            Thread.sleep(300)
            rule.waitForIdle()
            assertEquals("the rejoin or the recomposition replayed cues: ${sink.keys().drop(before)}", before, sink.keys().size)

            solo.owner.resume()
            playFor(solo, 4_000)
            rule.waitForIdle()
            val keys = sink.keys()
            assertEquals("a cue was performed twice: $keys", keys.size, keys.toSet().size)
            val late = synchronized(sink.outputs) { sink.outputs.drop(before).mapNotNull { revisionOf(it.cueId) } }
            assertTrue("cues at or below the resync revision $revision came back: $late", late.all { it > revision })
        } finally {
            solo.owner.shutdown()
        }
    }

    private fun bomb(reducedMotion: Boolean): RecordingSink {
        val screen = ScreenUnderTest(deviceView(TableFixtures.RUN_HAND))
        screen.preferences.update { it.copy(reducedMotion = reducedMotion) }
        val sink = RecordingSink()
        rule.showTable(screen.vm, effects = sink)
        rule.waitUntil(5_000) { screen.session.cueFlow.subscriptionCount.value > 0 }
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { screen.session.cueFlow.tryEmit(TableCue("e1:r11:BOMB:WEST:0", CueKind.BOMB, Seat.WEST, 11)) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(100)
        return sink
    }

    @Test fun reducedMotionShowsAStaticHighlightAndNoFlash() {
        val sink = bomb(reducedMotion = true)
        rule.onNodeWithTag("flash", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("highlight", useUnmergedTree = true).assertExists()
        assertTrue(sink.outputs.none { it is EffectOutput.Flash || it is EffectOutput.Motion })
        rule.mainClock.advanceTimeBy(5_000)
        rule.onNodeWithTag("flash", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun fullMotionFlashesOnceForABomb() {
        val sink = bomb(reducedMotion = false)
        rule.onNodeWithTag("flash", useUnmergedTree = true).assertExists()
        assertEquals(1, sink.outputs.count { it is EffectOutput.Flash })
        rule.mainClock.advanceTimeBy(5_000)
        rule.onNodeWithTag("flash", useUnmergedTree = true).assertDoesNotExist()
    }
}
