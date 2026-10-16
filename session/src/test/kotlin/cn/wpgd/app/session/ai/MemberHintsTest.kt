package cn.wpgd.app.session.ai

import cn.wpgd.app.core.ai.HintResult
import cn.wpgd.app.session.ROOM
import cn.wpgd.app.session.TEST_RECOVERY
import cn.wpgd.app.session.Table
import cn.wpgd.app.session.TestProfiles
import cn.wpgd.app.session.host.HostConfig
import cn.wpgd.app.session.host.RoomPolicy
import cn.wpgd.app.session.turn
import cn.wpgd.app.session.wire.ConsentBody
import cn.wpgd.app.session.wire.FollowRequestBody
import cn.wpgd.app.session.wire.Receipt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * GDN-062 through the session: hints follow the room policy from the host (PROVISIONAL G4 default:
 * friendly yes, competitive no), go only to the requesting member's own device, and never to a
 * spectator, even one privately following a player.
 */
class MemberHintsTest {
    private val hints = MemberHints(TestProfiles.base, TestProfiles.match)

    @Test fun friendlyRoomPlayerGetsALegalHintThatTheHostAccepts() {
        val t = Table(seed = 401)
        val players = t.fourPlayers()
        t.startDeal()
        val seat = t.turn()
        val peer = players.getValue(seat)
        val offered = assertIs<HintResult.Offered>(hints.request(peer.client))
        assertTrue(t.command(peer, offered.action.toBody()) is Receipt, "the hinted action is legal at the host")
        // Not this seat's turn any more: nothing to decide.
        assertEquals(HintResult.NothingToDecide, hints.request(peer.client))
    }

    @Test fun competitiveRoomGivesNoHints() {
        val t = Table(seed = 402, hostConfig = HostConfig(roomId = ROOM, recovery = TEST_RECOVERY, policy = RoomPolicy.COMPETITIVE))
        val players = t.fourPlayers()
        t.startDeal()
        assertEquals(HintResult.DisabledByRoom, hints.request(players.getValue(t.turn()).client))
    }

    @Test fun spectatorsNeverGetHintsEvenWhenFollowingAPlayer() {
        val t = Table(seed = 403)
        val players = t.fourPlayers()
        val watcher = t.observer("watcher")
        t.startDeal()
        val seat = t.turn()
        val grant = t.command(watcher, FollowRequestBody(seat))
        assertIs<Receipt>(grant)
        val pending = players.getValue(seat).client.grants.single()
        assertIs<Receipt>(t.command(players.getValue(seat), ConsentBody(pending.grantId, approve = true)))
        t.until(5_000) { watcher.client.follow != null }
        assertNotNull(watcher.client.follow, "the observer holds the player's private view")
        assertEquals(HintResult.NotAPlayer, hints.request(watcher.client))
        assertIs<HintResult.Offered>(hints.request(players.getValue(seat).client), "the followed player still gets its own hint")
    }

    /**
     * A hint is computed on the member's device: asking for one hands no chunk to any transport and
     * changes no state or view. Measured with the virtual clock stopped, so nothing else can send.
     */
    @Test fun askingForAHintSendsNothing() {
        val t = Table(seed = 404)
        val players = t.fourPlayers()
        val watcher = t.observer("watcher")
        t.startDeal()
        val peer = players.getValue(t.turn())
        t.run(200)
        val chunks = t.network.captured.size
        val spectator = t.spectatorBytes()
        val revision = t.host.revision
        val pending = peer.client.pendingCommands
        assertIs<HintResult.Offered>(hints.request(peer.client))
        assertIs<HintResult.Offered>(hints.request(peer.client))
        assertEquals(chunks, t.network.captured.size, "a hint request sent bytes")
        assertEquals(pending, peer.client.pendingCommands)
        assertEquals(spectator, t.spectatorBytes())
        assertEquals(revision, t.host.revision)
        assertNotNull(watcher.client.spectatorView)
    }
}
