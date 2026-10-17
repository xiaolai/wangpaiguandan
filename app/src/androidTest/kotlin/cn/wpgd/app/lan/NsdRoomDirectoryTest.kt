package cn.wpgd.app.lan

import cn.wpgd.app.session.wire.PROTOCOL_VERSION
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.room.AdvertCodec
import cn.wpgd.app.room.RoomAdvert
import cn.wpgd.app.room.RoomBrowser
import cn.wpgd.app.room.waitFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * WI-09 discovery on a real Android network stack (GDN-009, GDN-023): a room announced through
 * NSD is found with its advert and the resolved address; re-announcing on a new port (a host whose
 * table was restored, GDN-085) reports the new port under the same room id; withdrawing removes it;
 * a registration whose advert claims another room id is never reported. One device announces and
 * browses, so this proves the adapter, not multi-phone multicast (that is HP-2).
 */
@RunWith(AndroidJUnit4::class)
class NsdRoomDirectoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = NsdRoomDirectory(context)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val browsers = ArrayList<RoomBrowser>()
    private val raw = ArrayList<NsdManager.RegistrationListener>()
    private val suffix = Random.nextLong(1L shl 40).toString(36)

    @Volatile private var rooms: List<RoomAdvert> = emptyList()

    @After fun tearDown() {
        browsers.forEach { it.stop() }
        raw.forEach { runCatching { nsd.unregisterService(it) } }
    }

    private fun advert(id: String, port: Int) = RoomAdvert(
        id, "测试桌", "主机", PROTOCOL_VERSION, PROTOCOL_VERSION, "a".repeat(64), "b".repeat(64), "K".repeat(43), setOf(Seat.EAST, Seat.WEST), LanEndpoint("127.0.0.1", port),
    )

    private fun browse() {
        val b = directory.browser()
        browsers += b
        b.start { rooms = it }
    }

    private fun find(id: String): RoomAdvert? = rooms.firstOrNull { it.roomId == id }

    @Test fun aRoomIsFoundMovesToItsNewPortAndGoesAway() {
        val id = "nsd-$suffix"
        browse()
        directory.announce(advert(id, 41_001))
        waitFor(30_000, { "not found: $rooms" }) { (find(id)?.endpoint as? LanEndpoint)?.port == 41_001 }
        val found = find(id)!!
        assertEquals("测试桌", found.tableName)
        assertEquals(setOf(Seat.EAST, Seat.WEST), found.openSeats)
        assertTrue("the address comes from the resolve", (found.endpoint as LanEndpoint).host.isNotBlank())

        // The host restarts its table on another port: same room id, new address.
        directory.announce(advert(id, 41_002))
        waitFor(30_000, { "still at the old port: $rooms" }) { (find(id)?.endpoint as? LanEndpoint)?.port == 41_002 }

        directory.withdraw(id)
        waitFor(30_000, { "not withdrawn: $rooms" }) { find(id) == null }
    }

    @Test fun anAdvertClaimingAnotherRoomIdIsDropped() {
        val forged = "forged-$suffix"
        val honest = "honest-$suffix"
        browse()
        // A service named "forged" whose TXT record claims to be room "honest".
        val info = NsdServiceInfo().apply {
            serviceName = forged
            serviceType = AdvertCodec.SERVICE_TYPE
            port = 41_003
            AdvertCodec.encode(advert(honest, 41_003)).forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        raw += listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        // A genuine room announced after it proves the browser saw and resolved the forged one first.
        directory.announce(advert("later-$suffix", 41_004))
        waitFor(30_000, { "the genuine room was not found: $rooms" }) { find("later-$suffix") != null }
        Thread.sleep(2_000)
        assertTrue("a forged advert was reported: $rooms", rooms.none { it.roomId == honest || it.roomId == forged })
        directory.withdraw("later-$suffix")
    }
}
