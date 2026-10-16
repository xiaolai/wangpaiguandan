package cn.wpgd.app.room

import cn.wpgd.app.session.wire.PROTOCOL_VERSION
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.lan.LanEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Adverts arrive from any nearby device: decoding bounds every field and drops anything malformed. */
class AdvertCodecTest {
    private val endpoint = LanEndpoint("192.168.1.5", 41234)
    private val advert = RoomAdvert("t0a1b2c3d4e5f6a7b", "周末掼蛋", "阿梅", PROTOCOL_VERSION, PROTOCOL_VERSION, "a".repeat(64), "b".repeat(64), "K".repeat(43), setOf(Seat.EAST, Seat.WEST), endpoint, setOf(Seat.NORTH))

    @Test fun roundTrips() {
        val attrs = AdvertCodec.encode(advert)
        assertEquals("ES".length, attrs.getValue("s").length)
        assertEquals(advert, AdvertCodec.decode(attrs, endpoint))
        assertEquals(advert.copy(openSeats = emptySet()), AdvertCodec.decode(AdvertCodec.encode(advert.copy(openSeats = emptySet())), endpoint))
        // Every TXT value fits the 255-byte DNS-SD limit.
        AdvertCodec.encode(advert).forEach { (k, v) -> assert((k.length + 1 + v.encodeToByteArray().size) <= 255) }
    }

    @Test fun malformedAdvertsAreDropped() {
        val good = AdvertCodec.encode(advert)
        val bad = listOf(
            good - "id",
            good + ("v" to "1"),
            good + ("v" to "3"),
            // G2: the host key fingerprint is required and must be 32 bytes of base64url.
            good - "k",
            good + ("k" to "K".repeat(42)),
            good + ("k" to "K".repeat(44)),
            good + ("k" to "K".repeat(42) + "="),
            good + ("id" to "../etc"),
            good + ("n" to "x".repeat(AdvertCodec.MAX_TABLE_NAME + 1)),
            good + ("n" to "周末\u0000掼蛋"),
            good + ("n" to "周末​掼蛋"),
            good + ("h" to " "),
            good + ("p" to "2-1"),
            good + ("p" to "one"),
            // Every component must be a number, and there are exactly two (min-max).
            good + ("p" to "1-garbage-1"),
            good + ("p" to "1-1-1"),
            good + ("p" to "1-x"),
            good + ("b" to "A".repeat(64)),
            good + ("m" to "b".repeat(63)),
            good + ("s" to "Q"),
            good + ("s" to "SENWS"),
            good + ("s" to "EE"),
            good - "r",
            good + ("r" to "E"),
            good + ("r" to "X"),
        )
        for (attrs in bad) assertNull(attrs.toString(), AdvertCodec.decode(attrs, endpoint))
    }

    @Test fun tableCodesAreFourStableDigits() {
        assertEquals(TableCode.of("t0a1b2c3d4e5f6a7b"), TableCode.of("t0a1b2c3d4e5f6a7b"))
        assertEquals(4, TableCode.of("x").length)
        assertNotEquals(TableCode.of("t1"), TableCode.of("t2"))
    }
}
