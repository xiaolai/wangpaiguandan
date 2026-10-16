package cn.wpgd.app.session.storage

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.Bot
import cn.wpgd.app.session.Table
import cn.wpgd.app.session.host.AbsencePolicy
import cn.wpgd.app.session.host.PauseReason
import cn.wpgd.app.session.host.RecoveryPolicy
import cn.wpgd.app.session.host.RoomStatus
import cn.wpgd.app.session.host.SeatControl
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.wire.ConsentBody
import cn.wpgd.app.session.wire.FollowRequestBody
import cn.wpgd.app.session.wire.FollowSnapshot
import cn.wpgd.app.session.wire.GrantStatus
import cn.wpgd.app.session.wire.LeaveBody
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.session.wire.Receipt
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.session.wire.RejectionReceipt
import cn.wpgd.app.session.wire.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** WI-06: what the members file must carry so that a restart keeps every membership decision. */
class MembershipPersistenceTest {
    private fun SavedTable.seated(): Map<Seat, Table.Peer> = t.fourPlayers().also { t.startDeal() }

    @Test fun anObserversPrivateViewpointSurvivesARestart() {
        val saved = SavedTable(1101)
        val players = saved.seated()
        val observer = saved.t.observer("o1")
        assertIs<Receipt>(saved.t.command(observer, FollowRequestBody(Seat.EAST)))
        val east = players.getValue(Seat.EAST)
        saved.t.until(5_000) { east.client.grants.any { it.status == GrantStatus.PENDING } }
        assertIs<Receipt>(saved.t.command(east, ConsentBody(east.client.grants.single().grantId, approve = true)))
        saved.t.until(5_000) { observer.client.follow != null }

        saved.crash()
        saved.restored()
        val o = saved.rejoin(observer)
        saved.rejoin(players.getValue(Seat.NORTH))
        // The same deal: a second private viewpoint is still refused after the restart.
        val second = saved.t.command(o, FollowRequestBody(Seat.NORTH))
        assertEquals(RejectionCode.ONE_VIEW_PER_DEAL, assertIs<RejectionReceipt>(second).code)
    }

    /** EAST consents to the observer, deal 1 is played out, and [beforeDealTwo] runs before deal 2 is dealt. */
    private fun SavedTable.followIntoDealTwo(beforeDealTwo: () -> Unit = {}): Pair<Map<Seat, Table.Peer>, Table.Peer> {
        val players = seated()
        val observer = t.observer("o1")
        assertIs<Receipt>(t.command(observer, FollowRequestBody(Seat.EAST)))
        val east = players.getValue(Seat.EAST)
        t.until(5_000) { east.client.grants.any { it.status == GrantStatus.PENDING } }
        assertIs<Receipt>(t.command(east, ConsentBody(east.client.grants.single().grantId, approve = true)))
        t.until(5_000) { observer.client.follow != null }
        val bots = players.values.map { Bot(t, it) }
        bots.forEach { it.poke() }
        t.until(5 * 60_000L) { host.state.phase == MatchPhase.DEAL_ENDED }
        bots.forEach { it.enabled = false }
        t.run(500)
        while (host.state.phase != MatchPhase.AWAITING_DEAL) assertIs<SessionHost.AdvanceResult.Applied>(host.advance())
        beforeDealTwo()
        host.advance()
        assertEquals(2, host.state.dealNumber)
        t.run(500)
        return players to observer
    }

    @Test fun aViewpointCarriedIntoTheNextDealIsSavedBeforeItIsShown() {
        val saved = SavedTable(1109)
        val (players, observer) = saved.followIntoDealTwo()
        assertEquals(saved.host.revision, observer.client.follow?.revision, "the consent carried into deal 2")

        saved.crash()
        saved.restored()
        val o = saved.rejoin(observer)
        saved.rejoin(players.getValue(Seat.NORTH))
        // Deal 2's viewpoint was shown, so it must have been durable: a second one is refused after the restart.
        val second = saved.t.command(o, FollowRequestBody(Seat.NORTH))
        assertEquals(RejectionCode.ONE_VIEW_PER_DEAL, assertIs<RejectionReceipt>(second, "$second").code)
    }

    @Test fun aCarriedViewpointThatCannotBeSavedEndsTheGrantInsteadOfShowingTheNextDeal() {
        val saved = SavedTable(1110)
        val (_, observer) = saved.followIntoDealTwo {
            saved.storage.failWhen = { kind, name -> kind == InMemoryStorage.Kind.REPLACE_WRITE && name.endsWith("/members") }
        }
        val dealTwoFollows = observer.received.count { it is FollowSnapshot && it.revision == saved.host.revision }
        assertEquals(0, dealTwoFollows, "a private frame of deal 2 was sent although its viewpoint was not saved")
        assertEquals(GrantStatus.REVOKED, observer.client.grants.single().status)
        assertEquals(RoomStatus.Paused(setOf(PauseReason.STORAGE_FAILURE)), saved.host.status)
    }

    /** The failed save breaks the journal: a checkpoint due on that same revision is skipped, not thrown. */
    @Test fun aCheckpointDueAfterAFailedViewpointSaveIsSkipped() {
        val saved = SavedTable(1113, config = savedConfig(checkpointEvery = 1))
        val (_, observer) = saved.followIntoDealTwo {
            saved.storage.failWhen = { kind, name -> kind == InMemoryStorage.Kind.REPLACE_WRITE && name.endsWith("/members") }
        }
        assertEquals(GrantStatus.REVOKED, observer.client.grants.single().status)
        assertEquals(RoomStatus.Paused(setOf(PauseReason.STORAGE_FAILURE)), saved.host.status)
        // The committed revision is durable in the log: a restart recovers it.
        val committed = saved.host.revision
        saved.storage.failWhen = { _, _ -> false }
        saved.crash()
        assertEquals(committed, saved.restored().host.revision)
    }

    @Test fun aLeaveIsAcknowledgedOnlyOnceItIsSaved() {
        val saved = SavedTable(1102)
        val players = saved.seated()
        val south = players.getValue(Seat.SOUTH)
        saved.storage.failWhen = { _, _ -> true }
        val reply = saved.t.command(south, LeaveBody)
        assertEquals(RejectionCode.ROOM_PAUSED, assertIs<RejectionReceipt>(reply, "$reply").code)
        assertTrue(saved.host.members().any { it.seat == Seat.SOUTH }, "the member is still there: its departure was not saved")
        assertEquals(RoomStatus.Paused(setOf(PauseReason.STORAGE_FAILURE)), saved.host.status)
    }

    @Test fun aSeatWhoseHumanLeftKeepsItsStandInAfterARestart() {
        val saved = SavedTable(1103)
        val players = saved.seated()
        assertIs<Receipt>(saved.t.command(players.getValue(Seat.SOUTH), LeaveBody))
        assertEquals(SeatControl.StandIn(null), saved.host.seatControl(Seat.SOUTH))
        saved.crash()
        val r = saved.restored()
        assertEquals(SeatControl.StandIn(null), r.host.seatControl(Seat.SOUTH), "the stand-in seat came back empty")
    }

    @Test fun aSeatWaitingForAPlayerStillWaitsAfterARestart() {
        val saved = SavedTable(1104, config = savedConfig(policy(after = AbsencePolicy.PAUSE)))
        val players = saved.seated()
        assertIs<Receipt>(saved.t.command(players.getValue(Seat.SOUTH), LeaveBody))
        assertEquals(SeatControl.AwaitingPlayer(null), saved.host.seatControl(Seat.SOUTH))
        saved.crash()
        val r = saved.restored()
        assertEquals(SeatControl.AwaitingPlayer(null), r.host.seatControl(Seat.SOUTH))
        assertIs<RoomStatus.Paused>(r.host.status)
    }

    @Test fun anAiSeatWithoutASubstituteWaitsForAPlayerInsteadOfVanishing() {
        val pauseOnly = RecoveryPolicy(3_000, AbsencePolicy.PAUSE, AbsencePolicy.PAUSE, substitute = null)
        val saved = SavedTable(1105, config = savedConfig(pauseOnly))
        assertTrue(saved.host.setAi(Seat.EAST, standIn))
        saved.crash()
        val r = saved.restored()
        assertEquals(SeatControl.AwaitingPlayer(null), r.host.seatControl(Seat.EAST), "the saved AI seat was dropped")
    }

    @Test fun membersWithUndecodableCredentialsAreQuarantinedNotThrown() {
        val saved = SavedTable(1106)
        saved.seated()
        saved.crash()
        val name = TableFiles(TABLE).members
        val payload = checkNotNull(LogFormat.unwrap(LogFormat.FileKind.MEMBERS, checkNotNull(saved.storage.read(name)))).decodeToString()
        val broken = payload.replaceFirst(Regex("\"capability\":\"[^\"]+\""), "\"capability\":\"not base64!\"")
        check(broken != payload)
        saved.storage.replace(name, LogFormat.wrap(LogFormat.FileKind.MEMBERS, broken.encodeToByteArray()))
        val r = saved.restore()
        assertEquals(QuarantineReason.MEMBERS_UNREADABLE, assertIs<SessionHost.RestoreResult.Quarantined>(r, "$r").reason)
    }

    /** Empty (or short) credentials decode as base64url but can never rejoin: the file is unreadable. */
    @Test fun membersWithEmptyCredentialsAreQuarantined() {
        for (field in listOf("capability", "digestKey")) {
            val saved = SavedTable(1112)
            saved.seated()
            saved.crash()
            val name = TableFiles(TABLE).members
            val payload = checkNotNull(LogFormat.unwrap(LogFormat.FileKind.MEMBERS, checkNotNull(saved.storage.read(name)))).decodeToString()
            val broken = payload.replaceFirst(Regex("\"$field\":\"[^\"]+\""), "\"$field\":\"\"")
            check(broken != payload)
            saved.storage.replace(name, LogFormat.wrap(LogFormat.FileKind.MEMBERS, broken.encodeToByteArray()))
            val r = saved.restore()
            assertEquals(QuarantineReason.MEMBERS_UNREADABLE, assertIs<SessionHost.RestoreResult.Quarantined>(r, "$field: $r").reason)
        }
    }

    @Test fun seatChangesThatCannotBeSavedAreRefusedAndUndone() {
        val saved = SavedTable(1107)
        assertTrue(saved.host.setAi(Seat.EAST, standIn))
        saved.storage.failWhen = { _, _ -> true }
        assertFalse(saved.host.setAi(Seat.NORTH, standIn), "setAi reported success for an unsaved change")
        assertEquals(SeatControl.Empty, saved.host.seatControl(Seat.NORTH))
        assertFalse(saved.host.swapSeats(Seat.EAST, Seat.WEST), "swapSeats reported success for an unsaved change")
        assertEquals(SeatControl.Ai, saved.host.seatControl(Seat.EAST))
        assertEquals(SeatControl.Empty, saved.host.seatControl(Seat.WEST))
        assertEquals(RoomStatus.Paused(setOf(PauseReason.STORAGE_FAILURE)), saved.host.status)
    }

    @Test fun seatChangesThatCannotBeSavedNeverReachMembers() {
        val saved = SavedTable(1111)
        assertTrue(saved.host.setAi(Seat.EAST, standIn))
        val south = saved.t.player(Seat.SOUTH)
        saved.t.run(200)
        south.received.clear()
        saved.storage.failWhen = { _, _ -> true }
        assertFalse(saved.host.setAi(Seat.NORTH, standIn))
        assertFalse(saved.host.swapSeats(Seat.EAST, Seat.WEST))
        assertFalse(saved.host.clearAiSeat(Seat.EAST))
        saved.t.run(500)
        val rosters = south.received.filterIsInstance<Status>().map { s -> s.room.seats.associate { it.seat to it.occupant } }
        assertTrue(rosters.isNotEmpty(), "the pause reached the member")
        for (r in rosters) {
            assertEquals(Occupant.EMPTY, r[Seat.NORTH], "an unsaved AI seat was shown: $r")
            assertEquals(Occupant.AI, r[Seat.EAST], "an unsaved swap or clear was shown: $r")
            assertEquals(Occupant.EMPTY, r[Seat.WEST], "an unsaved swap was shown: $r")
        }
    }

    @Test fun clearingAnAiSeatThatCannotBeSavedKeepsTheAi() {
        val saved = SavedTable(1108)
        assertTrue(saved.host.setAi(Seat.EAST, standIn))
        saved.storage.failWhen = { _, _ -> true }
        assertFalse(saved.host.clearAiSeat(Seat.EAST))
        assertEquals(SeatControl.Ai, saved.host.seatControl(Seat.EAST))
    }
}
