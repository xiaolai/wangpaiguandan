package cn.wpgd.app.ui

import cn.wpgd.app.FakeSession
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.playingView
import cn.wpgd.app.table.EffectOutput
import cn.wpgd.app.table.TableViewModel
import cn.wpgd.app.table.WakeLockPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The table route's own decisions, outside a composition. */
class TableRouteLogicTest {
    /** Audit-fix #51: system reduced motion turns card motion into the static highlight, like the preference does. */
    @Test fun systemReducedMotionHighlightsAPlay() {
        val fx = TableEffectsState()
        fx.show(EffectOutput.Motion("c1", Seat.EAST), systemReducedMotion = true)
        assertNull(fx.motion)
        assertEquals(EffectOutput.Highlight("c1", Seat.EAST), fx.highlight)
        fx.show(EffectOutput.Motion("c2", Seat.EAST), systemReducedMotion = false)
        assertEquals(EffectOutput.Motion("c2", Seat.EAST), fx.motion)
    }

    /** Round 3 #31: a motion cue starts once; the trick area of the other layout (a resize mid-animation) does not replay it. */
    @Test fun aMotionCueStartsOnceAcrossLayouts() {
        val fx = TableEffectsState()
        val m = EffectOutput.Motion("c1", Seat.EAST)
        fx.show(m, systemReducedMotion = false)
        assertTrue("the first trick area plays it", fx.startMotion(m))
        assertFalse("a trick area composed after a resize does not", fx.startMotion(m))
        assertTrue("the next cue plays", fx.startMotion(EffectOutput.Motion("c2", Seat.EAST)))
    }

    /** Audit-fix #52: the open menu hides the table, so the wake policy releases the screen. */
    @Test fun theOpenMenuReleasesTheWakeLock() {
        val vm = TableViewModel(FakeSession(playingView(hand = listOf("0S3"))), PreferencesRepository(InMemoryKeyValueStore()), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val ui = vm.state()
        assertEquals(MatchPhase.PLAYING, ui.phase)
        assertTrue(WakeLockPolicy.shouldHold(wakeInputs(ui, menuOpen = false)))
        assertFalse(WakeLockPolicy.shouldHold(wakeInputs(ui, menuOpen = true)))
    }

    /** Audit-fix #40: a failing diagnostics export is a result the screen shows with a retry, not a crash. */
    @Test fun aFailedDiagnosticsExportIsNull() {
        assertNull(runBlocking { buildDiagnostics({ error("loop stalled") }, Dispatchers.Unconfined) })
        assertEquals("ok", runBlocking { buildDiagnostics({ "ok" }, Dispatchers.Unconfined) })
    }
}
