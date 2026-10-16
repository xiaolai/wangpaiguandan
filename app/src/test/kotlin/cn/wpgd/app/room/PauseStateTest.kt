package cn.wpgd.app.room

import cn.wpgd.app.TestProfiles
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.owner.RoomCondition
import cn.wpgd.app.owner.SubmitRefusal
import cn.wpgd.app.owner.SubmitResult
import cn.wpgd.app.session.wire.PassBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** H8, audit A22: the host's pause reaches every member and watcher; nothing moves until it resumes. */
class PauseStateTest {
    @Test fun pauseReachesEveryoneAndBlocksPlay() {
        val rig = RoomRig(seed = 91)
        val host = rig.host("阿梅")
        val chen = rig.phone("陈阿姨")
        val (hostSeat, zhou) = rig.startedRoom(host, mapOf(Seat.EAST to rig.phone("老周")), watchers = listOf(chen))
        host.owner.pause()
        rig.until { zhou.phone.table?.room == RoomCondition.PAUSED && chen.table?.room == RoomCondition.PAUSED }
        assertTrue(zhou.phone.table!!.roomView!!.paused)
        assertEquals(SubmitResult.Refused(SubmitRefusal.ROOM_NOT_ACTIVE), zhou.phone.owner.submit(PassBody))
        assertFalse(zhou.vm.state().controlsEnabled)
        assertFalse(hostSeat.vm.state().controlsEnabled)
        assertEquals(RoomCondition.PAUSED, WatchUi.of(chen.state, 0, TestProfiles.rules.matchSettings.maxAFailures)!!.room)
        val frozen = host.table!!.revision
        rig.run(10_000)
        assertEquals("nothing moved while paused (no AI either)", frozen, host.table!!.revision)

        host.owner.resume()
        rig.until { zhou.phone.table?.room == RoomCondition.ACTIVE && chen.table?.room == RoomCondition.ACTIVE }
        assertFalse(zhou.phone.table!!.roomView!!.paused)
    }
}
