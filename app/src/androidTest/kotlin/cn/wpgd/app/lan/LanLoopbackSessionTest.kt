package cn.wpgd.app.lan

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.room.DeviceRoomRig
import cn.wpgd.app.room.joinPhase
import cn.wpgd.app.room.waitFor
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.session.wire.Role
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WI-09 on a device: production owners (platform crypto, session thread, AI worker) host and join a
 * room over real TCP sockets on the loopback interface, using the app's INTERNET permission. Covers
 * ask → compare codes → approve, a watcher, the lobby, dealing, a deliberate leave and ending the table.
 * Real multi-device discovery (NSD between phones) is not testable here.
 */
@RunWith(AndroidJUnit4::class)
class LanLoopbackSessionTest {
    private val rig = DeviceRoomRig()

    @After fun tearDown() = rig.shutdown()

    @Test fun aRoomRunsOverLoopbackSockets() {
        val host = rig.host()
        val zhou = rig.ask("老周", host, Seat.EAST)
        val code = zhou.state.value.table!!.join!!.code!!
        waitFor { host.state.value.table?.hostSide?.pending?.isNotEmpty() == true }
        assertEquals("both screens show the same six digits", code, host.state.value.table!!.hostSide!!.pending.single().code)
        assertTrue(code.matches(Regex("[0-9]{6}")))
        host.approve(host.state.value.table!!.hostSide!!.pending.single().connectionId)
        waitFor { zhou.state.value.joinPhase == JoinPhase.ADMITTED && zhou.state.value.table?.roomView != null }
        val chen = rig.join("陈阿姨", host, seat = null)
        assertEquals(Role.OBSERVER, chen.state.value.table!!.role)

        rig.start(host, listOf(zhou))
        assertEquals(27, zhou.state.value.table!!.view!!.deal!!.hand.size)
        assertNull("a watcher has no hand", chen.state.value.table!!.view)
        waitFor { chen.state.value.table?.spectatorView?.match?.dealNumber == 1 }

        // A deliberate leave reaches the host before the connection closes (the seat follows the policy).
        zhou.leaveRoom()
        waitFor(what = { "${host.state.value.table?.roomView?.seats}" }) {
            host.state.value.table!!.roomView!!.seats.single { it.seat == Seat.EAST }.occupant == Occupant.STAND_IN
        }
        // Ending the table reaches the watcher as an end, not as a lost host.
        host.leaveHosted(checkNotNull(host.prepareLeave()).end())
        waitFor(what = { "${chen.state.value.table?.join}" }) { chen.state.value.joinPhase == JoinPhase.ENDED }
        assertTrue(rig.directory.announced.isEmpty())
    }
}
