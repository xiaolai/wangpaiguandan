package cn.wpgd.app.room

import cn.wpgd.app.lan.LanEndpoint
import org.junit.Assert.assertEquals
import org.junit.Test

/** Join by address: what the user types is checked strictly before anything is dialled. */
class ManualAddressTest {
    private fun ok(host: String, port: String, expectHost: String, expectPort: Int) =
        assertEquals("$host / $port", AddressInput.Valid(LanEndpoint(expectHost, expectPort)), ManualAddress.parse(host, port))

    private fun bad(host: String, port: String, problem: AddressProblem) =
        assertEquals("$host / $port", AddressInput.Invalid(problem), ManualAddress.parse(host, port))

    @Test fun literalAddressesAndHostnamesAreAccepted() {
        ok("192.168.43.1", "41234", "192.168.43.1", 41234)
        ok("  10.0.2.2 ", " 5000 ", "10.0.2.2", 5000)
        ok("0.0.0.1", "1", "0.0.0.1", 1)
        ok("255.255.255.255", "65535", "255.255.255.255", 65535)
        ok("fd00::1", "41234", "fd00::1", 41234)
        ok("[fd00::1]", "41234", "fd00::1", 41234)
        ok("FE80::1%wlan0", "8080", "FE80::1%wlan0", 8080)
        ok("::ffff:192.168.1.5", "80", "::ffff:192.168.1.5", 80)
        ok("2001:db8:0:0:0:0:0:1", "80", "2001:db8:0:0:0:0:0:1", 80)
        ok("phone-host.local", "41234", "phone-host.local", 41234)
        ok("android-7f3a", "41234", "android-7f3a", 41234)
    }

    @Test fun emptyFieldsAreNamed() {
        bad("", "41234", AddressProblem.HOST_EMPTY)
        bad("   ", "41234", AddressProblem.HOST_EMPTY)
        bad("192.168.1.2", "", AddressProblem.PORT_EMPTY)
    }

    @Test fun schemesPathsAndUserInfoAreRefused() {
        bad("http://192.168.1.2", "80", AddressProblem.HOST_HAS_SCHEME)
        bad("wpgd://host", "80", AddressProblem.HOST_HAS_SCHEME)
        bad("192.168.1.2/room", "80", AddressProblem.HOST_HAS_PATH)
        bad("192.168.1.2?x=1", "80", AddressProblem.HOST_HAS_PATH)
        bad("me@192.168.1.2", "80", AddressProblem.HOST_INVALID)
    }

    @Test fun aPortTypedIntoTheAddressIsPointedOut() {
        bad("192.168.1.2:41234", "", AddressProblem.HOST_HAS_PORT)
        bad("192.168.1.2:41234", "41234", AddressProblem.HOST_HAS_PORT)
        bad("[fd00::1]:41234", "", AddressProblem.HOST_HAS_PORT)
        bad("phone.local:80", "80", AddressProblem.HOST_HAS_PORT)
    }

    @Test fun malformedHostsAreRefused() {
        for (h in listOf("256.1.1.1", "1.2.3", "1.2.3.4.5", "01.2.3.4", "1..2.3", "-bad.local", "bad-.local", "a b", "例子.中国", "fd00::1::2", "12345::1", "::g", "[fd00::1", "x".repeat(64) + ".local", "a." .repeat(127) + "b", "192.168.1.2%wlan0", "fe80::1%", "fe80::1%bad zone", "1:2:3:4:5:6:7:8:9")) {
            bad(h, "80", AddressProblem.HOST_INVALID)
        }
    }

    @Test fun portsMustBeDecimalInRange() {
        bad("10.0.0.1", "0", AddressProblem.PORT_OUT_OF_RANGE)
        bad("10.0.0.1", "65536", AddressProblem.PORT_OUT_OF_RANGE)
        bad("10.0.0.1", "99999999999999999999", AddressProblem.PORT_OUT_OF_RANGE)
        bad("10.0.0.1", "-1", AddressProblem.PORT_INVALID)
        bad("10.0.0.1", "+80", AddressProblem.PORT_INVALID)
        bad("10.0.0.1", "8o", AddressProblem.PORT_INVALID)
        bad("10.0.0.1", "８０", AddressProblem.PORT_INVALID)
        bad("10.0.0.1", "0x50", AddressProblem.PORT_INVALID)
    }

    @Test fun addressesAreFormattedForDisplayAndTyping() {
        assertEquals("192.168.43.1:41234", ManualAddress.format("192.168.43.1", 41234))
        assertEquals("[fd00::1]:41234", ManualAddress.format("fd00::1", 41234))
        assertEquals("phone.local:80", ManualAddress.format("phone.local", 80))
    }

    @Test fun everyProblemHasAMessage() {
        for (p in AddressProblem.entries) assert(p.messageKey.startsWith("address.")) { p }
    }
}
