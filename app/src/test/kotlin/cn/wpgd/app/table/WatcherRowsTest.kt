package cn.wpgd.app.table

import cn.wpgd.app.FakeSession
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.playingView
import cn.wpgd.app.session.wire.AbsenceMode
import cn.wpgd.app.session.wire.GrantStatus
import cn.wpgd.app.session.wire.GrantView
import cn.wpgd.app.session.wire.RoomMode
import cn.wpgd.app.session.wire.RoomPolicyView
import cn.wpgd.app.session.wire.RoomSettingsView
import cn.wpgd.app.session.wire.RoomView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Test

/** T10: every active grant on this seat is its own watcher row, even when two watchers share a nickname. */
class WatcherRowsTest {
    private val session = FakeSession(playingView(hand = listOf("0S3", "0S4")))
    private val vm = TableViewModel(session, PreferencesRepository(InMemoryKeyValueStore()), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
    private val settings = RoomSettingsView(RoomPolicyView(RoomMode.FRIENDLY, hints = false, privateFollow = true), 60_000, AbsenceMode.SUBSTITUTE, AbsenceMode.SUBSTITUTE)

    private fun grant(id: String, name: String) = GrantView(id, name, Seat.SOUTH, GrantStatus.ACTIVE, 1)

    private fun room(observers: List<String>) = RoomView(lobby = false, paused = false, settings = settings, settingsRevision = 1, seats = emptyList(), observers = observers, yourSeat = Seat.SOUTH)

    @Test fun twoWatchersWithTheSameNicknameKeepTheirOwnGrants() {
        session.update { it.copy(grants = listOf(grant("g1", "阿明"), grant("g2", "阿明")), roomView = room(listOf("阿明", "阿明"))) }
        val rows = vm.state().watchers
        assertEquals(listOf("g1", "g2"), rows.map { it.grantId })
        assertEquals(listOf(true, true), rows.map { it.seesYourHand })
    }

    @Test fun aSharedNicknameWithOneGrantShowsOneWatcherSeeingTheHand() {
        session.update { it.copy(grants = listOf(grant("g1", "阿明")), roomView = room(listOf("阿明", "阿明"))) }
        val rows = vm.state().watchers
        assertEquals(listOf("g1", null), rows.map { it.grantId })
        assertEquals(listOf(true, false), rows.map { it.seesYourHand })
    }

    @Test fun aGrantWhoseWatcherIsNotInTheRosterStillShows() {
        session.update { it.copy(grants = listOf(grant("g1", "阿明"), grant("g2", "阿明")), roomView = room(listOf("阿明"))) }
        assertEquals(listOf("g1", "g2"), vm.state().watchers.map { it.grantId })
    }
}
