package cn.wpgd.app.room

import cn.wpgd.app.session.wire.PROTOCOL_VERSION
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.DeviceProfiles
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.lan.LanEndpoint
import cn.wpgd.app.owner.ExecutorSessionLoop
import cn.wpgd.app.ui.FindRoomScreen
import cn.wpgd.app.ui.WpgdTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** C4: system Back from a chosen room returns to the room list, like the page's own back button. */
@RunWith(AndroidJUnit4::class)
class FindRoomBackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val loop = ExecutorSessionLoop("finder-back-test") { throw it }
    private val directory = InProcessDirectory()
    private val local = LocalVersion.of(DeviceProfiles.profiles.base.hash, DeviceProfiles.profiles.match.hash)

    @After fun tearDown() = loop.shutdown()

    private fun has(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test fun systemBackFromAChosenRoomReturnsToTheList() {
        directory.announce(RoomAdvert("tA", "周末掼蛋", "阿梅", PROTOCOL_VERSION, PROTOCOL_VERSION, local.baseProfileHash, local.matchProfileHash, "K".repeat(43), setOf(Seat.EAST), LanEndpoint("127.0.0.1", 4000)))
        val finder = RoomFinder({ directory.browser() }, loop, 8_000)
        var exited = 0
        rule.setContent {
            WpgdTheme {
                // As in AppRoot: the route's own handler is registered first, the screen's after it.
                BackHandler { exited++ }
                FindRoomScreen(finder, local, onJoin = { _, _, _ -> }, onBack = {})
            }
        }
        rule.waitUntil(10_000) { has("find:room:tA") }
        rule.onNodeWithTag("find:room:tA").performClick()
        rule.waitUntil(5_000) { has("pow") }

        pressBack()
        assertEquals("system Back left the find screen instead of the chosen room", 0, exited)
        rule.onNodeWithTag("pow").assertDoesNotExist()
        rule.onNodeWithTag("find:room:tA").assertExists()

        pressBack()
        assertEquals(1, exited)
    }
}
