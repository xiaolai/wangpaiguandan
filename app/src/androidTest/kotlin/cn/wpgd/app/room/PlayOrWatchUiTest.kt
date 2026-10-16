package cn.wpgd.app.room

import cn.wpgd.app.session.wire.PROTOCOL_VERSION
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.DeviceProfiles
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.lan.LanEndpoint
import cn.wpgd.app.owner.ExecutorSessionLoop
import cn.wpgd.app.ui.FindRoomScreen
import cn.wpgd.app.ui.WpgdTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C1–C4, C7, C8 on screen: rooms are listed as found (name, host, code; two same-named tables are
 * told apart), nothing joins by itself; choosing one offers the open seats and watching; a full
 * table still offers watching; an incompatible one offers neither; an empty search says so.
 */
@RunWith(AndroidJUnit4::class)
class PlayOrWatchUiTest {
    @get:Rule val rule = createComposeRule()
    private val loop = ExecutorSessionLoop("finder-test") { throw it }
    private val directory = InProcessDirectory()
    private val local = LocalVersion.of(DeviceProfiles.profiles.base.hash, DeviceProfiles.profiles.match.hash)
    private val joins = ArrayList<Pair<String, Seat?>>()

    @After fun tearDown() = loop.shutdown()

    private fun advert(id: String, host: String, open: Set<Seat>, base: String = local.baseProfileHash) =
        RoomAdvert(id, "周末掼蛋", host, PROTOCOL_VERSION, PROTOCOL_VERSION, base, local.matchProfileHash, "K".repeat(43), open, LanEndpoint("127.0.0.1", 4000))

    private fun show(noneFoundMs: Long = 8_000) {
        val finder = RoomFinder({ directory.browser() }, loop, noneFoundMs)
        rule.setContent { WpgdTheme { FindRoomScreen(finder, local, onJoin = { a, s, _ -> joins += a.roomId to s }, onBack = {}) } }
    }

    private fun has(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test fun roomsAreListedAndTheChoiceIsPlayOrWatch() {
        directory.announce(advert("tA", "阿梅", setOf(Seat.EAST, Seat.WEST)))
        directory.announce(advert("tB", "Lily", emptySet()))
        directory.announce(advert("tC", "老王", setOf(Seat.EAST), base = "f".repeat(64)))
        show()
        rule.waitUntil(10_000) { has("find:room:tA") && has("find:room:tB") && has("find:room:tC") }
        assertTrue("finding never joins", joins.isEmpty())

        rule.onNodeWithTag("find:room:tA").performClick()
        rule.waitUntil(5_000) { has("pow") }
        assertTrue(has("pow:seat:EAST") && has("pow:seat:WEST") && !has("pow:seat:NORTH"))
        rule.onNodeWithTag("pow:seat:WEST").performScrollTo().performClick()
        assertEquals(listOf("tA" to Seat.WEST), joins)

        rule.onNodeWithTag("page:back").performClick()
        rule.onNodeWithTag("find:room:tB").performScrollTo().performClick()
        rule.waitUntil(5_000) { has("pow:watch") }
        assertTrue("C8: a full table offers no seat", !has("pow:seat:EAST"))
        rule.onNodeWithTag("pow:watch").performScrollTo().performClick()
        assertEquals("tB" to null, joins.last())

        rule.onNodeWithTag("page:back").performClick()
        rule.onNodeWithTag("find:room:tC").performScrollTo().performClick()
        rule.waitUntil(5_000) { has("pow:incompatible") }
        assertTrue(!has("pow:watch") && !has("pow:seat:EAST"))
    }

    @Test fun anEmptySearchSaysSo() {
        show(noneFoundMs = 500)
        rule.waitUntil(10_000) { has("find:none") }
        directory.announce(advert("tA", "阿梅", setOf(Seat.EAST)))
        rule.waitUntil(10_000) { has("find:room:tA") && !has("find:none") }
    }
}
