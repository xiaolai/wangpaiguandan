package cn.wpgd.app.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * The host's lobby shows the addresses others can type (join by address): those of its Wi-Fi or
 * hotspot interface on a private network, never the mobile-data, loopback or VPN ones.
 */
class HostAddressesTest {
    private fun a(s: String): InetAddress = InetAddress.getByName(s)

    private fun iface(name: String, vararg addrs: String, up: Boolean = true, loopback: Boolean = false, virtual: Boolean = false) =
        NetInterfaceInfo(name, up, loopback, virtual, addrs.map(::a))

    @Test fun wifiAndHotspotPrivateAddressesAreShownIpv4First() {
        val got = HostAddresses.select(
            listOf(
                iface("wlan0", "fd12:3456::7", "192.168.1.23", "fe80::1"),
                iface("ap0", "192.168.43.1"),
                iface("rmnet_data0", "10.120.4.5", "2408:8456::1"),
                iface("lo", "127.0.0.1", "::1", loopback = true),
                iface("tun0", "10.8.0.2"),
            ),
        )
        assertEquals(listOf("192.168.1.23", "192.168.43.1", "fd12:3456:0:0:0:0:0:7"), got)
    }

    @Test fun emulatorAndTetheringInterfacesCount() {
        assertEquals(listOf("10.0.2.16", "10.0.2.15"), HostAddresses.select(listOf(iface("wlan0", "10.0.2.16"), iface("eth0", "10.0.2.15"))))
        assertEquals(listOf("172.20.10.1"), HostAddresses.select(listOf(iface("swlan0", "172.20.10.1"))))
    }

    @Test fun publicDownAndLinkLocalAddressesAreNotShown() {
        assertTrue(HostAddresses.select(listOf(iface("wlan0", "8.8.8.8", "169.254.3.4", "fe80::2"), iface("wlan1", "192.168.0.9", up = false))).isEmpty())
    }

    @Test fun noInterfaceMeansNothingToShow() {
        assertTrue(HostAddresses.select(emptyList()).isEmpty())
    }

    @Test fun duplicatesAreShownOnce() {
        assertEquals(listOf("192.168.1.23"), HostAddresses.select(listOf(iface("wlan0", "192.168.1.23", "192.168.1.23"))))
    }
}
