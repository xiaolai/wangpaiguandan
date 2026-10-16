package cn.wpgd.app.session

import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.core.state.StateHash
import cn.wpgd.app.session.client.ClientPhase
import cn.wpgd.app.session.host.SeatPolicy
import cn.wpgd.app.session.transport.LinkFaults
import cn.wpgd.app.session.wire.CommandMessage
import cn.wpgd.app.session.wire.PassBody
import cn.wpgd.app.session.wire.PlayBody
import cn.wpgd.app.session.wire.RejectionCode
import cn.wpgd.app.session.wire.RejectionReceipt
import cn.wpgd.app.session.wire.Role
import cn.wpgd.app.session.wire.WireAssignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GDN-025, GDN-053, audit A16, A18, A21: callbacks, frames and tasks of an old epoch or an old
 * connection generation cannot close, mutate or act.
 */
class StaleGenerationTest {
    private val leadLowest = SeatPolicy { o, opts ->
        if (opts.mayPass) PassBody else opts.plays.minBy { it.cards.size }.let { a ->
            PlayBody(a.cards.map { it.id }, a.declaration?.map { WireAssignment(it.rank, it.suit) }, a.combination.type)
        }
    }

    @Test fun oldEpochCallbacksCannotCloseMutateOrAct() {
        val t = Table(seed = 71)
        val delayed = LinkFaults(latencyMs = 2_000)
        val oldPlayers = Seat.entries.associateWith { if (it == Seat.SOUTH) t.player(it, delayed) else t.player(it) }
        t.startDeal()
        val oldSouth = oldPlayers.getValue(Seat.SOUTH)
        oldSouth.link.deliverAfterClose = true
        val oldListener = checkNotNull(oldSouth.link.hostEnd.installedListener)
        // A command leaves the old SOUTH just before the room restarts; it lands in the new epoch.
        oldSouth.client.submit(PassBody)
        t.run(10)
        t.host.restartRoom()
        val players = t.fourPlayers()
        t.startDeal()
        val hash = StateHash.of(checkNotNull(t.host.state.deal))
        val connections = t.host.connectionCount
        val stale = t.host.metrics.staleCallbacks

        t.run(5_000) // the delayed old frames arrive now
        oldListener.onClosed()
        oldListener.onReceive(ByteArray(40) { 7 })
        oldListener.onWritable()

        assertEquals(hash, StateHash.of(checkNotNull(t.host.state.deal)), "old-epoch traffic changed the game")
        assertEquals(connections, t.host.connectionCount, "old-epoch callbacks closed a connection")
        assertTrue(t.host.members().all { it.connected }, "old-epoch callbacks disconnected a member")
        assertTrue(t.host.metrics.staleCallbacks >= stale + 3)
        assertTrue(players.values.all { it.client.phase == ClientPhase.ADMITTED })
    }

    @Test fun supersededConnectionCannotDisconnectTheMembersNewConnection() {
        val t = Table(seed = 72)
        val players = t.fourPlayers()
        val south = players.getValue(Seat.SOUTH)
        val oldListener = checkNotNull(south.link.hostEnd.installedListener)
        // The same member rejoins on a new connection with its capability (no approval needed).
        val again = t.joiner(t.config(Role.PLAYER, "south", Seat.SOUTH, rejoin = south.client.capability))
        t.until(5_000) { again.client.phase == ClientPhase.ADMITTED }
        assertEquals(1, t.host.metrics.rejoined)
        // The old connection's late close callback arrives afterwards (audit A16).
        oldListener.onClosed()
        t.run(100)
        assertTrue(t.host.members().single { it.seat == Seat.SOUTH }.connected, "a superseded connection closed the new one")
        t.startDeal()
        if (t.turn() == Seat.SOUTH) assertTrue(t.command(again, simpleBody(again.client.memberView!!)) !is RejectionReceipt)
    }

    @Test fun aiTaskOfAnOldEpochOrAnOldControllerDoesNotAct() {
        val t = Table(seed = 73)
        Seat.entries.forEach { t.host.setAi(it, leadLowest) }
        t.host.advance(); t.host.advance() // deal, open play: the AI to lead now has a task scheduled
        assertEquals(MatchPhase.PLAYING, t.host.state.phase)
        t.host.restartRoom()
        t.run(5_000)
        assertEquals(0, t.host.revision, "an old-epoch AI task acted in the new room")
        assertTrue(t.host.metrics.staleAiTasks >= 1)

        // Same epoch: the AI is replaced by a new controller before its task fires.
        Seat.entries.forEach { t.host.setAi(it, leadLowest) }
        t.host.advance(); t.host.advance()
        val revision = t.host.revision
        val actor = t.turn()
        val staleBefore = t.host.metrics.staleAiTasks
        t.host.setAi(actor, leadLowest) // new token; the pending task belongs to the old controller
        // Both tasks fall due together: the old controller's is refused, the new one plays once.
        t.run(t.hostConfig.aiDelayMs)
        assertEquals(staleBefore + 1, t.host.metrics.staleAiTasks, "the old controller's task must be inert")
        assertEquals(revision + 1, t.host.revision, "exactly one action for the turn")
    }

    @Test fun aiTaskIsBoundToTheTurnRevision() {
        val t = Table(seed = 74)
        Seat.entries.filter { it != Seat.SOUTH }.forEach { t.host.setAi(it, leadLowest) }
        val south = t.player(Seat.SOUTH)
        Bot(t, south).poke()
        t.host.advance(); t.host.advance()
        val before = t.host.metrics.commits
        t.until(60_000) { t.host.metrics.commits >= before + 30 }
        // Every AI commit happened at the revision its task was scheduled for; no stale task acted.
        assertEquals(0, t.host.metrics.aiRejected, "an AI acted on a state it was not scheduled for")
    }

    @Test fun oldEpochCommandIsRejectedTyped() {
        val t = Table(seed = 75)
        val players = t.fourPlayers()
        t.startDeal()
        val p = players.getValue(t.turn())
        p.client.sendRaw(CommandMessage(p.client.epoch!! - 1, p.client.capability!!, "old-epoch", p.client.revision, PassBody))
        t.until(5_000) { p.received.any { it is RejectionReceipt && it.commandId == "old-epoch" } }
        assertEquals(RejectionCode.STALE_EPOCH, p.received.filterIsInstance<RejectionReceipt>().single().code)
    }

    @Test fun aClientIgnoresCallbacksOfItsOwnOldConnection() {
        val t = Table(seed = 76)
        val south = t.player(Seat.SOUTH)
        val oldClientListener = checkNotNull(south.link.clientEnd.installedListener)
        south.link.disconnect()
        t.until(5_000) { south.client.phase == ClientPhase.CLOSED }
        val link = t.network.link()
        t.host.accept(link.hostEnd)
        south.client.connect(link.clientEnd)
        t.until(5_000) { south.client.phase == ClientPhase.ADMITTED }
        val stale = south.client.metrics.staleCallbacks
        oldClientListener.onClosed()
        assertEquals(ClientPhase.ADMITTED, south.client.phase, "an old connection's callback closed the new one")
        assertTrue(south.client.metrics.staleCallbacks > stale)
    }
}
