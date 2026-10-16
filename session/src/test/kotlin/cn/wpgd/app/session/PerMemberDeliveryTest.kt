package cn.wpgd.app.session

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.MatchProjectionCodec
import cn.wpgd.app.core.state.Seat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GDN-049 (delivery half; the projection itself is `ProjectionPrivacyTest` in `:core`): after every
 * committed revision each client holds exactly the projection the host builds for the seat its
 * capability names, byte for byte, and a watcher holds exactly the public view. Nobody receives a
 * view built for anyone else.
 */
class PerMemberDeliveryTest {
    @Test fun everyMemberHoldsExactlyItsOwnProjectionAfterEveryRevision() {
        val t = Table(seed = 171)
        val players = t.fourPlayers()
        val watcher = t.observer("看客")
        t.startDeal()

        var checked = 0
        while (t.host.state.phase == MatchPhase.PLAYING && checked < 60) {
            t.until(5_000) { (players.values + watcher).all { it.client.revision == t.host.revision } }
            for ((seat, p) in players) {
                val view = checkNotNull(p.client.memberView) { "$seat has no view" }
                assertEquals(seat, view.seat)
                assertEquals(t.memberBytes(seat), MatchProjectionCodec.encode(view).decodeToString(), "$seat at r${t.host.revision}")
                assertNull(p.client.spectatorView, "$seat also holds a public view")
            }
            assertNull(watcher.client.memberView, "the watcher holds a seat's view")
            assertEquals(t.spectatorBytes(), MatchProjectionCodec.encode(checkNotNull(watcher.client.spectatorView)).decodeToString())
            checked++
            val actor = players.getValue(t.turn())
            t.command(actor, simpleBody(checkNotNull(actor.client.memberView)))
        }
        assertTrue(checked >= 20, "only $checked revisions were compared")
        assertTrue(Seat.entries.map { t.memberBytes(it) }.toSet().size == 4, "the four seats' views must differ")
    }
}
