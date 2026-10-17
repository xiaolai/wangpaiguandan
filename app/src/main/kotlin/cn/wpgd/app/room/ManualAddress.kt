package cn.wpgd.app.room

import cn.wpgd.app.lan.LanEndpoint

/** Why a typed address cannot be used; each has a message (strings `address_*`). */
enum class AddressProblem(val messageKey: String) {
    HOST_EMPTY("address.host_empty"),

    /** "http://…": only the address itself is wanted. */
    HOST_HAS_SCHEME("address.host_has_scheme"),

    /** "…/room", "…?x": no path or query. */
    HOST_HAS_PATH("address.host_has_path"),

    /** "192.168.1.2:41234" in the address field: the port has its own field. */
    HOST_HAS_PORT("address.host_has_port"),
    HOST_INVALID("address.host_invalid"),
    PORT_EMPTY("address.port_empty"),

    /** Not plain decimal digits. */
    PORT_INVALID("address.port_invalid"),
    PORT_OUT_OF_RANGE("address.port_out_of_range"),
}

/** A typed address: usable, or why not. */
sealed interface AddressInput {
    data class Valid(val endpoint: LanEndpoint) : AddressInput

    data class Invalid(val problem: AddressProblem) : AddressInput
}

/**
 * Join by address: what a user types into the two fields (host, port), checked strictly before
 * anything is dialled. The host is an IPv4 literal (dotted decimal, no leading zeros), an IPv6
 * literal (optionally in brackets, optionally with a zone such as `%wlan0`) or a hostname (RFC 1123
 * labels); the port is decimal 1–65535. Schemes, paths, user info and a port inside the host field
 * are refused with a message saying what to change. Surrounding spaces are ignored. Pure: no DNS.
 */
object ManualAddress {
    const val MAX_HOST = 253

    fun parse(hostText: String, portText: String): AddressInput {
        val host = hostText.trim()
        if (host.isEmpty()) return AddressInput.Invalid(AddressProblem.HOST_EMPTY)
        if ("://" in host) return AddressInput.Invalid(AddressProblem.HOST_HAS_SCHEME)
        if (host.any { it == '/' || it == '?' || it == '#' }) return AddressInput.Invalid(AddressProblem.HOST_HAS_PATH)
        val literal = hostLiteral(host) ?: return AddressInput.Invalid(if (hasPort(host)) AddressProblem.HOST_HAS_PORT else AddressProblem.HOST_INVALID)
        val port = portText.trim()
        if (port.isEmpty()) return AddressInput.Invalid(AddressProblem.PORT_EMPTY)
        if (!port.all(::asciiDigit)) return AddressInput.Invalid(AddressProblem.PORT_INVALID)
        val value = if (port.length > 5) null else port.toInt()
        if (value == null || value !in 1..65535) return AddressInput.Invalid(AddressProblem.PORT_OUT_OF_RANGE)
        return AddressInput.Valid(LanEndpoint(literal, value))
    }

    /** "host:port" as shown on the host's lobby and typed elsewhere; an IPv6 host in brackets. */
    fun format(host: String, port: Int): String = if (':' in host) "[$host]:$port" else "$host:$port"

    /** The host to dial, or null when [host] is none of the accepted forms. */
    private fun hostLiteral(host: String): String? {
        if (host.length > MAX_HOST + 2) return null
        if (host.startsWith('[')) {
            if (!host.endsWith(']')) return null
            return host.substring(1, host.length - 1).takeIf(::ipv6)
        }
        return host.takeIf { ipv4(it) || ipv6(it) || hostname(it) }
    }

    /** "host:port" or "[v6]:port" typed into the host field. */
    private fun hasPort(host: String): Boolean {
        if (host.startsWith('[')) {
            val end = host.indexOf("]:")
            return end > 0 && ipv6(host.substring(1, end)) && host.substring(end + 2).let { it.isNotEmpty() && it.all(::asciiDigit) }
        }
        val i = host.lastIndexOf(':')
        if (i <= 0 || host.indexOf(':') != i) return false
        val before = host.substring(0, i)
        val after = host.substring(i + 1)
        return after.isNotEmpty() && after.all(::asciiDigit) && (ipv4(before) || hostname(before))
    }

    private fun asciiDigit(c: Char) = c in '0'..'9'

    private fun hexDigit(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun ipv4(s: String): Boolean {
        val parts = s.split('.')
        return parts.size == 4 && parts.all { p ->
            p.length in 1..3 && p.all(::asciiDigit) && (p.length == 1 || p[0] != '0') && p.toInt() <= 255
        }
    }

    private val ZONE = Regex("[A-Za-z0-9_.-]{1,15}")

    private fun ipv6(text: String): Boolean {
        val pct = text.indexOf('%')
        val s = if (pct >= 0) text.substring(0, pct) else text
        if (pct >= 0 && !ZONE.matches(text.substring(pct + 1))) return false
        if (':' !in s) return false
        val halves = s.split("::")
        if (halves.size > 2) return false
        fun groups(part: String): List<String> = if (part.isEmpty()) emptyList() else part.split(':')
        val head = groups(halves[0])
        val tail = if (halves.size == 2) groups(halves[1]) else emptyList()
        val all = head + tail
        // An embedded IPv4 address may stand in for the last two groups: the literal's final 32 bits,
        // so nothing may follow it, not even "::" ("192.0.2.1::" is refused, as the JDK refuses it).
        val ipv4Ends = halves.size == 1 || tail.isNotEmpty()
        var count = 0
        for ((i, g) in all.withIndex()) {
            count += when {
                '.' in g -> if (i == all.lastIndex && ipv4Ends && ipv4(g)) 2 else return false
                g.length in 1..4 && g.all(::hexDigit) -> 1
                else -> return false
            }
        }
        return if (halves.size == 2) count <= 7 else count == 8
    }

    private val LABEL = Regex("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

    private fun hostname(s: String): Boolean {
        if (s.length > MAX_HOST) return false
        val labels = s.split('.')
        // A last label of digits only would be a malformed IPv4 address, not a name.
        return labels.all(LABEL::matches) && labels.last().any { it.isLetter() }
    }
}
