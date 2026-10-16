package cn.wpgd.app.table

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.DeviceProfiles
import cn.wpgd.app.R
import cn.wpgd.app.str
import cn.wpgd.app.ScreenUnderTest
import cn.wpgd.app.TableFixtures
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.room.allText
import cn.wpgd.app.owner.HostSide
import cn.wpgd.app.owner.RoomInfo
import cn.wpgd.app.owner.TableKind
import cn.wpgd.app.room.LeavePrompt
import cn.wpgd.app.room.RecordingRoomSession
import cn.wpgd.app.session.host.RoomPolicy
import cn.wpgd.app.session.wire.AbsenceMode
import cn.wpgd.app.session.wire.GrantStatus
import cn.wpgd.app.session.wire.GrantView
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.session.wire.RoomSettingsView
import cn.wpgd.app.session.wire.RoomView
import cn.wpgd.app.session.wire.RosterSeat
import cn.wpgd.app.ui.TableRoute
import cn.wpgd.app.ui.WpgdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The table's room parts on screen: T6 (a player away, with the grace left), T7 (the AI stands in),
 * T9 (a watcher's request, never on this player's own turn, G3), T10 (who watches and who sees this
 * hand, with revoke), H1 (the host lets the AI stand in now) and H9 (leaving explains suspend and end).
 */
@RunWith(AndroidJUnit4::class)
class RoomTablePartsTest {
    @get:Rule val rule = createComposeRule()

    private val settings = RoomSettingsView(RoomPolicy.FRIENDLY.view(), 30_000, AbsenceMode.SUBSTITUTE, AbsenceMode.SUBSTITUTE)

    private fun room(east: Occupant, eastGraceMs: Long?, observers: List<String>) = RoomView(
        lobby = false,
        paused = false,
        settings = settings,
        settingsRevision = 1,
        seats = listOf(
            RosterSeat(Seat.SOUTH, Occupant.HUMAN, "阿梅", true, null),
            RosterSeat(Seat.EAST, east, "老周", true, eastGraceMs),
            RosterSeat(Seat.NORTH, Occupant.AI, null, false, null),
            RosterSeat(Seat.WEST, Occupant.STAND_IN, "小林", false, null),
        ),
        observers = observers,
        yourSeat = Seat.SOUTH,
    )

    /** SOUTH's screen in a hosted room; [turn] decides whether it is this player's turn. */
    private fun screen(turn: Seat, grants: List<GrantView>, east: Occupant = Occupant.ABSENT): RecordingRoomSession {
        val s = ScreenUnderTest(TableFixtures.playingView(DeviceProfiles.profiles, hand = listOf("0S5", "0H7", "1C9"), turn = turn))
        s.session.update {
            it.copy(
                kind = TableKind.HOSTED,
                roomView = room(east, 25_000L.takeIf { east == Occupant.ABSENT }, listOf("陈阿姨", "王叔")),
                grants = grants,
                info = RoomInfo("t1", "周末掼蛋", "1234", "阿梅"),
                hostSide = HostSide(emptyList(), emptyList(), canStandIn = true, saved = true, canRematch = false),
            )
        }
        val room = RecordingRoomSession(s.session, LeavePrompt("t1", saved = true, retentionMs = 12 * 3_600_000L, expiresAt = System.currentTimeMillis() + 12 * 3_600_000L))
        rule.setContent { WpgdTheme { TableRoute(s.vm, EffectSink { }, WakeLockSink { }, onLeave = {}, room = room) } }
        rule.waitForIdle()
        return room
    }

    private fun has(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun text(tag: String): String = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().flatMap { it.allText() }.joinToString(" ")

    @Test fun awayAndStandInSeatsAndTheHostsStandIn() {
        val room = screen(turn = Seat.NORTH, grants = emptyList())
        // A seat is one node for a screen reader: its description carries the same words as the panel.
        assertTrue("T6: away with the grace left", text("seat:EAST").contains(str(R.string.seat_presence_away, 25)))
        assertTrue("T6: the player's name", text("seat:EAST").contains("老周"))
        assertTrue("T7: the AI stands in", text("seat:WEST").contains(str(R.string.seat_presence_stand_in)))
        assertTrue("H1", has("host:absent:EAST"))
        rule.onNodeWithTag("host:standin:EAST").performClick()
        assertEquals(listOf("standIn:EAST"), room.calls)
    }

    @Test fun aWatchersRequestIsAnsweredOnlyOffTurn() {
        val pending = listOf(GrantView("g1", "陈阿姨", Seat.SOUTH, GrantStatus.PENDING, 1))
        val room = screen(turn = Seat.NORTH, grants = pending, east = Occupant.HUMAN)
        assertTrue("T9", has("follow:request"))
        assertTrue(text("follow:request").contains("陈阿姨"))
        rule.onNodeWithTag("follow:allow", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(listOf("answer:g1:true"), room.calls)
    }

    @Test fun noRequestInterruptsThePlayersOwnTurn() {
        screen(turn = Seat.SOUTH, grants = listOf(GrantView("g1", "陈阿姨", Seat.SOUTH, GrantStatus.PENDING, 1)), east = Occupant.HUMAN)
        assertFalse("G3: shown after the player's action, not during it", has("follow:request"))
    }

    @Test fun theMenuListsWatchersAndWhoSeesThisHand() {
        val active = listOf(GrantView("g2", "陈阿姨", Seat.SOUTH, GrantStatus.ACTIVE, 2))
        val room = screen(turn = Seat.NORTH, grants = active, east = Occupant.HUMAN)
        rule.onNodeWithTag("button:menu").performClick()
        rule.waitUntil(5_000) { has("menu:watchers") }
        assertTrue(has("watcher:陈阿姨") && has("watcher:王叔"))
        assertTrue("T10: who sees this hand is said in words", text("watcher:陈阿姨").length > text("watcher:王叔").length)
        rule.onNodeWithTag("watcher:revoke", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(listOf("stop:g2"), room.calls)
    }

    @Test fun theHostIsToldWhatSuspendAndEndMean() {
        val room = screen(turn = Seat.NORTH, grants = emptyList(), east = Occupant.HUMAN)
        rule.onNodeWithTag("button:menu").performClick()
        rule.onNodeWithTag("menu:leave").performScrollTo().performClick()
        rule.waitUntil(5_000) { has("menu:leave_consequence") }
        assertTrue("the retention is stated", text("menu:leave_consequence").contains("12"))
        assertTrue(has("menu:leave_suspend") && has("menu:leave_end"))
        rule.onNodeWithTag("menu:leave_suspend").performScrollTo().performClick()
        assertEquals(listOf("leaveHosted:Suspend"), room.calls)
    }
}
