package cn.wpgd.app.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The platform-free parts of the Android radio adapter. */
class AndroidBleRadioLogicTest {
    /**
     * Audit #5: a central can write its first packet as soon as it subscribed, before the room has
     * opened the host's port. That packet is held and handed over, in order, once the port opens.
     */
    @Test fun packetsBeforeThePortOpensAreHeldAndDeliveredInOrder() {
        val early = EarlyPackets(capacity = 3)
        early.receive(byteArrayOf(1))
        early.receive(byteArrayOf(2))
        val got = ArrayList<Int>()
        early.attach { got += it.single().toInt() }
        assertEquals(listOf(1, 2), got)
        early.receive(byteArrayOf(3))
        assertEquals(listOf(1, 2, 3), got)
    }

    @Test fun aFullEarlyBufferRefusesInsteadOfDropping() {
        val early = EarlyPackets(capacity = 2)
        early.receive(byteArrayOf(1))
        assertTrue(early.canReceive)
        early.receive(byteArrayOf(2))
        assertFalse("the write is refused, so the central sees a failed send", early.canReceive)
        early.attach { }
        assertTrue(early.canReceive)
    }

    /** Audit #6: closing a connection is not a connect failure, and a failure is counted once. */
    @Test fun aLocalCloseIsNoSetupFailure() {
        var failures = 0
        val s = ConnectSetup { failures++ }
        s.closed()
        s.failed()
        assertEquals("the disconnect callback after the close counts nothing", 0, failures)
    }

    @Test fun aFailedSetupIsCountedOnce() {
        var failures = 0
        val s = ConnectSetup { failures++ }
        s.failed()
        s.failed()
        assertEquals("a late callback for the same connection counts nothing", 1, failures)
    }

    @Test fun anEndAfterReadyIsNoSetupFailure() {
        var failures = 0
        val s = ConnectSetup { failures++ }
        s.ready()
        s.failed()
        assertEquals(0, failures)
    }

    /** Round 3 #2: a descriptor read honours the requested offset, and refuses one past the value. */
    @Test fun aCccdReadHonoursItsOffset() {
        assertArrayEquals(byteArrayOf(1, 0), BleCccd.read(subscribed = true, offset = 0))
        assertArrayEquals(byteArrayOf(0), BleCccd.read(subscribed = true, offset = 1))
        assertArrayEquals(byteArrayOf(), BleCccd.read(subscribed = false, offset = 2))
        assertNull(BleCccd.read(subscribed = true, offset = 3))
        assertNull(BleCccd.read(subscribed = true, offset = -1))
    }

    /** Round 3 #3: only enabling and disabling notifications is a valid CCCD write; indications and other values are refused. */
    @Test fun onlyNotificationOnOrOffIsAValidCccdWrite() {
        assertEquals(CccdWrite.ENABLE, BleCccd.parse(byteArrayOf(1, 0)))
        assertEquals(CccdWrite.DISABLE, BleCccd.parse(byteArrayOf(0, 0)))
        assertNull("indications are not offered", BleCccd.parse(byteArrayOf(2, 0)))
        assertNull(BleCccd.parse(byteArrayOf(3, 0)))
        assertNull(BleCccd.parse(byteArrayOf(1, 1)))
        assertNull(BleCccd.parse(byteArrayOf(1)))
        assertNull(BleCccd.parse(null))
    }

    /** Round 3 #1: a connection closed before its open task ran never connects. */
    @Test fun aConnectionClosedBeforeItOpensNeverConnects() {
        val s = ConnectSetup { }
        assertTrue(s.mayConnect)
        s.closed()
        assertFalse(s.mayConnect)
        val failed = ConnectSetup { }
        failed.failed()
        assertFalse(failed.mayConnect)
    }
}
