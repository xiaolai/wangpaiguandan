package cn.wpgd.app.room

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.PreferencesRepository
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.session.storage.InMemoryStorage
import cn.wpgd.app.table.TableViewModel
import cn.wpgd.app.ui.TableActions
import cn.wpgd.app.ui.hostLeaveConfirmed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit-fix #45: a suspend chosen from a prompt whose expiry has passed is refused by the owner, so
 * the screen must not close the table either; the host is shown a fresh prompt instead.
 */
class HostLeaveExpiryTest {
    @Test fun anExpiredSuspendKeepsTheTableOpen() {
        val rig = RoomRig(seed = 121)
        val host = rig.host("阿梅", storage = InMemoryStorage(), rooms = RoomStore(InMemoryKeyValueStore()))
        val zhou = rig.phone("老周")
        rig.startedRoom(host, mapOf(Seat.EAST to zhou))
        var sent = 0
        val vm = TableViewModel(host.owner, PreferencesRepository(InMemoryKeyValueStore()), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val actions = TableActions(vm, { error("the table closes only once the owner confirms") }, host.owner, rig.clock, onHostLeaveSent = { sent++ })

        val prompt = checkNotNull(host.owner.prepareLeave())
        rig.clock.now = prompt.expiresAt
        assertFalse(actions.leaveHosted(prompt.suspend()))
        rig.run(500)
        assertEquals("nothing was sent", 0, sent)
        assertFalse(hostLeaveConfirmed(host.table!!.room))
        assertEquals(JoinPhase.ADMITTED, zhou.table!!.join!!.phase)

        val fresh = checkNotNull(host.owner.prepareLeave())
        assertTrue(fresh.expiresAt > rig.clock.now)
        assertTrue(actions.leaveHosted(fresh.suspend()))
        assertEquals(1, sent)
        rig.until { zhou.table?.join?.phase == JoinPhase.SUSPENDED }
        rig.until { hostLeaveConfirmed(host.table?.room) }
    }

    /** With fork-B's storage-failure contract: a suspend that cannot be saved is never confirmed, so the table stays open. */
    @Test fun aSuspendThatCannotBeSavedIsNotConfirmed() {
        val rig = RoomRig(seed = 122)
        val storage = InMemoryStorage()
        val host = rig.host("阿梅", storage = storage, rooms = RoomStore(InMemoryKeyValueStore()))
        val zhou = rig.phone("老周")
        rig.startedRoom(host, mapOf(Seat.EAST to zhou))
        storage.failWhen = { kind, _ -> kind == InMemoryStorage.Kind.REPLACE_WRITE || kind == InMemoryStorage.Kind.REPLACE_SYNC || kind == InMemoryStorage.Kind.REPLACE_COMMIT }
        host.owner.leaveHosted(checkNotNull(host.owner.prepareLeave()).suspend())
        rig.run(10_000)
        assertFalse(hostLeaveConfirmed(host.table?.room))
    }
}
