package cn.wpgd.app.ble

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.nickname
import cn.wpgd.app.owner.JoinPhase
import cn.wpgd.app.room.MediumFallback
import cn.wpgd.app.room.RoomMedium
import cn.wpgd.app.room.RoomRig
import cn.wpgd.app.session.wire.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GDN-021: a failed Bluetooth join offers Wi-Fi; a label never claims Bluetooth for a Wi-Fi room. */
class BleFailureFallbackTest {
    @Test fun aBluetoothRoomThatCannotBeReachedOffersTheSameWifi() {
        val rig = BleRig(41)
        val host = rig.host()
        val zhou = rig.phone("老周")
        val advert = rig.find(zhou, host)
        host.owner.shutdown() // the host is gone before the joiner connects
        rig.scheduler.runFor(1_000)
        assertTrue(zhou.owner.joinRoom(advert, Role.PLAYER, Seat.EAST, nickname("老周")))
        rig.until { zhou.table?.join?.phase == JoinPhase.FAILED }
        val t = zhou.table!!
        assertEquals(RoomMedium.BLUETOOTH, t.info!!.medium)
        assertTrue(MediumFallback.offersWifi(t.info, t.join!!.phase))
        rig.shutdown()
    }

    @Test fun aLostBluetoothHostOffersWifiWhileRejoining() {
        val rig = BleRig(42)
        val host = rig.host()
        val zhou = rig.join(rig.phone("老周"), host, Seat.EAST)
        assertFalse(MediumFallback.offersWifi(zhou.table!!.info, zhou.table!!.join!!.phase))
        rig.air.cut(rig.radios.getValue(host).address)
        rig.until { zhou.table?.join?.phase == JoinPhase.HOST_LOST }
        assertTrue(MediumFallback.offersWifi(zhou.table!!.info, zhou.table!!.join!!.phase))
        rig.shutdown()
    }

    @Test fun aWifiRoomIsNeverLabelledBluetoothAndOffersNoSwitch() {
        val rig = RoomRig(43)
        val host = rig.host()
        val zhou = rig.join(rig.phone("老周"), host, Seat.EAST)
        assertEquals(RoomMedium.LAN, zhou.table!!.info!!.medium)
        assertEquals(RoomMedium.LAN, host.table!!.info!!.medium)
        for (phase in JoinPhase.entries) assertFalse(MediumFallback.offersWifi(zhou.table!!.info, phase))
    }
}
