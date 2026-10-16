package cn.wpgd.app.room

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.owner.RoomCondition
import cn.wpgd.app.session.storage.InMemoryStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the host cannot save its suspend or end, the room pauses and closes nothing: the owner must
 * not withdraw the advert or stop serving, so members stay connected and the room can be left again.
 */
class LeaveHostedFailureTest {
    private fun failingWrites(s: InMemoryStorage) {
        s.failWhen = { kind, _ ->
            kind == InMemoryStorage.Kind.REPLACE_WRITE || kind == InMemoryStorage.Kind.REPLACE_SYNC || kind == InMemoryStorage.Kind.REPLACE_COMMIT
        }
    }

    private fun attempt(seed: Long, choose: (LeavePrompt) -> LeaveChoice) {
        val rig = RoomRig(seed)
        val storage = InMemoryStorage()
        val host = rig.host("阿梅", storage = storage, rooms = RoomStore(InMemoryKeyValueStore()))
        val zhou = rig.phone("老周")
        rig.startedRoom(host, mapOf(Seat.EAST to zhou))
        val id = host.table!!.tableId
        failingWrites(storage)

        host.owner.leaveHosted(choose(checkNotNull(host.owner.prepareLeave())))
        rig.run(10_000) // well past the linger: a wrongly withdrawn table would be gone by now

        assertTrue("still announced", rig.network.directory.announced.any { it.roomId == id })
        assertEquals("members stay connected", JoinPhase.ADMITTED, zhou.table!!.join!!.phase)
        assertNotEquals(RoomCondition.SUSPENDED, host.table!!.room)
        assertNotEquals(RoomCondition.ENDED, host.table!!.room)
    }

    @Test fun aSuspendThatCannotBeSavedKeepsServing() = attempt(131) { it.suspend() }

    @Test fun anEndThatCannotBeSavedKeepsServing() = attempt(132) { it.end() }
}
