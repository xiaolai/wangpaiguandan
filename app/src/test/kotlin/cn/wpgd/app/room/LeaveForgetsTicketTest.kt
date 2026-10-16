package cn.wpgd.app.room

import cn.wpgd.app.core.state.Seat
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Round 3 #10: a player who leaves keeps no rejoin ticket, even when the leave's receipt arrives while still admitted. */
class LeaveForgetsTicketTest {
    @Test fun theTicketStaysForgottenThroughTheLeaveReceipt() {
        val rig = RoomRig(seed = 161)
        val host = rig.host("阿梅")
        val zhou = rig.join(rig.phone("老周"), host, Seat.EAST)
        val id = zhou.table!!.tableId
        assertNotNull("an admitted player keeps its ticket", zhou.identity.rejoinCapability(id))
        zhou.owner.leaveRoom()
        rig.until { zhou.owner.sessionRequestsInFlight == 0 }
        rig.run(1_000)
        assertNull(zhou.identity.rejoinCapability(id))
    }
}
