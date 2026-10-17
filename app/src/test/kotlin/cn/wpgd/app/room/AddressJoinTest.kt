package cn.wpgd.app.room

import cn.wpgd.app.TestProfiles
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.lan.LanEndpoint
import cn.wpgd.app.session.transport.Cancellable
import cn.wpgd.app.session.wire.PROTOCOL_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The join-by-address screen's state: validate, look up once, show the room or why not. */
class AddressJoinTest {
    private class FakeLookup : AddressLookupService {
        val asked = ArrayList<LanEndpoint>()
        var pending: ((AddressLookupOutcome) -> Unit)? = null
        var cancelled = 0

        override fun lookup(endpoint: LanEndpoint, onDone: (AddressLookupOutcome) -> Unit): Cancellable {
            asked += endpoint
            pending = onDone
            return Cancellable { cancelled++ }
        }
    }

    private fun advert(endpoint: LanEndpoint) = RoomAdvert(
        "t0011223344556677", "周末掼蛋", "阿梅", PROTOCOL_VERSION, PROTOCOL_VERSION,
        TestProfiles.profiles.base.hash, TestProfiles.profiles.match.hash, "A".repeat(43), setOf(Seat.EAST), endpoint,
    )

    @Test fun anInvalidAddressIsExplainedAndNothingIsDialled() {
        val lookup = FakeLookup()
        val j = AddressJoin(lookup)
        j.lookup("http://192.168.1.2", "41234")
        assertEquals(AddressJoinState.Editing(problem = AddressProblem.HOST_HAS_SCHEME), j.state.value)
        j.lookup("192.168.1.2", "70000")
        assertEquals(AddressJoinState.Editing(problem = AddressProblem.PORT_OUT_OF_RANGE), j.state.value)
        assertTrue(lookup.asked.isEmpty())
    }

    @Test fun aFoundRoomIsShownForThePlayOrWatchChoice() {
        val lookup = FakeLookup()
        val j = AddressJoin(lookup)
        j.lookup("192.168.43.1", "41234")
        val endpoint = LanEndpoint("192.168.43.1", 41234)
        assertEquals(AddressJoinState.Looking(endpoint), j.state.value)
        j.lookup("192.168.43.1", "41234")
        assertEquals("one lookup at a time", 1, lookup.asked.size)
        lookup.pending!!(AddressLookupOutcome.Found(advert(endpoint)))
        assertEquals(AddressJoinState.Found(advert(endpoint)), j.state.value)
        j.back()
        assertEquals(AddressJoinState.Editing(), j.state.value)
    }

    @Test fun aFailureIsShownWithItsReasonAndCanBeRetried() {
        val lookup = FakeLookup()
        val j = AddressJoin(lookup)
        for (f in LookupFailure.entries) {
            j.lookup("10.0.0.5", "41234")
            lookup.pending!!(AddressLookupOutcome.Failed(f))
            assertEquals(AddressJoinState.Editing(failure = f), j.state.value)
            assertTrue(f.messageKey.startsWith("address."))
        }
        assertEquals(LookupFailure.entries.size, lookup.asked.size)
    }

    @Test fun aResultAfterCancelIsIgnored() {
        val lookup = FakeLookup()
        val j = AddressJoin(lookup)
        j.lookup("10.0.0.5", "41234")
        val late = lookup.pending!!
        j.cancel()
        assertEquals(1, lookup.cancelled)
        assertEquals(AddressJoinState.Editing(), j.state.value)
        late(AddressLookupOutcome.Failed(LookupFailure.TIMEOUT))
        assertEquals(AddressJoinState.Editing(), j.state.value)
    }
}
