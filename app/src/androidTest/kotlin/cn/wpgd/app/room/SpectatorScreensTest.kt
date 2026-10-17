package cn.wpgd.app.room

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.wire.GrantStatus
import cn.wpgd.app.table.EffectSink
import cn.wpgd.app.table.WakeLockSink
import cn.wpgd.app.ui.OpenTableRoute
import cn.wpgd.app.ui.WpgdTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G1–G6 on the watcher's screen with real owners over loopback sockets: the public table, a request
 * only to a human seat, the read-only hand after consent, and its disappearance from the screen the
 * moment the player takes consent back.
 */
@RunWith(AndroidJUnit4::class)
class SpectatorScreensTest {
    @get:Rule val rule = createComposeRule()
    private val rig = DeviceRoomRig()

    @After fun tearDown() = rig.shutdown()

    private fun has(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun aWatcherSeesOneHandOnlyWithConsent() {
        val host = rig.host()
        val zhou = rig.join("老周", host, Seat.EAST)
        val chen = rig.join("陈阿姨", host, seat = null)
        rig.start(host, listOf(zhou))
        val vm = rig.vm(chen)
        rule.setContent { WpgdTheme { OpenTableRoute(chen, { vm }, EffectSink { }, WakeLockSink { }, onLeave = {}, onFindAgain = {}) } }
        rule.waitUntil(10_000) { has("watch") && has("watch:seat:EAST") }

        // G1: four seats, the trick and the turn; asking is offered for the humans only.
        assertTrue(has("watch:ask:EAST") && has("watch:ask:SOUTH"))
        assertFalse(has("watch:ask:NORTH") || has("watch:ask:WEST"))
        assertFalse("no hand before consent", has("watch:hand"))
        assertFalse("a watcher has no game control", has("button:play") || has("button:pass") || has("button:tribute"))

        rule.onNodeWithTag("watch:ask:EAST").performScrollTo().performClick()
        rule.waitUntil(10_000) { has("watch:waiting") }
        waitFor { zhou.state.value.table?.grants?.any { it.status == GrantStatus.PENDING } == true }
        val grant = zhou.state.value.table!!.grants.single { it.status == GrantStatus.PENDING }.grantId
        zhou.answerFollow(grant, approve = true)
        rule.waitUntil(10_000) { has("watch:follow") && has("watch:hand") }

        zhou.stopFollow(grant)
        rule.waitUntil(10_000) { !has("watch:hand") && has("watch:notice") }
        assertFalse(has("watch:follow"))
        rule.onNodeWithTag("watch:dismiss").performScrollTo().performClick()
        rule.waitUntil(5_000) { !has("watch:notice") }
    }
}
