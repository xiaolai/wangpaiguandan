package cn.wpgd.app.room

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.owner.SessionOwner
import cn.wpgd.app.table.EffectSink
import cn.wpgd.app.table.WakeLockSink
import cn.wpgd.app.ui.OpenTableRoute
import cn.wpgd.app.ui.WpgdTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B8 (PROVISIONAL G2), B7, B9, B10 on the host's screen: a joiner's request shows the six-digit code
 * the joiner sees; approving seats them, denying refuses them; the host gives empty seats to AI and
 * can start only when the lobby gate is clear. Real owners over loopback sockets.
 */
@RunWith(AndroidJUnit4::class)
class AdmissionApprovalUiTest {
    @get:Rule val rule = createComposeRule()
    private val rig = DeviceRoomRig()

    @After fun tearDown() = rig.shutdown()

    private fun show(owner: SessionOwner) {
        val vm = rig.vm(owner)
        rule.setContent { WpgdTheme { OpenTableRoute(owner, { vm }, EffectSink { }, WakeLockSink { }, onLeave = {}, onFindAgain = {}) } }
    }

    private fun texts(tag: String): String = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().flatMap { it.allText() }.joinToString(" ")

    @Test fun theHostComparesTheCodeThenApprovesOrDenies() {
        val host = rig.host()
        show(host)
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("lobby").fetchSemanticsNodes().isNotEmpty() }

        val zhou = rig.ask("老周", host, Seat.EAST)
        val code = zhou.state.value.table!!.join!!.code!!
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("admit:dialog").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("the host's screen shows the joiner's code", texts("admit:code").contains(code))
        rule.onNodeWithTag("admit:yes").performScrollTo().performClick()
        waitFor { zhou.state.value.joinPhase == JoinPhase.ADMITTED }
        rule.waitUntil(10_000) { texts("lobby:seat:EAST").contains("老周") }

        val stranger = rig.ask("陌生人", host, Seat.WEST)
        rule.waitUntil(10_000) { texts("admit:code").contains(stranger.state.value.table!!.join!!.code!!) }
        rule.onNodeWithTag("admit:no").performScrollTo().performClick()
        waitFor { stranger.state.value.joinPhase == JoinPhase.DENIED }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("admit:dialog").fetchSemanticsNodes().isEmpty() }

        // B10: the empty seats go to AI by the host's hand; the start waits for the joiner's ready.
        rule.onNodeWithTag("lobby:start").performScrollTo().assertIsNotEnabled()
        for (seat in listOf("NORTH", "WEST")) {
            rule.onNodeWithTag("lobby:seat:$seat").performScrollTo().performClick()
            rule.onNodeWithTag("seat:ai:$seat").performScrollTo().performClick()
            rule.waitUntil(10_000) { texts("lobby:seat:$seat").contains("AI") }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("lobby:blockers").fetchSemanticsNodes().isNotEmpty() }
        zhou.setReady(true)
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("lobby:blockers").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("lobby:start").performScrollTo().assertIsEnabled().performClick()
        rule.waitUntil(20_000) { rule.onAllNodesWithTag("table").fetchSemanticsNodes().isNotEmpty() }
        waitFor { zhou.state.value.table?.view?.match?.dealNumber == 1 }
        assertEquals(27, zhou.state.value.table!!.view!!.deal!!.hand.size)
    }

    @Test fun theRulesSheetShowsTheRoomsSettings() {
        val host = rig.host(settings = RoomSettings("周末掼蛋", graceSeconds = 60))
        val zhou = rig.join("老周", host, Seat.EAST)
        show(zhou)
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("lobby:ready").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("lobby:rules").performScrollTo().performClick()
        rule.waitUntil(5_000) { texts("rules:sheet").contains("60") }
        rule.onNodeWithTag("lobby:ready").performScrollTo().performClick()
        waitFor { host.state.value.table!!.roomView!!.seats.single { it.seat == Seat.EAST }.ready }
    }
}
