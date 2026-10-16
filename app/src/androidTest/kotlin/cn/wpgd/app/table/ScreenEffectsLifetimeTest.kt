package cn.wpgd.app.table

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.R
import cn.wpgd.app.ScreenUnderTest
import cn.wpgd.app.TableFixtures
import cn.wpgd.app.allNodes
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.team
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.deviceView
import cn.wpgd.app.owner.ConnectionState
import cn.wpgd.app.owner.RoomCondition
import cn.wpgd.app.owner.RoomSession
import cn.wpgd.app.room.WatchSeatUi
import cn.wpgd.app.room.WatchUiState
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.str
import cn.wpgd.app.ui.TableActions
import cn.wpgd.app.ui.TableEffectsState
import cn.wpgd.app.ui.TableRoute
import cn.wpgd.app.ui.WatchScreen
import cn.wpgd.app.ui.WpgdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.util.Collections
import cn.wpgd.app.ui.TableScreen as TableScreenUi

/**
 * Round-3 audit, screen side: the wildcard readings lock while a play is pending; a finished card
 * slide is not kept to replay; no cue is performed while the screen is stopped; a watcher's seat
 * names a finish place once.
 */
@RunWith(AndroidJUnit4::class)
class ScreenEffectsLifetimeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun readingsCannotBeChangedWhileThePlayIsPending() {
        val selection = listOf("0H2", "0S5", "0S6", "0S7", "0S8")
        val screen = ScreenUnderTest(deviceView(selection + listOf("0D9", "1CK")))
        rule.setContent { WpgdTheme { TableRoute(screen.vm, EffectSink { }, WakeLockSink { }, onLeave = {}) } }
        rule.waitForIdle()
        selection.forEach { rule.onNodeWithTag("card:$it", useUnmergedTree = true).performScrollTo().performClick() }
        rule.waitForIdle()
        val preview = screen.vm.state().preview as PlayPreview.Options
        val pick = preview.options.first()
        val other = preview.options.last()
        rule.onNodeWithTag("option:${other.key}", useUnmergedTree = true).assertIsEnabled()
        rule.onNodeWithTag("option:${pick.key}", useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("button:play").performClick()
        rule.waitForIdle()
        assertTrue(screen.vm.state().pending)
        rule.onNodeWithTag("option:${other.key}", useUnmergedTree = true).assertIsNotEnabled()
        assertEquals(pick.key, (screen.vm.state().preview as PlayPreview.Options).chosenKey)
    }

    @Test fun aFinishedSlideIsClearedSoNoLaterTrickAreaReplaysIt() {
        val screen = ScreenUnderTest(deviceView(TableFixtures.RUN_HAND))
        val fx = TableEffectsState().apply { motion = EffectOutput.Motion("e1:r11:PLAY:WEST:0", Seat.WEST) }
        rule.setContent { WpgdTheme { TableScreenUi(screen.vm.state(), screen.vm.rulesSummary, fx, TableActions(screen.vm, {}), menuOpen = false, onMenu = {}) } }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertNull("the completed slide is still pending, so a recreated trick area would replay it", fx.motion)
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun noCueIsPerformedWhileTheScreenIsStopped() {
        val screen = ScreenUnderTest(deviceView(TableFixtures.RUN_HAND))
        screen.preferences.update { it.copy(sound = true, vibration = true) }
        val performed: MutableList<EffectOutput> = Collections.synchronizedList(ArrayList())
        val owner = Owner()
        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                WpgdTheme { TableRoute(screen.vm, EffectSink { performed += it }, WakeLockSink { }, onLeave = {}) }
            }
        }
        rule.waitUntil(5_000) { screen.session.cueFlow.subscriptionCount.value > 0 }

        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        rule.waitUntil(5_000) { screen.session.cueFlow.subscriptionCount.value == 0 }
        rule.runOnIdle { screen.session.cueFlow.tryEmit(TableCue("e1:r11:BOMB:WEST:0", CueKind.BOMB, Seat.WEST, 11)) }
        rule.waitForIdle()
        assertTrue("cues were performed while stopped: $performed", performed.isEmpty())

        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        rule.waitUntil(5_000) { screen.session.cueFlow.subscriptionCount.value > 0 }
        rule.runOnIdle { screen.session.cueFlow.tryEmit(TableCue("e1:r12:YOUR_TURN:SOUTH:0", CueKind.YOUR_TURN, Seat.SOUTH, 12)) }
        rule.waitUntil(5_000) { performed.any { it is EffectOutput.Sound } }
    }

    @Test fun aWatcherSeesAFinishPlaceOnce() {
        val seats = Seat.entries.map { s ->
            val done = s == Seat.EAST
            WatchSeatUi(s, s.team, "阿明", Occupant.HUMAN, false, if (done) SeatLastAction.Finished(1) else SeatLastAction.None, if (done) 1 else null, null, false)
        }
        val w = WatchUiState(
            null, MatchPhase.PLAYING, 1, 2, emptyMap(), seats, TrickDisplay.EMPTY, TurnClarity.NONE, null, null, null, null,
            false, false, emptyList(), null, ConnectionState.CONNECTED, RoomCondition.ACTIVE,
        )
        val session = Proxy.newProxyInstance(RoomSession::class.java.classLoader, arrayOf(RoomSession::class.java)) { _, _, _ -> null } as RoomSession
        rule.setContent { WpgdTheme { WatchScreen(w, session, onDismissNotice = {}, onLeave = {}) } }
        rule.waitForIdle()
        val place = str(R.string.seat_finished, 1)
        val summary = rule.allNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("") }
            .single { place in it }
        assertEquals("the place is named twice: $summary", 1, summary.windowed(place.length).count { it == place })
    }
}
