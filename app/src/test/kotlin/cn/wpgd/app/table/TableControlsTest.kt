package cn.wpgd.app.table

import cn.wpgd.app.FakeSession
import cn.wpgd.app.card
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.TransferView
import cn.wpgd.app.core.match.TributeKind
import cn.wpgd.app.core.match.TributeView
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.owner.ConnectionState
import cn.wpgd.app.owner.RoomCondition
import cn.wpgd.app.playingView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GDN-088, audit A22, T8 and H8: the in-table pause, resume and reconnect controls come from the
 * state, and the intents do nothing when the state does not offer them.
 */
class TableControlsTest {
    private val session = FakeSession(playingView(hand = listOf("0S3", "0S4")))
    private val vm = TableViewModel(session, PreferencesRepository(InMemoryKeyValueStore()), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    @Test fun theHostPausesAndResumesAndControlsFollowTheRoom() {
        var ui = vm.state()
        assertTrue(ui.canPause)
        assertFalse(ui.canResume)
        vm.resume()
        assertEquals(RoomCondition.ACTIVE, session.state.value.table!!.room)

        vm.pause()
        ui = vm.state()
        assertEquals(RoomCondition.PAUSED, ui.room)
        assertFalse(ui.canPause)
        assertTrue(ui.canResume)
        assertFalse(ui.controlsEnabled)
        assertFalse(ui.actions.play || ui.actions.pass)

        vm.resume()
        assertTrue(vm.state().controlsEnabled)
    }

    @Test fun aGuestCannotPause() {
        session.update { it.copy(hosting = false) }
        assertFalse(vm.state().canPause)
        vm.pause()
        assertEquals(RoomCondition.ACTIVE, session.state.value.table!!.room)
    }

    @Test fun reconnectIsOfferedOnlyForAStaleOrLostLink() {
        assertFalse(vm.state().canReconnect)
        vm.reconnect()
        assertEquals(0, session.reconnects)

        for (c in listOf(ConnectionState.STALE, ConnectionState.DISCONNECTED)) {
            session.update { it.copy(connection = c) }
            assertTrue(vm.state().canReconnect)
            assertFalse(vm.state().controlsEnabled)
        }
        vm.reconnect()
        assertEquals(1, session.reconnects)
        assertFalse(vm.state().canReconnect)

        session.update { it.copy(connection = ConnectionState.CONNECTING) }
        assertFalse("a rejoin already in progress is not restarted", vm.state().canReconnect)
    }

    @Test fun theTributeStepIsInTheState() {
        val tribute = TributeView(
            TributeKind.SINGLE, listOf(Seat.SOUTH), listOf(Seat.EAST),
            listOf(TransferView(Seat.SOUTH, Seat.EAST, card("0SA"))), emptyList(), emptyList(), null, emptyList(),
        )
        session.setView(playingView(hand = listOf("0S3", "0S4"), phase = MatchPhase.RETURN, tribute = tribute))
        val t = vm.state().tribute!!
        assertEquals(listOf(Seat.SOUTH), t.givers)
        assertEquals(listOf(TransferUi(Seat.SOUTH, Seat.EAST, card("0SA"))), t.given)
        assertEquals(null, t.resistedBy)

        val resisted = tribute.copy(given = emptyList(), resistedBy = listOf(Seat.SOUTH), resistedJokers = listOf(card("0BJ"), card("1BJ")))
        session.setView(playingView(hand = listOf("0S3", "0BJ", "1BJ"), tribute = resisted))
        assertEquals(listOf(Seat.SOUTH), vm.state().tribute!!.resistedBy)
        assertEquals(2, vm.state().tribute!!.resistedJokers.size)
    }

    /** Audit-fix #36: with jokers split between partners (double tribute), a giver holding one may claim resistance. */
    @Test fun resistanceIsOfferedWhenPartnersMayHoldTheJokersBetweenThem() {
        assertTrue(session.rules.matchSettings.resistanceSplitBetweenPartners)
        val double = TributeView(TributeKind.DOUBLE, listOf(Seat.SOUTH, Seat.NORTH), listOf(Seat.EAST, Seat.WEST), emptyList(), emptyList(), emptyList(), null, emptyList())
        session.setView(playingView(hand = listOf("0S3", "0BJ"), phase = MatchPhase.TRIBUTE, tribute = double))
        assertTrue(vm.state().actions.resist)
        session.setView(playingView(hand = listOf("0S3", "0S4"), phase = MatchPhase.TRIBUTE, tribute = double))
        assertFalse("no big joker here: the partner claims if it holds both", vm.state().actions.resist)
        val single = TributeView(TributeKind.SINGLE, listOf(Seat.SOUTH), listOf(Seat.EAST), emptyList(), emptyList(), emptyList(), null, emptyList())
        session.setView(playingView(hand = listOf("0S3", "0BJ"), phase = MatchPhase.TRIBUTE, tribute = single))
        assertFalse("a single giver needs both", vm.state().actions.resist)
        session.setView(playingView(hand = listOf("0BJ", "1BJ"), phase = MatchPhase.TRIBUTE, tribute = single))
        assertTrue(vm.state().actions.resist)
    }

    /** Audit-fix #37: a later, smaller public count is shown even when an earlier answer said more. */
    @Test fun thePublicCountIsTheNewestOneOfTheDeal() {
        val base = cn.wpgd.app.TableFixtures.playingView(session.rules.profiles, hand = listOf("0S3"), reports = listOf(cn.wpgd.app.core.match.CountReport(Seat.EAST, 3)))
        val view = base.copy(match = base.match.copy(answers = listOf(cn.wpgd.app.core.match.CountAnswer(Seat.SOUTH, Seat.EAST, 6))))
        session.setView(view)
        assertEquals(3, vm.state().seats.single { it.seat == Seat.EAST }.publicCount)
    }

    @Test fun theRulesSummaryNamesBothProfilesAndTheirHashes() {
        val r = vm.rulesSummary
        assertEquals(session.rules.profiles.base.id, r.baseId)
        assertEquals(session.rules.profiles.match.id, r.matchId)
        // P1/M1 are signed (G1a, G1b): the summary shows the profiles without the provisional mark.
        assertEquals(session.rules.profiles.base.provisional || session.rules.profiles.match.provisional, r.provisional)
        assertFalse(r.provisional)
        assertEquals(session.rules.profiles.base.hash.take(12), r.baseHash)
    }
}
