package cn.wpgd.app.room

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.R
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.str
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
 * GDN-016 on the host's screen, mid-match: a newcomer asks to take over an away player's seat; the
 * host sees the request at the table with the code and what it means, approves, and the newcomer
 * holds the seat with its cards. Real owners over loopback sockets.
 */
@RunWith(AndroidJUnit4::class)
class ReplacementApprovalUiTest {
    @get:Rule val rule = createComposeRule()
    private val rig = DeviceRoomRig()

    @After fun tearDown() = rig.shutdown()

    private fun texts(tag: String): String = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().flatMap { it.allText() }.joinToString(" ")

    @Test fun theHostApprovesATakeoverAtTheTable() {
        val host = rig.host(settings = RoomSettings("周末掼蛋", graceSeconds = 60))
        val zhou = rig.join("老周", host, Seat.EAST)
        rig.start(host, listOf(zhou))
        val hand = zhou.state.value.table!!.view!!.deal!!.hand.map { it.id }.toSet()
        val vm = rig.vm(host)
        rule.setContent { WpgdTheme { OpenTableRoute(host, { vm }, EffectSink { }, WakeLockSink { }, onLeave = {}, onFindAgain = {}) } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("table").fetchSemanticsNodes().isNotEmpty() }

        zhou.shutdown()
        waitFor(what = { "${rig.advertOf(host)}" }) { rig.advertOf(host)?.replaceableSeats == setOf(Seat.EAST) }
        val lin = rig.owner()
        assertTrue(lin.joinRoom(checkNotNull(rig.advertOf(host)), Role.PLAYER, Seat.EAST, nick("小林"), replace = true))
        waitFor { lin.state.value.table?.join?.code != null }
        val code = lin.state.value.table!!.join!!.code!!

        rule.waitUntil(10_000) { rule.onAllNodesWithTag("admit:dialog").fetchSemanticsNodes().isNotEmpty() }
        val dialog = texts("admit:dialog")
        assertTrue(dialog, dialog.contains(code) && dialog.contains("小林") && dialog.contains(str(R.string.admit_replace, str(R.string.seat_name_east))))
        rule.onNodeWithTag("admit:yes").performClick() // the fixed banner area at the table: no scroll container
        waitFor { lin.state.value.joinPhase == JoinPhase.ADMITTED && lin.state.value.table?.view?.deal != null }
        assertEquals(Seat.EAST, lin.state.value.table!!.seat)
        assertEquals(hand, lin.state.value.table!!.view!!.deal!!.hand.map { it.id }.toSet())
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("admit:dialog").fetchSemanticsNodes().isEmpty() }
    }
}
