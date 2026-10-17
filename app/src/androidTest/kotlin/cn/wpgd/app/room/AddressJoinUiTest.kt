package cn.wpgd.app.room

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.DeviceProfiles
import cn.wpgd.app.R
import cn.wpgd.app.Sized
import cn.wpgd.app.allNodes
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.lan.LanEndpoint
import cn.wpgd.app.owner.ExecutorSessionLoop
import cn.wpgd.app.session.transport.Cancellable
import cn.wpgd.app.session.wire.PROTOCOL_VERSION
import cn.wpgd.app.str
import cn.wpgd.app.ui.AddressJoinScreen
import cn.wpgd.app.ui.FindRoomScreen
import cn.wpgd.app.ui.WpgdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C10 on screen: join by address. The find screen offers it with the hotspot hint (Wi-Fi only); a
 * malformed address is explained at its field, as an accessibility error, before anything is
 * dialled; a failed lookup says why; a found room leads to the same play-or-watch choice. Every
 * control has a 48 dp target and a label, at 320 dp width and 200 % text.
 */
@RunWith(AndroidJUnit4::class)
class AddressJoinUiTest {
    @get:Rule val rule = createComposeRule()
    private val local = LocalVersion.of(DeviceProfiles.profiles.base.hash, DeviceProfiles.profiles.match.hash)
    private val joins = ArrayList<Pair<String, Seat?>>()

    private class FakeLookup : AddressLookupService {
        val asked = ArrayList<LanEndpoint>()
        @Volatile var pending: ((AddressLookupOutcome) -> Unit)? = null
        override fun lookup(endpoint: LanEndpoint, onDone: (AddressLookupOutcome) -> Unit): Cancellable {
            asked += endpoint
            pending = onDone
            return Cancellable { }
        }
    }

    private val lookup = FakeLookup()

    private fun has(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun show(width: Int = 411, fontScale: Float = 1f) {
        val join = AddressJoin(lookup)
        rule.setContent { WpgdTheme { Sized(width.dp, fontScale) { AddressJoinScreen(join, local, onJoin = { a, s, _ -> joins += a.roomId to s }, onBack = {}) } } }
    }

    private fun type(host: String, port: String) {
        rule.onNodeWithTag("address:host").performTextInput(host)
        rule.onNodeWithTag("address:port").performTextInput(port)
        rule.onNodeWithTag("address:lookup").performScrollTo().performClick()
    }

    @Test fun aMalformedAddressIsExplainedAtItsFieldAndNothingIsDialled() {
        show()
        type("http://192.168.1.2", "41234")
        rule.waitForIdle()
        val error = rule.onNodeWithTag("address:host").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Error)
        assertEquals(str(R.string.address_host_has_scheme), error)
        assertTrue(lookup.asked.isEmpty())
    }

    @Test fun aFailedLookupSaysWhyAndAFoundRoomOffersPlayOrWatch() {
        show()
        type("192.168.43.1", "41234")
        rule.waitUntil(5_000) { has("address:looking") }
        assertEquals(listOf(LanEndpoint("192.168.43.1", 41234)), lookup.asked)
        lookup.pending!!(AddressLookupOutcome.Failed(LookupFailure.WRONG_PORT))
        rule.waitUntil(5_000) { has("address:failure:WRONG_PORT") }

        rule.onNodeWithTag("address:lookup").performScrollTo().performClick()
        rule.waitUntil(5_000) { lookup.asked.size == 2 }
        val endpoint = LanEndpoint("192.168.43.1", 41234)
        lookup.pending!!(AddressLookupOutcome.Found(RoomAdvert("tA", "周末掼蛋", "阿梅", PROTOCOL_VERSION, PROTOCOL_VERSION, local.baseProfileHash, local.matchProfileHash, "K".repeat(43), setOf(Seat.EAST), endpoint)))
        rule.waitUntil(5_000) { has("pow:seat:EAST") }
        assertTrue("finding never joins", joins.isEmpty())
        rule.onNodeWithTag("pow:seat:EAST").performScrollTo().performClick()
        assertEquals(listOf("tA" to Seat.EAST), joins)
    }

    @Test fun everyControlIsLabelledAndAtLeast48dpAtSmallWidthAndLargeText() {
        show(width = 320, fontScale = 2f)
        type("", "")
        rule.waitForIdle()
        val min = with(rule.density) { 48.dp.toPx() } - 0.5f
        val actionable = rule.allNodes().filter { SemanticsActions.OnClick in it.config || SemanticsActions.SetText in it.config }
        assertTrue(actionable.size >= 4)
        val small = actionable.filter { maxOf(it.touchBoundsInRoot.height, it.size.height.toFloat()) < min || maxOf(it.touchBoundsInRoot.width, it.size.width.toFloat()) < min }
        assertTrue("touch targets under 48 dp: ${small.map(::describe)}", small.isEmpty())
        for (tag in listOf("address:host", "address:port")) {
            val n = rule.onNodeWithTag(tag).fetchSemanticsNode()
            assertTrue("$tag has no spoken label", n.allText().any { it.isNotBlank() })
        }
        assertEquals(str(R.string.address_host_empty), rule.onNodeWithTag("address:host").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Error))
    }

    @Test fun theFindScreenOffersJoinByAddressOnWifiOnly() {
        val loop = ExecutorSessionLoop("finder-test") { throw it }
        try {
            var opened = 0
            val finder = RoomFinder({ InProcessDirectory().browser() }, loop)
            var bluetooth by mutableStateOf(false)
            rule.setContent {
                WpgdTheme {
                    FindRoomScreen(finder, local, onJoin = { _, _, _ -> }, onBack = {}, onAddress = if (bluetooth) null else ({ opened++ }))
                }
            }
            rule.waitUntil(5_000) { has("find:address") && has("find:hotspot_hint") }
            rule.onNodeWithTag("find:address").performScrollTo().performClick()
            assertEquals(1, opened)
            bluetooth = true
            rule.waitUntil(5_000) { !has("find:address") && !has("find:hotspot_hint") }
        } finally {
            loop.shutdown()
        }
    }

    private fun describe(n: SemanticsNode) = "${n.config.getOrNull(SemanticsProperties.TestTag)} ${n.size}"

}
