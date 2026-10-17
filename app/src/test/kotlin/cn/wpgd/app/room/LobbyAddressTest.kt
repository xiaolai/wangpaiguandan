package cn.wpgd.app.room

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.lan.LanEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Join by address: the host's lobby knows its listening port; a joiner's lobby shows none. */
class LobbyAddressTest {
    @Test fun theHostsLobbyCarriesItsListeningPortAndAJoinersDoesNot() {
        val rig = RoomRig(seed = 811)
        val host = rig.host("阿梅")
        val port = (rig.advertOf(host).endpoint as LanEndpoint).port
        assertEquals(port, LobbyUi.of(host.state, null)!!.listenPort)
        val zhou = rig.join(rig.phone("老周"), host, Seat.EAST)
        assertNull(LobbyUi.of(zhou.state, null)!!.listenPort)
    }

    @Test fun shownAddressesPairEachHostAddressWithThePort() {
        assertEquals(
            listOf(JoinAddress("192.168.43.1", 41234, "192.168.43.1:41234"), JoinAddress("fd00:0:0:0:0:0:0:1", 41234, "[fd00:0:0:0:0:0:0:1]:41234")),
            LobbyUi.joinAddresses(41234, listOf("192.168.43.1", "fd00:0:0:0:0:0:0:1")),
        )
        assertEquals(emptyList<JoinAddress>(), LobbyUi.joinAddresses(null, listOf("192.168.43.1")))
        assertEquals(emptyList<JoinAddress>(), LobbyUi.joinAddresses(41234, emptyList()))
    }
}
