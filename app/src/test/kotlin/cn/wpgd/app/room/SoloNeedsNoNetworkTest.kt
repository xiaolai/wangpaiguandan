package cn.wpgd.app.room

import cn.wpgd.app.BlockingWorkerDecisionExecutor
import cn.wpgd.app.TestProfiles
import cn.wpgd.app.core.match.MatchPhase
import cn.wpgd.app.nickname
import cn.wpgd.app.owner.OwnerDependencies
import cn.wpgd.app.owner.SessionOwner
import cn.wpgd.app.owner.SoloConfig
import cn.wpgd.app.owner.VirtualSessionLoop
import cn.wpgd.app.session.transport.Transport
import cn.wpgd.app.testCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GDN-005: solo play in airplane mode. With a room network present (the app always builds one), a
 * solo table never listens, connects, announces or browses — so nothing permission-gated runs.
 */
class SoloNeedsNoNetworkTest {
    private class Tripwire : RoomNetwork, RoomAnnouncer {
        var touched = 0
        private fun trip(): Nothing {
            touched++
            error("solo touched the room network")
        }
        override fun listen(post: (() -> Unit) -> Unit, onPeer: (Transport) -> Unit): RoomListener = trip()
        override val medium: RoomMedium get() = trip()
        override fun connect(endpoint: RoomEndpoint, post: (() -> Unit) -> Unit): Transport = trip()
        override val announcer: RoomAnnouncer get() = this
        override fun browser(): RoomBrowser = trip()
        override fun announce(advert: RoomAdvert, onFailed: () -> Unit) = trip()
        override fun withdraw(roomId: String) = trip()
    }

    @Test fun soloNeverTouchesTheRoomNetwork() {
        val net = Tripwire()
        val loop = VirtualSessionLoop()
        val owner = SessionOwner(
            OwnerDependencies(
                profiles = TestProfiles.profiles,
                crypto = testCrypto(5),
                loopFactory = { loop },
                decisionExecutorFactory = { w, _, _ -> BlockingWorkerDecisionExecutor(w) },
                solo = SoloConfig(aiDelayMs = 50),
                network = net,
            ),
        )
        assertTrue(owner.startSolo(nickname()))
        assertTrue(loop.scheduler.runUntil(600_000) { owner.state.value.table?.view?.match?.phase == MatchPhase.PLAYING })
        loop.scheduler.runFor(30_000)
        owner.shutdown()
        assertEquals(0, net.touched)
        assertEquals(null, owner.state.value.failure)
    }
}
