package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.wire.AdmissionDenial
import cn.wpgd.app.session.wire.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * GDN-085, audit A14 and A15: a seat belongs to the capability it was admitted with, never to a
 * nickname, a claimed seat or a network address. Nearby rooms: [TwoRoomIsolationTest]. A host that
 * comes back on another port: the app's `HostPortChangeRejoinTest`.
 */
class RejoinOwnershipTest {
    @Test fun duplicateNamesSeatClaimsAndNewConnectionsNeverMoveASeat() {
        val t = Table(seed = 161)
        // Two players share one nickname.
        val south = t.joiner(t.config(Role.PLAYER, "阿李", Seat.SOUTH)).also { t.admit(it) }
        val north = t.joiner(t.config(Role.PLAYER, "阿李", Seat.NORTH)).also { t.admit(it) }
        t.player(Seat.EAST)
        t.player(Seat.WEST)
        t.startDeal()
        val southHand = checkNotNull(south.client.memberView?.deal).hand
        val northHand = checkNotNull(north.client.memberView?.deal).hand
        assertNotEquals(southHand, northHand)
        val southCapability = checkNotNull(south.client.capability)
        val northCapability = checkNotNull(north.client.capability)
        val revision = t.host.revision

        // Both drop. While they are away, the same name without a capability takes neither seat.
        south.link.disconnect()
        north.link.disconnect()
        t.until(5_000) { t.host.members().count { !it.connected } == 2 }
        for (seat in listOf(Seat.SOUTH, Seat.NORTH)) {
            val impostor = t.joiner(t.config(Role.PLAYER, "阿李", seat))
            t.until(5_000) { impostor.client.phase == ClientPhase.REJECTED }
            assertEquals(AdmissionDenial.SEAT_TAKEN, impostor.client.denial, "a name took the absent $seat")
        }
        assertTrue(t.host.pendingAdmissions().isEmpty())

        // Each comes back on a new connection (another address), each claiming the OTHER seat:
        // the capability decides, and the claim is ignored.
        val southBack = t.joiner(t.config(Role.PLAYER, "阿李", Seat.NORTH, rejoin = southCapability))
        val northBack = t.joiner(t.config(Role.PLAYER, "阿李", Seat.SOUTH, rejoin = northCapability))
        t.until(5_000) {
            listOf(southBack, northBack).all { it.client.phase == ClientPhase.ADMITTED && it.client.memberView?.deal != null }
        }
        assertEquals(Seat.SOUTH, southBack.client.memberView!!.seat)
        assertEquals(Seat.NORTH, northBack.client.memberView!!.seat)
        assertEquals(southHand, southBack.client.memberView!!.deal!!.hand)
        assertEquals(northHand, northBack.client.memberView!!.deal!!.hand)
        assertEquals(2L, t.host.metrics.rejoined)
        assertEquals(revision, t.host.revision, "rejoining changes no game state")
        assertEquals(4, t.host.members().count { it.role == Role.PLAYER })
        assertTrue(t.host.members().all { it.connected })
    }

    @Test fun aCapabilityRejoinsOnlyInItsOwnRole() {
        val t = Table(seed = 162)
        val players = t.fourPlayers()
        val watcher = t.observer("看客")
        t.startDeal()
        // A watcher's capability cannot claim a seat; a player's cannot come back as a watcher.
        val seatGrab = t.joiner(t.config(Role.PLAYER, "看客", Seat.SOUTH, rejoin = watcher.client.capability))
        val demoted = t.joiner(t.config(Role.OBSERVER, "south", rejoin = players.getValue(Seat.SOUTH).client.capability))
        t.until(5_000) { seatGrab.client.phase == ClientPhase.REJECTED && demoted.client.phase == ClientPhase.REJECTED }
        assertEquals(AdmissionDenial.CAPABILITY_INVALID, seatGrab.client.denial)
        assertEquals(AdmissionDenial.CAPABILITY_INVALID, demoted.client.denial)
        assertTrue(players.values.all { it.client.phase == ClientPhase.ADMITTED })
        assertEquals(Seat.SOUTH, t.host.members().single { it.nickname == "south" }.seat)
    }

    /** A table the host ended admits nobody: not an old capability, not a newcomer, and not after a restore. */
    @Test fun anEndedTableHonoursNoCapability() {
        for (restored in listOf(false, true)) {
            val saved = cn.wpgd.app.session.storage.SavedTable(171)
            val t = saved.t
            val south = t.player(Seat.SOUTH)
            val capability = checkNotNull(south.client.capability)
            assertTrue(t.host.end(cn.wpgd.app.session.storage.T0 + 1, cn.wpgd.app.session.storage.T0 + 2))
            t.until(5_000) { south.client.phase == ClientPhase.CLOSED }
            if (restored) {
                saved.crash()
                saved.restored()
            }
            val ghost = t.joiner(t.config(Role.PLAYER, "south", Seat.SOUTH, rejoin = capability))
            val newcomer = t.joiner(t.config(Role.OBSERVER, "late"))
            t.run(5_000)
            for (p in listOf(ghost, newcomer)) {
                assertTrue(p.client.phase != ClientPhase.ADMITTED, "restored=$restored: ${p.client.config.nickname} was admitted to an ended table")
                assertEquals(cn.wpgd.app.session.wire.CloseReason.EPOCH_ENDED, p.client.closeReason, "restored=$restored")
            }
            assertTrue(t.host.pendingAdmissions().isEmpty())
        }
    }
}
