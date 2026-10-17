package cn.wpgd.app.table

import cn.wpgd.app.TestProfiles
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.TablePreferences
import cn.wpgd.app.owner.ConnectionState
import cn.wpgd.app.owner.OwnerLifecycle
import cn.wpgd.app.owner.OwnerState
import cn.wpgd.app.owner.RoomCondition
import cn.wpgd.app.owner.TableSnapshot
import cn.wpgd.app.playingView
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round 3 #19: deal numbers restart in every match, so a selection or group made in deal 1 of one
 * match never carries into deal 1 of the rematch (card ids repeat across deals).
 */
class DealIdentityTest {
    private val mapper = TableUiMapper(TestProfiles.rules)
    private val base = playingView(hand = listOf("0S5", "0S6", "0S7"))

    private fun state(matchNumber: Int, dealNumber: Int) = OwnerState(
        OwnerLifecycle.RUNNING,
        TableSnapshot(
            "t-1", Seat.SOUTH, 1, 10, base.copy(match = base.match.copy(matchNumber = matchNumber, dealNumber = dealNumber)),
            ConnectionState.CONNECTED, RoomCondition.ACTIVE, null, null, hosting = true,
        ),
    )

    private val local = LocalTableState(selection = HandSelection(setOf("0S5")), manualGroups = listOf(listOf("0S6", "0S7")), matchNumber = 1, dealNumber = 1)

    @Test fun theSameDealNumberOfTheNextMatchIsAnotherDeal() {
        val same = mapper.map(state(1, 1), local, TablePreferences())
        assertEquals(setOf("0S5"), same.hand.selectedIds.toSet())
        val next = mapper.map(state(2, 1), local, TablePreferences())
        assertEquals("no selection from the previous match", emptySet<String>(), next.hand.selectedIds.toSet())
        assertEquals(TableUiMapper(TestProfiles.rules).map(state(2, 1), LocalTableState(), TablePreferences()).hand, next.hand)
    }
}
