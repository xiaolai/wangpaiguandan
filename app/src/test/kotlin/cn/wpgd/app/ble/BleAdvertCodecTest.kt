package cn.wpgd.app.ble

import cn.wpgd.app.session.wire.PROTOCOL_VERSION
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.room.RoomAdvert
import cn.wpgd.app.room.RoomMedium
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** WI-10: the advert characteristic carries the LAN advert's attributes, decoded just as strictly. */
class BleAdvertCodecTest {
    private val endpoint = BleEndpoint("4C:1D:2E:3F:40:51")
    private val advert = RoomAdvert("t0a1b2c3d4e5f6a7b", "周末掼蛋", "阿梅", PROTOCOL_VERSION, PROTOCOL_VERSION, "a".repeat(64), "b".repeat(64), "K".repeat(43), setOf(Seat.EAST, Seat.WEST), endpoint, setOf(Seat.NORTH))

    @Test fun roundTripsAndKeepsTheScannedAddress() {
        val bytes = BleAdvertCodec.encode(advert)
        assertEquals(advert, BleAdvertCodec.decode(bytes, endpoint))
        val other = BleEndpoint("4C:1D:2E:3F:40:52")
        assertEquals(other, BleAdvertCodec.decode(bytes, other)!!.endpoint)
        assertEquals(RoomMedium.BLUETOOTH, BleAdvertCodec.decode(bytes, other)!!.endpoint.medium)
    }

    @Test fun theLargestAdvertFitsOneAttributeValue() {
        val big = advert.copy(roomId = "x".repeat(64), tableName = "桌".repeat(12), hostName = "名".repeat(32))
        val bytes = BleAdvertCodec.encode(big)
        assertTrue(bytes.size <= BleAdvertCodec.MAX_BYTES)
        assertEquals(big, BleAdvertCodec.decode(bytes, endpoint))
    }

    @Test fun malformedBytesDecodeToNothing() {
        val good = BleAdvertCodec.encode(advert)
        val bad = listOf(
            ByteArray(0),
            byteArrayOf(1),
            good.copyOf().also { it[0] = 2 },
            good.copyOf(good.size - 1),
            good + byteArrayOf(0),
            good.copyOf().also { it[1] = 17 },
            ByteArray(BleAdvertCodec.MAX_BYTES + 1),
            // Invalid UTF-8 in a value.
            byteArrayOf(1, 1, 1, 'v'.code.toByte(), 2, 0xC3.toByte(), 0x28),
            // A duplicated key.
            byteArrayOf(1, 2, 1, 'v'.code.toByte(), 1, '1'.code.toByte(), 1, 'v'.code.toByte(), 1, '1'.code.toByte()),
            // A control character as a key.
            byteArrayOf(1, 1, 1, 0x0A, 1, '1'.code.toByte()),
        )
        for (b in bad) assertNull(b.joinToString(), BleAdvertCodec.decode(b, endpoint))
    }

    @Test fun theAttributesAreCheckedLikeTheLanAdvert() {
        val tooLong = advert.copy(tableName = "x".repeat(25))
        assertNull(BleAdvertCodec.decode(BleAdvertCodec.encode(tooLong), endpoint))
    }

    @Test fun decodingNeverThrowsOnRandomBytes() {
        val r = Random(9)
        val good = BleAdvertCodec.encode(advert)
        repeat(20_000) {
            val b = if (r.nextBoolean()) r.nextBytes(r.nextInt(0, 600)) else good.copyOf().also { g -> repeat(3) { g[r.nextInt(g.size)] = r.nextInt(256).toByte() } }
            BleAdvertCodec.decode(b, endpoint)
        }
    }

    @Test fun addressesAreValidated() {
        for (bad in listOf("", "4c:1d:2e:3f:40:51", "4C-1D-2E-3F-40-51", "4C:1D:2E:3F:40", "4C:1D:2E:3F:40:51:00")) {
            assertTrue(bad, runCatching { BleEndpoint(bad) }.isFailure)
        }
    }
}
