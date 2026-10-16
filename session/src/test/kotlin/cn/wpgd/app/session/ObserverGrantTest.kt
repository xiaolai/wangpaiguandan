package cn.wpgd.app.session

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.host.HostConfig
import cn.wpgd.app.session.host.RoomPolicy
import cn.wpgd.app.session.host.SessionHost
import cn.wpgd.app.session.transport.LinkFaults
import cn.wpgd.app.session.wire.ConsentBody
import cn.wpgd.app.session.wire.FollowRequestBody
import cn.wpgd.app.session.wire.FollowSnapshot
import cn.wpgd.app.session.wire.GrantStatus
import cn.wpgd.app.session.wire.LeaveBody
import cn.wpgd.app.session.wire.Receipt
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.session.wire.RejectionReceipt
import cn.wpgd.app.session.wire.RevokeBody
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.session.wire.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PROVISIONAL policy (gate G4 proposed defaults), GDN-066/067/068/072, P4, P5: consent, one private
 * viewpoint per deal, the revocation boundary, departure revokes, replacement needs new consent.
 */
class ObserverGrantTest {
    private fun table(seed: Long, observerFaults: LinkFaults = LinkFaults.RELIABLE): Triple<Table, Map<Seat, Table.Peer>, Table.Peer> {
        val t = Table(seed = seed)
        val players = t.fourPlayers()
        val o = t.observer("watcher", observerFaults)
        t.startDeal()
        return Triple(t, players, o)
    }

    private fun Table.follow(o: Table.Peer, player: Table.Peer, seat: Seat, approve: Boolean = true) {
        assertIs<Receipt>(command(o, FollowRequestBody(seat)))
        until(5_000) { player.client.grants.any { it.status == GrantStatus.PENDING } }
        val g = player.client.grants.single { it.status == GrantStatus.PENDING }
        assertEquals("watcher", g.observerName, "the player sees who asks")
        assertIs<Receipt>(command(player, ConsentBody(g.grantId, approve)))
        until(5_000) { o.client.grants.any { it.grantId == g.grantId && it.status != GrantStatus.PENDING } }
    }

    @Test fun consentRevealsOnlyThatSeatAndDenialLeavesThePublicView() {
        val (t, players, o) = table(81)
        t.follow(o, players.getValue(Seat.NORTH), Seat.NORTH, approve = false)
        t.run(500)
        assertNull(o.client.follow, "denial leaves a public-only view")
        assertTrue(o.received.none { it is FollowSnapshot })
        assertNotNull(o.client.spectatorView)

        // A denied request does not count as a viewpoint: the observer may ask the same or another seat.
        t.follow(o, players.getValue(Seat.SOUTH), Seat.SOUTH)
        t.until(5_000) { o.client.follow != null }
        assertEquals(t.memberBytes(Seat.SOUTH), cn.wpgd.app.core.match.MatchProjectionCodec.encode(o.client.follow!!.view).decodeToString())
        assertTrue(o.received.filterIsInstance<FollowSnapshot>().all { it.seat == Seat.SOUTH })
        val southStatus = players.getValue(Seat.SOUTH).client.grants.single()
        assertEquals(GrantStatus.ACTIVE, southStatus.status, "the followed player sees who watches (GDN-067)")
    }

    /** Follow snapshots go to observers only: a followed player must not keep asking for one. */
    @Test fun aFollowedPlayerDoesNotResyncEveryHeartbeat() {
        val (t, players, o) = table(89)
        val south = players.getValue(Seat.SOUTH)
        t.follow(o, south, Seat.SOUTH)
        t.until(5_000) { o.client.follow != null }
        t.run(3_000)
        val before = south.client.metrics.resyncRequests
        t.run(10 * t.hostConfig.heartbeatIntervalMs)
        assertEquals(GrantStatus.ACTIVE, south.client.grants.single().status)
        assertEquals(before, south.client.metrics.resyncRequests, "a followed player resynced while up to date")
    }

    /** A follow snapshot is checked like a snapshot: a view under other rule profiles is refused. */
    @Test fun aFollowViewUnderOtherProfilesIsDiscarded() {
        val (t, players, o) = table(90)
        t.follow(o, players.getValue(Seat.SOUTH), Seat.SOUTH)
        t.until(5_000) { o.client.follow != null }
        val f = checkNotNull(o.client.follow)
        val grant = o.client.grants.single { it.status == GrantStatus.ACTIVE }
        val foreign = f.view.copy(match = f.view.match.copy(baseProfileHash = "0".repeat(64)))
        val bad = o.client.metrics.badSnapshots
        o.client.onMessage(FollowSnapshot(checkNotNull(o.client.epoch), f.revision + 1, grant.grantId, grant.grantRevision, Seat.SOUTH,
            cn.wpgd.app.core.match.MatchProjectionCodec.encode(foreign).decodeToString()))
        assertEquals(f, o.client.follow, "a follow view under other profiles was applied")
        assertEquals(bad + 1, o.client.metrics.badSnapshots)
    }

    @Test fun onePrivateViewpointPerDeal() {
        val (t, players, o) = table(82)
        t.follow(o, players.getValue(Seat.SOUTH), Seat.SOUTH)
        val active = o.client.grants.single { it.status == GrantStatus.ACTIVE }
        assertIs<Receipt>(t.command(o, RevokeBody(active.grantId)))
        val again = assertIs<RejectionReceipt>(t.command(o, FollowRequestBody(Seat.EAST)))
        assertEquals(RejectionCode.ONE_VIEW_PER_DEAL, again.code)
        // The same seat again is allowed, with a new consent.
        t.follow(o, players.getValue(Seat.SOUTH), Seat.SOUTH)
        assertEquals(GrantStatus.ACTIVE, o.client.grants.last().status)
        // Holding one grant, a second request is refused.
        assertEquals(RejectionCode.ALREADY_FOLLOWING, assertIs<RejectionReceipt>(t.command(o, FollowRequestBody(Seat.EAST))).code)
    }

    @Test fun revocationBoundaryDropsQueuedFramesAndTheClientDiscardsStaleOnes() {
        val slow = LinkFaults(latencyMs = 300, window = 3)
        val (t, players, o) = table(83, observerFaults = slow)
        val south = players.getValue(Seat.SOUTH)
        t.follow(o, south, Seat.SOUTH)
        t.until(20_000) { o.client.follow != null }
        val grant = o.client.grants.single { it.status == GrantStatus.ACTIVE }

        // Play fast: follow frames pile up in the slow observer's queue.
        val bots = players.values.map { Bot(t, it, thinkMs = 1) }
        bots.forEach { it.poke() }
        t.until(60_000) { t.host.revision >= 30 }
        bots.forEach { it.enabled = false }
        val boundaryRevision = t.host.revision
        val dropsBefore = t.host.metrics.followFramesDropped
        assertIs<Receipt>(t.command(south, RevokeBody(grant.grantId)))
        assertTrue(t.host.metrics.followFramesDropped > dropsBefore, "queued follow frames must be dropped at the boundary")
        bots.forEach { it.enabled = true; it.poke() }
        t.until(60_000) { t.host.revision >= boundaryRevision + 20 }
        bots.forEach { it.enabled = false }
        t.until(60_000) { o.client.revision == t.host.revision }
        t.run(5_000)

        assertNull(o.client.follow, "revocation clears the private view")
        assertEquals(GrantStatus.REVOKED, o.client.grants.single { it.grantId == grant.grantId }.status)
        // After the boundary the host emitted no follow frame for any later revision.
        assertTrue(o.received.filterIsInstance<FollowSnapshot>().none { it.revision > boundaryRevision }, "a follow frame was emitted after revocation")

        // A follow frame with the old grant revision arriving late is discarded.
        val late = FollowSnapshot(o.client.epoch!!, boundaryRevision, grant.grantId, grant.grantRevision, Seat.SOUTH, t.memberBytes(Seat.SOUTH))
        val discarded = o.client.metrics.staleFollowDiscarded
        o.client.onMessage(late)
        assertNull(o.client.follow)
        assertEquals(discarded + 1, o.client.metrics.staleFollowDiscarded)
    }

    @Test fun clientDiscardsFramesWhoseGrantRevisionIsNotTheActiveOne() {
        val (t, players, o) = table(84)
        t.follow(o, players.getValue(Seat.SOUTH), Seat.SOUTH)
        t.until(5_000) { o.client.follow != null }
        val active = o.client.grants.single { it.status == GrantStatus.ACTIVE }
        val view = o.client.follow!!
        o.client.onMessage(FollowSnapshot(o.client.epoch!!, t.host.revision + 5, active.grantId, active.grantRevision - 1, Seat.SOUTH, t.memberBytes(Seat.SOUTH)))
        assertEquals(view, o.client.follow, "a frame with a stale grant revision must not replace the view")
        assertEquals(1, o.client.metrics.staleFollowDiscarded)
    }

    @Test fun departureRevokesAndReplacementNeedsNewConsent() {
        val (t, players, o) = table(85)
        // Departure: the followed player leaves.
        t.follow(o, players.getValue(Seat.SOUTH), Seat.SOUTH)
        t.until(5_000) { o.client.follow != null }
        assertIs<Receipt>(t.command(players.getValue(Seat.SOUTH), LeaveBody))
        t.until(5_000) { o.client.grants.all { it.status == GrantStatus.REVOKED } }
        assertNull(o.client.follow)

        // Replacement: a substitute takes SOUTH; the earlier consent does not carry over.
        val sub = t.joiner(t.config(Role.PLAYER, "sub", Seat.SOUTH))
        t.until(5_000) { sub.client.phase == ClientPhase.PENDING }
        assertIs<SessionHost.ApprovalResult.Admitted>(t.host.approve(t.host.pendingAdmissions().single().connectionId, sub.client.confirmationCode!!))
        t.until(5_000) { sub.client.viewJson != null }
        t.run(2_000)
        assertNull(o.client.follow, "replacement must not restore private access")
        assertTrue(o.received.filterIsInstance<Status>().last().grants.none { it.status == GrantStatus.ACTIVE })
        assertIs<Receipt>(t.command(o, FollowRequestBody(Seat.SOUTH)))
        t.run(1_000)
        assertEquals(GrantStatus.PENDING, o.client.grants.last().status, "a new grant waits for the new player's consent")
        assertNull(o.client.follow)
    }

    @Test fun replacingTheFollowedPlayerRevokesTheGrant() {
        val (t, players, o) = table(86)
        t.follow(o, players.getValue(Seat.EAST), Seat.EAST)
        t.until(5_000) { o.client.follow != null }
        val sub = t.joiner(t.config(Role.PLAYER, "sub", Seat.EAST, replace = true))
        t.until(5_000) { sub.client.phase == ClientPhase.PENDING }
        assertIs<SessionHost.ApprovalResult.Admitted>(t.host.approve(t.host.pendingAdmissions().single().connectionId, sub.client.confirmationCode!!))
        t.until(5_000) { o.client.grants.all { it.status == GrantStatus.REVOKED } }
        assertNull(o.client.follow)
    }

    @Test fun observerDepartureEndsItsGrant() {
        val (t, players, o) = table(87)
        val south = players.getValue(Seat.SOUTH)
        t.follow(o, south, Seat.SOUTH)
        assertIs<Receipt>(t.command(o, LeaveBody))
        t.until(5_000) { south.client.grants.all { it.status == GrantStatus.REVOKED } }
    }

    @Test fun competitiveRoomDisablesPrivateFollow() {
        val t = Table(seed = 88, hostConfig = HostConfig(roomId = ROOM, recovery = TEST_RECOVERY, policy = RoomPolicy.COMPETITIVE))
        t.fourPlayers()
        val o = t.observer("watcher")
        assertEquals(false, o.client.policy?.privateFollow)
        assertEquals(false, o.client.policy?.hints)
        assertEquals(RejectionCode.FOLLOW_DISABLED, assertIs<RejectionReceipt>(t.command(o, FollowRequestBody(Seat.SOUTH))).code)
    }

    @Test fun friendlyIsTheDefaultPolicy() {
        assertEquals(RoomPolicy.FRIENDLY, HostConfig(roomId = ROOM, recovery = TEST_RECOVERY).policy)
    }
}
