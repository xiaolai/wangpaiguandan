package cn.wpgd.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.wpgd.app.core.match.MatchCommand
import cn.wpgd.app.core.match.MatchProjectionCodec
import cn.wpgd.app.core.match.MatchProjector
import cn.wpgd.app.core.match.MatchReduceResult
import cn.wpgd.app.core.match.MatchReducer
import cn.wpgd.app.core.match.MatchStateCodec
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.Nickname
import cn.wpgd.app.data.NicknameResult
import cn.wpgd.app.core.state.SplitMix64
import cn.wpgd.app.session.wire.WireCodec
import cn.wpgd.app.table.RuleProfiles
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WI-12: the serialized types survive R8. Run against the minified build with
 * `./gradlew -Pwpgd.testBuildType=r8check :app:connectedR8checkAndroidTest` (same shrinking and R8
 * rules as release, debug-signed so it installs); it also passes against debug. Every message of the
 * shared wire fixtures (core/src/test/resources/fixtures/wire/, packaged as test assets) decodes,
 * re-encodes to the same JSON and decodes to an equal message; a dealt match state round-trips
 * through its persisted form with an unchanged state hash, and each member's projection encodes.
 */
@RunWith(AndroidJUnit4::class)
class R8SerializationTest {
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    /** Key-sorted compact JSON, so two encodings compare by content, not field order. */
    private fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        is String -> JSONObject.quote(v)
        JSONObject.NULL, null -> "null"
        is Number -> if (v.toDouble() == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    @Test fun everyWireFixtureMessageRoundTrips() {
        var checked = 0
        val files = testAssets.list("")!!.filter { it.endsWith(".json") }
        assertTrue("wire fixtures are packaged as test assets: $files", files.contains("command.json") && files.contains("receipt.json"))
        for (file in files) {
            val root = JSONObject(testAssets.open(file).use { it.readBytes().decodeToString() })
            val cases = root.optJSONArray("cases") ?: continue
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                val wire = case.optJSONObject("wire") ?: continue
                val text = wire.toString()
                val decoded = WireCodec.decode(text)
                assertNotNull("$file ${case.optString("name")}: does not decode", decoded)
                val message = decoded!!
                val again = WireCodec.encodeToString(message)
                assertEquals("$file ${case.optString("name")}: re-encoding changed the JSON", canonical(wire), canonical(JSONObject(again)))
                assertEquals("$file ${case.optString("name")}: decode(encode(m)) != m", message, WireCodec.decode(again))
                checked++
            }
        }
        assertTrue("only $checked wire messages checked", checked >= 20)
    }

    @Test fun dealtMatchStateAndMemberViewRoundTrip() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        fun asset(name: String) = ctx.assets.open(name).use { it.readBytes().decodeToString() }
        val profiles = RuleProfiles.parse(asset("P1-base-v1.json"), asset("M1-match-v1.json"))
        val reducer = MatchReducer(profiles.base, profiles.match)
        val dealt = reducer.reduce(reducer.newMatch(), MatchCommand.DealNext(SplitMix64(20261015)))
        val state = (dealt as MatchReduceResult.Accepted).state
        // The persisted form (WI-06 storage writes MatchStateCodec's JSON; recovery decodes it).
        val decoded = MatchStateCodec.decode(MatchStateCodec.encode(state))
        assertEquals(state, decoded)
        assertEquals(MatchStateCodec.hash(state), MatchStateCodec.hash(decoded))

        // The projection the host sends each member: generated serializers of MatchMemberView and
        // everything inside it. (Decoding happens inside SessionClient; the solo and LAN on-device
        // tests exercise it when run with -Pwpgd.testBuildType=r8check.)
        val projector = MatchProjector(profiles.base, profiles.match)
        for (seat in Seat.entries) {
            val json = JSONObject(MatchProjectionCodec.encode(projector.memberView(state, seat)).decodeToString())
            assertEquals(seat.name, json.getString("seat"))
            assertEquals("$seat holds its 27 cards", 27, json.getJSONObject("deal").getJSONArray("hand").length())
            assertTrue(json.getJSONObject("match").has("dealNumber"))
        }
    }

    /**
     * The whole app path under R8: a solo table on the production graph deals, the host encodes
     * this member's projection, it crosses the session's wire codec and sealed channel, and the
     * client's strict decoder turns it back into a MatchMemberView with the 27 cards.
     */
    @Test fun aSoloTableDealsAndTheClientDecodesItsViewUnderR8() {
        val graph = ApplicationProvider.getApplicationContext<WpgdApplication>().graph
        val owner = graph.owner()
        try {
            assertTrue(owner.startSolo((Nickname.parse("阿梅") as NicknameResult.Valid).nickname))
            val deadline = System.currentTimeMillis() + 30_000
            while (owner.state.value.table?.view?.deal == null && owner.state.value.failure == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
            val state = owner.state.value
            assertEquals("the session failed: ${state.failure}", null, state.failure)
            val view = state.table?.view
            assertNotNull("no projection reached the client within 30 s: $state", view)
            assertEquals(27, view!!.deal!!.hand.size)
        } finally {
            graph.closeTable()
        }
    }
}
