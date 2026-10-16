package cn.wpgd.app.lan

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/** One network interface as [HostAddresses] needs it (a plain value, so selection is testable). */
data class NetInterfaceInfo(val name: String, val up: Boolean, val loopback: Boolean, val virtual: Boolean, val addresses: List<InetAddress>)

/**
 * Join by address: the addresses a host's lobby shows, so others can type one when discovery does
 * not reach them (phone hotspots, isolated guest Wi-Fi, emulators). Only interfaces of a local
 * network this phone shares with others (Wi-Fi, its own hotspot, Ethernet or USB tethering) and only
 * private addresses on them: never mobile data, loopback, VPN tunnels or public addresses. IPv4 first
 * (what people type), then IPv6 unique-local addresses. Nothing is shown when there is none.
 */
object HostAddresses {
    private val LOCAL_NETWORK = Regex("(wlan|swlan|ap|softap|wifi|eth|rndis|usb|bt-pan)[0-9]*")

    fun select(interfaces: List<NetInterfaceInfo>): List<String> {
        val chosen = interfaces.filter { it.up && !it.loopback && !it.virtual && LOCAL_NETWORK.matches(it.name) }
        val v4 = chosen.flatMap { i -> i.addresses.filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress } }
        val v6 = chosen.flatMap { i -> i.addresses.filterIsInstance<Inet6Address>().filter(::uniqueLocal) }
        return (v4 + v6).map { checkNotNull(it.hostAddress).substringBefore('%') }.distinct()
    }

    /** This device's addresses now; empty when the platform cannot list interfaces. */
    fun current(): List<String> = try {
        select(
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map {
                NetInterfaceInfo(it.name, it.isUp, it.isLoopback, it.isVirtual, it.inetAddresses.toList())
            },
        )
    } catch (_: java.net.SocketException) {
        emptyList()
    }

    /** fc00::/7 (RFC 4193), the IPv6 counterpart of a private IPv4 network. */
    private fun uniqueLocal(a: Inet6Address): Boolean = (a.address[0].toInt() and 0xFE) == 0xFC
}
