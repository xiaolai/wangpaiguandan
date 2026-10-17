package cn.wpgd.app.room

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.HistoryRepository
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.owner.JoinPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GDN-017 through the screens: after a match ends, the host offers a rematch; everyone stays seated
 * and connected (nobody searches, asks or is approved again), the room returns to the lobby and
 * everyone readies again.
 */
class RematchTest {
    @Test fun aRematchKeepsTheTableTogether() {
        val rig = RoomRig(seed = 71)
        val history = HistoryRepository(InMemoryKeyValueStore())
        val host = rig.host("阿梅", history = history)
        val zhou = rig.phone("老周")
        val chen = rig.phone("陈阿姨")
        val people = rig.startedRoom(host, mapOf(Seat.EAST to zhou), watchers = listOf(chen))
        val (hostSeat, zhouSeat) = people
        assertFalse("no rematch during the match", hostSeat.vm.state().canRematch)

        assertTrue(rig.drive(people) { host.table?.view?.match?.phase == MatchPhase.OVER && zhou.table?.view?.match?.phase == MatchPhase.OVER })
        rig.run(500)
        val firstMatch = history.entries().map { it.dealNumber }
        assertEquals("every deal of the first match is in the history", (1..firstMatch.size).toList(), firstMatch)
        assertTrue("the host is offered a rematch", hostSeat.vm.state().canRematch)
        assertFalse("only the host decides", zhouSeat.vm.state().canRematch)
        val zhouTicket = zhou.identity.rejoinCapability(zhou.table!!.tableId)
        assertNotNull(zhouTicket)

        host.owner.rematch()
        rig.until { listOf(host, zhou).all { RoomScreen.of(it.state) == RoomScreen.LOBBY } && chen.table?.spectatorView?.match?.dealNumber == 0 }
        val lobby = checkNotNull(LobbyUi.of(zhou.state, null))
        assertEquals(listOf("阿梅", "老周", "AI", "AI"), lobby.seats.map { it.nickname ?: it.occupant.name })
        assertEquals(Seat.EAST, lobby.yourSeat)
        assertFalse("everyone readies again", lobby.youReady)
        assertEquals(listOf("陈阿姨"), lobby.observers)
        assertEquals(JoinPhase.ADMITTED, zhou.table!!.join!!.phase)
        assertEquals("nobody asked to join again", emptyList<Any>(), host.table!!.hostSide!!.pending)
        assertEquals("the same capability: no new admission", zhouTicket, zhou.identity.rejoinCapability(zhou.table!!.tableId))
        assertEquals(0, zhou.table!!.view!!.match.dealNumber)
        assertEquals(RoomScreen.WATCH, RoomScreen.of(chen.state))

        zhou.owner.setReady(true)
        rig.until { LobbyUi.of(host.state, null)?.canStart == true }
        host.owner.startMatch()
        rig.until { zhou.table?.view?.match?.dealNumber == 1 }
        assertEquals(RoomScreen.TABLE, RoomScreen.of(zhou.state))

        // The rematch's deals are numbered from 1 again and still reach the history.
        assertTrue(rig.drive(people) { host.table?.view?.match?.lastResult?.dealNumber == 1 })
        rig.run(500)
        assertEquals(firstMatch + 1, history.entries().map { it.dealNumber })
    }

    /**
     * Audit finding 17: a player who leaves after deal 1, and joins the same room again for its
     * rematch, records the rematch's deal 1 as a new deal. History keys deals by (match number, deal
     * number); the match number reaches every member in its view, so the returning player can tell
     * the two deal 1s apart without having seen the match boundary.
     */
    @Test fun aReturningPlayerKeepsTheOldMatchsDealOneDistinct() {
        val rig = RoomRig(seed = 73)
        val host = rig.host("阿梅")
        val zhouHistory = HistoryRepository(InMemoryKeyValueStore())
        val zhou = rig.phone("老周", history = zhouHistory)
        val people = rig.startedRoom(host, mapOf(Seat.EAST to zhou))
        assertEquals(1, zhou.table!!.view!!.match.matchNumber)
        assertTrue(rig.drive(people) { zhou.table?.view?.match?.lastResult?.dealNumber == 1 })
        rig.run(500)
        assertEquals(listOf(1 to 1), zhouHistory.entries().map { it.matchNumber to it.dealNumber })

        // Zhou leaves after deal 1; the room plays the first match to its end without him.
        zhou.owner.leaveRoom()
        rig.until { zhou.table?.join?.phase == JoinPhase.ENDED }
        zhou.owner.shutdown()
        val hostOnly = people.take(1)
        assertTrue(rig.drive(hostOnly) { host.table?.view?.match?.phase == MatchPhase.OVER })
        host.owner.rematch()
        rig.until { RoomScreen.of(host.state) == RoomScreen.LOBBY && host.table?.view?.match?.dealNumber == 0 }
        assertEquals(2, host.table!!.view!!.match.matchNumber)

        // He comes back to the same room on the same device (same identity and history), as a new admission.
        val back = rig.phone("老周", identity = zhou.identity, history = zhouHistory)
        rig.join(back, host, Seat.EAST)
        back.owner.setReady(true)
        rig.until { LobbyUi.of(host.state, null)?.canStart == true }
        host.owner.startMatch()
        rig.until { back.table?.view?.match?.dealNumber == 1 }
        assertEquals(2, back.table!!.view!!.match.matchNumber)
        val again = listOf(people[0], Seated(back, 7301))
        assertTrue(rig.drive(again) { back.table?.view?.match?.lastResult?.dealNumber == 1 })
        rig.run(500)
        assertEquals("deal 1 of each match is its own entry", listOf(1 to 1, 2 to 1), zhouHistory.entries().map { it.matchNumber to it.dealNumber })
    }
}
