package cn.wpgd.app.session.lobby

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.Table
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.host.AbsencePolicy
import cn.wpgd.app.session.host.HostConfig
import cn.wpgd.app.session.host.LobbyGate
import cn.wpgd.app.session.host.RecoveryPolicy
import cn.wpgd.app.session.host.RoomPolicy
import cn.wpgd.app.session.host.SeatPolicy
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.wire.AbsenceMode
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.session.wire.PassBody
import cn.wpgd.app.session.wire.ReadyBody
import cn.wpgd.app.session.wire.Receipt
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.session.wire.RejectionReceipt
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.session.wire.RoomMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A policy that is never asked to act in these lobby tests (the deal is not played). */
internal val IDLE_AI = SeatPolicy { _, _ -> PassBody }

internal val SUBSTITUTE_RECOVERY = RecoveryPolicy(graceMs = 10_000, afterGrace = AbsencePolicy.SUBSTITUTE, afterLeave = AbsencePolicy.SUBSTITUTE, substitute = IDLE_AI)

internal fun Table.ready(peer: Table.Peer, ready: Boolean = true) =
    command(peer, ReadyBody(ready, checkNotNull(peer.client.room).settingsRevision))

/**
 * WI-09 (GDN-012, GDN-013): every member sees the roster and the room settings; the room starts only
 * when every seat is filled (an AI explicitly) and every human is connected and ready; a settings
 * change clears every ready flag; seats move only in the lobby.
 */
class LobbyRosterTest {
    @Test fun everyMemberSeesTheRosterAndSettings() {
        val t = Table(seed = 901)
        val south = t.player(Seat.SOUTH)
        val north = t.player(Seat.NORTH)
        assertTrue(t.host.setAi(Seat.EAST, IDLE_AI))
        val watcher = t.observer("watcher")
        t.run(200)
        for (peer in listOf(south, north, watcher)) {
            val room = checkNotNull(peer.client.room)
            assertTrue(room.lobby)
            assertEquals(Seat.entries, room.seats.map { it.seat })
            assertEquals(listOf(Occupant.HUMAN, Occupant.AI, Occupant.HUMAN, Occupant.EMPTY), room.seats.map { it.occupant })
            assertEquals(listOf("south", null, "north", null), room.seats.map { it.nickname })
            assertEquals(listOf("watcher"), room.observers)
            assertEquals(RoomMode.FRIENDLY, room.settings.policy.mode)
        }
        assertEquals(Seat.SOUTH, south.client.room?.yourSeat)
        assertNull(watcher.client.room?.yourSeat, "an observer has no seat")
    }

    @Test fun startWaitsForEverySeatAndEveryReadyHuman() {
        val t = Table(seed = 902)
        val south = t.player(Seat.SOUTH)
        val north = t.player(Seat.NORTH)
        assertTrue(t.host.setAi(Seat.EAST, IDLE_AI))
        val blocked = assertIs<SessionHost.StartResult.Blocked>(t.host.start()).blockers
        assertEquals(
            setOf(LobbyGate.Blocker(Seat.SOUTH, LobbyGate.Reason.NOT_READY), LobbyGate.Blocker(Seat.NORTH, LobbyGate.Reason.NOT_READY), LobbyGate.Blocker(Seat.WEST, LobbyGate.Reason.EMPTY_SEAT)),
            blocked.toSet(),
        )
        assertEquals(0, t.host.state.dealNumber, "nothing dealt while blocked")

        assertIs<Receipt>(t.ready(south))
        assertTrue(t.host.setAi(Seat.WEST, IDLE_AI), "the host fills the empty seat with an AI explicitly")
        assertEquals(listOf(LobbyGate.Blocker(Seat.NORTH, LobbyGate.Reason.NOT_READY)), assertIs<SessionHost.StartResult.Blocked>(t.host.start()).blockers)
        assertEquals(blocked.size - 2, 1)

        assertIs<Receipt>(t.ready(north))
        t.run(100)
        assertTrue(north.client.room!!.seats.single { it.seat == Seat.SOUTH }.ready, "everyone sees who is ready")
        assertIs<SessionHost.StartResult.Started>(t.host.start())
        t.run(200)
        assertEquals(1, t.host.state.dealNumber)
        assertFalse(south.client.room!!.lobby, "the lobby ends with the first deal")
        assertIs<SessionHost.StartResult.NotInLobby>(t.host.start())

        val late = t.command(south, ReadyBody(false, south.client.room!!.settingsRevision))
        assertEquals(RejectionCode.NOT_IN_LOBBY, assertIs<RejectionReceipt>(late).code)
    }

    @Test fun aDisconnectedHumanBlocksTheStart() {
        val t = Table(seed = 903)
        val players = Seat.entries.associateWith { t.player(it) }
        players.values.forEach { assertIs<Receipt>(t.ready(it)) }
        players.getValue(Seat.EAST).link.disconnect()
        t.run(200)
        assertEquals(listOf(LobbyGate.Blocker(Seat.EAST, LobbyGate.Reason.AWAY)), assertIs<SessionHost.StartResult.Blocked>(t.host.start()).blockers)
    }

    @Test fun aSettingsChangeClearsEveryReadyFlagAndOldReadiesAreRefused() {
        val t = Table(seed = 904)
        val south = t.player(Seat.SOUTH)
        val north = t.player(Seat.NORTH)
        val seen = south.client.room!!.settingsRevision
        assertIs<Receipt>(t.ready(south))
        assertIs<Receipt>(t.ready(north))
        t.run(100)
        assertTrue(south.client.room!!.seats.filter { it.occupant == Occupant.HUMAN }.all { it.ready })

        assertTrue(t.host.updateSettings(RoomPolicy.COMPETITIVE, SUBSTITUTE_RECOVERY))
        t.run(200)
        for (peer in listOf(south, north)) {
            val room = peer.client.room!!
            assertTrue(room.seats.none { it.ready }, "a rules change resets ready")
            assertNotEquals(seen, room.settingsRevision)
            assertEquals(RoomMode.COMPETITIVE, room.settings.policy.mode)
            assertEquals(AbsenceMode.SUBSTITUTE, room.settings.afterGrace)
            assertEquals(10_000, room.settings.graceMs)
            assertEquals(RoomMode.COMPETITIVE, peer.client.policy?.mode, "hint policy follows the new settings")
        }
        val stale = t.command(south, ReadyBody(true, seen))
        assertEquals(RejectionCode.SETTINGS_CHANGED, assertIs<RejectionReceipt>(stale).code, "ready for rules the player has not seen is refused")
        assertIs<Receipt>(t.ready(south))
    }

    @Test fun observersCannotReady() {
        val t = Table(seed = 905)
        t.player(Seat.SOUTH)
        val watcher = t.observer("watcher")
        val r = t.command(watcher, ReadyBody(true, watcher.client.room!!.settingsRevision))
        assertEquals(RejectionCode.NOT_A_PLAYER, assertIs<RejectionReceipt>(r).code)
    }

    @Test fun duplicateNicknamesAreHarmless() {
        val t = Table(seed = 906)
        val a = t.joiner(t.config(Role.PLAYER, "阿梅", Seat.SOUTH))
        val b = t.joiner(t.config(Role.PLAYER, "阿梅", Seat.NORTH))
        t.until(10_000) { a.client.phase == ClientPhase.PENDING && b.client.phase == ClientPhase.PENDING }
        for ((peer, seat) in listOf(a to Seat.SOUTH, b to Seat.NORTH)) {
            val p = t.host.pendingAdmissions().single { it.seat == seat }
            assertEquals("阿梅", p.nickname)
            assertIs<SessionHost.ApprovalResult.Admitted>(t.host.approve(p.connectionId, peer.client.confirmationCode!!))
        }
        t.until(10_000) { a.client.phase == ClientPhase.ADMITTED && b.client.phase == ClientPhase.ADMITTED && a.client.room != null && b.client.room != null }
        assertEquals(Seat.SOUTH, a.client.seat)
        assertEquals(Seat.NORTH, b.client.seat)
        assertNotEquals(a.client.memberId, b.client.memberId, "same name, two members")
        assertEquals(listOf("阿梅", "阿梅"), t.host.room().seats.mapNotNull { it.nickname })
        // Each one's ready acts only for its own seat.
        assertIs<Receipt>(t.ready(a))
        t.run(100)
        assertEquals(listOf(true, false), t.host.room().seats.filter { it.occupant == Occupant.HUMAN }.map { it.ready })
    }

    @Test fun seatsMoveOnlyInTheLobbyAndTheMovedPlayerFollows() {
        // SeatArrangementTest (GDN-012) is in :app; this checks the host side: partners are chosen
        // by the host whatever order people joined in.
        val t = Table(seed = 907)
        val first = t.player(Seat.SOUTH)
        val second = t.player(Seat.EAST)
        assertIs<Receipt>(t.ready(second))
        assertTrue(t.host.swapSeats(Seat.EAST, Seat.NORTH), "make the second joiner the first one's partner")
        t.until(5_000) { second.client.seat == Seat.NORTH && second.client.memberView?.seat == Seat.NORTH }
        assertEquals(Seat.SOUTH.partner, second.client.seat)
        val room = t.host.room()
        assertEquals(Occupant.EMPTY, room.seats.single { it.seat == Seat.EAST }.occupant)
        assertEquals("east", room.seats.single { it.seat == Seat.NORTH }.nickname)
        assertFalse(room.seats.single { it.seat == Seat.NORTH }.ready, "a moved player readies again")
        assertEquals(Seat.NORTH, t.host.members().single { it.nickname == "east" }.seat)

        assertTrue(t.host.setAi(Seat.EAST, IDLE_AI))
        assertTrue(t.host.setAi(Seat.WEST, IDLE_AI))
        assertTrue(t.host.clearAiSeat(Seat.WEST))
        assertEquals(Occupant.EMPTY, t.host.room().seats.single { it.seat == Seat.WEST }.occupant)
        assertTrue(t.host.setAi(Seat.WEST, IDLE_AI))
        assertIs<Receipt>(t.ready(first))
        assertIs<Receipt>(t.ready(second))
        assertIs<SessionHost.StartResult.Started>(t.host.start())
        t.until(5_000) { second.client.memberView?.deal != null }
        assertEquals(27, second.client.memberView!!.deal!!.hand.size, "the moved player holds the NORTH hand")
        assertFalse(t.host.swapSeats(Seat.SOUTH, Seat.EAST), "no seat changes once the match started")
        assertFalse(t.host.clearAiSeat(Seat.EAST))
        assertFalse(t.host.updateSettings(RoomPolicy.COMPETITIVE, t.host.recoveryPolicy))
        assertEquals(0L, second.client.metrics.digestMismatches + first.client.metrics.digestMismatches)
    }

    @Test fun anAbsentHumanShowsItsGraceThenTheStandIn() {
        val t = Table(seed = 908, hostConfig = HostConfig(roomId = cn.wpgd.app.session.ROOM, recovery = SUBSTITUTE_RECOVERY))
        val players = Seat.entries.associateWith { t.player(it) }
        players.values.forEach { assertIs<Receipt>(t.ready(it)) }
        assertIs<SessionHost.StartResult.Started>(t.host.start())
        t.run(200)
        players.getValue(Seat.NORTH).link.disconnect()
        t.run(1_000)
        val south = players.getValue(Seat.SOUTH).client
        val north = south.room!!.seats.single { it.seat == Seat.NORTH }
        assertEquals(Occupant.ABSENT, north.occupant, "T6: partner disconnected, grace running")
        assertTrue(north.graceRemainingMs!! in 1..10_000, "grace remaining ${north.graceRemainingMs}")
        t.run(10_000)
        assertEquals(Occupant.STAND_IN, south.room!!.seats.single { it.seat == Seat.NORTH }.occupant, "T7: the AI plays for the seat")
        assertEquals("north", south.room!!.seats.single { it.seat == Seat.NORTH }.nickname, "the seat still belongs to its player")
        t.reconnect(players.getValue(Seat.NORTH))
        t.until(5_000) { south.room!!.seats.single { it.seat == Seat.NORTH }.occupant == Occupant.HUMAN }
    }

    @Test fun theHostCanLetTheAiStandInAtOnce() {
        val t = Table(seed = 909, hostConfig = HostConfig(roomId = cn.wpgd.app.session.ROOM, recovery = SUBSTITUTE_RECOVERY))
        val players = Seat.entries.associateWith { t.player(it) }
        players.values.forEach { assertIs<Receipt>(t.ready(it)) }
        assertIs<SessionHost.StartResult.Started>(t.host.start())
        players.getValue(Seat.EAST).link.disconnect()
        t.run(500)
        assertFalse(t.host.standInNow(Seat.SOUTH), "a connected human is never replaced")
        assertTrue(t.host.standInNow(Seat.EAST))
        t.run(100)
        assertEquals(Occupant.STAND_IN, players.getValue(Seat.SOUTH).client.room!!.seats.single { it.seat == Seat.EAST }.occupant)

        val paused = Table(seed = 910)
        val ps = Seat.entries.associateWith { paused.player(it) }
        ps.values.forEach { assertIs<Receipt>(paused.ready(it)) }
        assertIs<SessionHost.StartResult.Started>(paused.host.start())
        ps.getValue(Seat.EAST).link.disconnect()
        paused.run(500)
        assertFalse(paused.host.standInNow(Seat.EAST), "no substitute policy: no stand-in")
        assertIs<cn.wpgd.app.session.host.SeatControl.Absent>(paused.host.seatControl(Seat.EAST))
    }
}
