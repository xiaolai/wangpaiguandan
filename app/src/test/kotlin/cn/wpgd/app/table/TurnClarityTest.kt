package cn.wpgd.app.table

import cn.wpgd.app.FakeSession
import cn.wpgd.app.card
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.core.match.TributeKind
import cn.wpgd.app.core.match.TributeView
import cn.wpgd.app.core.rules.CombinationType
import cn.wpgd.app.core.state.PublicPlay
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.playingView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GDN-077: whose play must be beaten and who acts, as data for the text and the screen reader. */
class TurnClarityTest {
    private val hand = listOf("0S5", "1S5", "0D9")
    private fun play(seat: Seat, vararg ids: String, type: CombinationType = CombinationType.SINGLE) = PublicPlay(seat, ids.map(::card), emptyList(), type)

    @Test fun youLead() {
        val t = TurnClarity.of(playingView(hand = hand, turn = Seat.SOUTH))
        assertEquals(TurnKey.YOU_LEAD, t.key)
        assertTrue(t.youAct)
        assertEquals(Seat.SOUTH, t.actor)
        assertNull(t.trickOwner)
    }

    @Test fun youMustBeatYourPartnersPair() {
        val t = TurnClarity.of(playingView(hand = hand, turn = Seat.SOUTH, trick = listOf(play(Seat.WEST, "0C3"), play(Seat.NORTH, "0C6", "1C6", type = CombinationType.PAIR)), passed = listOf(Seat.EAST)))
        assertEquals(TurnKey.YOU_BEAT_OR_PASS, t.key)
        assertEquals(Seat.NORTH, t.trickOwner)
        assertEquals(SeatRelation.PARTNER, t.trickOwnerRelation)
        assertEquals(CombinationType.PAIR, t.topType)
    }

    @Test fun anotherSeatFollowsAndTheScreenSaysWho() {
        val view = playingView(hand = hand, turn = Seat.WEST, trick = listOf(play(Seat.EAST, "0C9")))
        val t = TurnClarity.of(view)
        assertEquals(TurnKey.OTHER_FOLLOWS, t.key)
        assertFalse(t.youAct)
        assertEquals(Seat.WEST, t.actor)
        assertEquals(SeatRelation.PREVIOUS, t.actorRelation)
        assertEquals(Seat.EAST, t.trickOwner)
        assertEquals(SeatRelation.NEXT, t.trickOwnerRelation)

        val vm = TableViewModel(FakeSession(view), PreferencesRepository(InMemoryKeyValueStore()), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val ui = vm.uiState.value
        assertEquals(t, ui.turn)
        assertEquals(listOf(Seat.WEST), ui.seats.filter { it.acting }.map { it.seat })
        assertEquals(SeatLastAction.Played(listOf(card("0C9")), CombinationType.SINGLE, emptyList()), ui.seats.single { it.seat == Seat.EAST }.lastAction)
        assertTrue(ui.trick.top!!.isTop)
        assertEquals(listOf(SeatRelation.SELF, SeatRelation.NEXT, SeatRelation.PARTNER, SeatRelation.PREVIOUS), ui.seats.map { it.relation })
    }

    @Test fun tributeListsWhoStillOwes() {
        val tribute = TributeView(TributeKind.DOUBLE, listOf(Seat.SOUTH, Seat.NORTH), listOf(Seat.EAST, Seat.WEST), emptyList(), emptyList(), emptyList(), null, emptyList())
        val t = TurnClarity.of(playingView(hand = hand, phase = MatchPhase.TRIBUTE, tribute = tribute))
        assertEquals(TurnKey.YOU_GIVE_TRIBUTE, t.key)
        assertEquals(listOf(Seat.SOUTH, Seat.NORTH), t.waitingFor)
        val other = TurnClarity.of(playingView(seat = Seat.EAST, hand = hand, phase = MatchPhase.TRIBUTE, tribute = tribute))
        assertEquals(TurnKey.WAITING_FOR_TRIBUTE, other.key)
        assertFalse(other.youAct)
    }
}
