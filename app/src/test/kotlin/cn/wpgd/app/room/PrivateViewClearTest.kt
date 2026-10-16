package cn.wpgd.app.room

import cn.wpgd.app.TestProfiles
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.wire.GrantStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G1–G6, GDN-065, GDN-066 at the watcher's device: it holds the public table only; one hand appears
 * only after that player's consent and disappears from the device the moment consent ends —
 * revoked, or the player left the seat (consent never passes to the next occupant).
 */
class PrivateViewClearTest {
    private val maxA = TestProfiles.rules.matchSettings.maxAFailures

    private fun handIds(p: Phone): Set<String> = p.table!!.view!!.deal!!.hand.map { it.id }.toSet()

    /**
     * No card of [hidden] appears anywhere in what [watcher]'s device holds. Callers pass hands read
     * after [settle], so a card just played (public) is not mistaken for a hidden one.
     */
    private fun assertHolds(watcher: Phone, hidden: Set<String>) {
        val held = watcher.state.toString()
        // The card that decided the first leader is turned face up for everyone (public).
        val public = setOfNotNull(watcher.table!!.spectatorView!!.match.cutCard?.id)
        for (id in hidden - public) {
            val at = held.indexOf("id=$id,")
            assertFalse("watcher holds hidden card $id: ${if (at < 0) "" else held.substring(maxOf(0, at - 300), minOf(held.length, at + 100))}", at >= 0)
        }
    }

    /** Lets the table run until it waits for a person, so every device shows the same revision. */
    private fun RoomRig.settle(vararg phones: Phone) {
        run(5_000)
        until { phones.map { it.table!!.revision }.distinct().size == 1 }
    }

    private fun setUp(seed: Long): Triple<RoomRig, List<Seated>, Phone> {
        val rig = RoomRig(seed)
        val host = rig.host("阿梅")
        val chen = rig.phone("陈阿姨")
        val people = rig.startedRoom(host, mapOf(Seat.EAST to rig.phone("老周")), watchers = listOf(chen))
        return Triple(rig, people, chen)
    }

    /** Plays until the followed player's screen shows the request (never on its own turn, G3). */
    private fun awaitRequest(rig: RoomRig, people: List<Seated>, player: Seated) =
        assertTrue(rig.drive(people, 600_000) { player.vm.state().followRequests.isNotEmpty() })

    @Test fun aHandIsSeenOnlyWithConsentAndClearedOnRevoke() {
        val (rig, people, chen) = setUp(81)
        val (host, zhou) = people
        val first = checkNotNull(WatchUi.of(chen.state, 0, maxA))
        assertEquals(listOf(true, true, false, false), Seat.entries.map { s -> first.seats.single { it.seat == s }.canAsk })
        assertNull(first.follow)
        assertNull(chen.table!!.view)
        rig.settle(chen, host.phone, zhou.phone)
        assertHolds(chen, handIds(host.phone) + handIds(zhou.phone))

        chen.owner.requestFollow(Seat.EAST)
        awaitRequest(rig, people, zhou)
        assertEquals(Seat.EAST, WatchUi.of(chen.state, 0, maxA)!!.waitingFor)
        assertNull("still nothing to see while the player decides", chen.table!!.follow)
        assertTrue("the host is not asked about EAST's hand", host.vm.state().followRequests.isEmpty())
        val request = zhou.vm.state().followRequests.single()
        assertEquals("陈阿姨", request.observerName)

        zhou.phone.owner.answerFollow(request.grantId, approve = true)
        rig.until { chen.table?.follow?.view?.deal?.hand?.map { it.id }?.toSet() == handIds(zhou.phone) }
        val following = checkNotNull(WatchUi.of(chen.state, 0, maxA)!!.follow)
        assertEquals(Seat.EAST, following.seat)
        assertEquals("老周", following.nickname)
        assertEquals(handIds(zhou.phone), following.hand.map { it.id }.toSet())
        rig.settle(chen, host.phone, zhou.phone)
        assertHolds(chen, handIds(host.phone))
        assertEquals(listOf(true), zhou.vm.state().watchers.filter { it.name == "陈阿姨" }.map { it.seesYourHand })
        assertEquals(listOf(false), host.vm.state().watchers.map { it.seesYourHand })

        zhou.phone.owner.stopFollow(following.grantId)
        rig.until { chen.table?.follow == null }
        val after = checkNotNull(WatchUi.of(chen.state, 0, maxA))
        assertNull(after.follow)
        assertEquals(WatchNotice.Revoked(Seat.EAST, "老周"), after.notice)
        rig.settle(chen, host.phone, zhou.phone)
        assertHolds(chen, handIds(zhou.phone) + handIds(host.phone))
        val revoked = chen.table!!.grants.maxBy { it.grantRevision }
        assertEquals(GrantStatus.REVOKED, revoked.status)
        assertNull("dismissed outcomes are not shown again", WatchUi.of(chen.state, revoked.grantRevision, maxA)!!.notice)
    }

    @Test fun consentEndsWhenThePlayerLeavesTheSeat() {
        val (rig, people, chen) = setUp(82)
        val zhou = people[1]
        chen.owner.requestFollow(Seat.EAST)
        awaitRequest(rig, people, zhou)
        zhou.phone.owner.answerFollow(zhou.vm.state().followRequests.single().grantId, approve = true)
        rig.until { chen.table?.follow != null }
        val handBefore = handIds(zhou.phone)

        zhou.phone.owner.leaveRoom()
        rig.until { chen.table?.follow == null && WatchUi.of(chen.state, 0, maxA)?.notice is WatchNotice.PlayerLeft }
        val w = checkNotNull(WatchUi.of(chen.state, 0, maxA))
        assertEquals(Seat.EAST, (w.notice as WatchNotice.PlayerLeft).seat)
        // The stand-in now plays these cards; the watcher keeps no copy of them.
        rig.run(200)
        val played = chen.state.toString()
        val stillHidden = handBefore.filter { id -> !played.contains("id=$id,") }
        assertTrue("the hand vanished from the watcher's device", stillHidden.isNotEmpty())
        assertFalse("the stand-in is not watchable", w.seats.single { it.seat == Seat.EAST }.canAsk)
    }

    @Test fun aDeniedRequestShowsNothing() {
        val (rig, people, chen) = setUp(83)
        val zhou = people[1]
        chen.owner.requestFollow(Seat.EAST)
        awaitRequest(rig, people, zhou)
        zhou.phone.owner.answerFollow(zhou.vm.state().followRequests.single().grantId, approve = false)
        rig.until { WatchUi.of(chen.state, 0, maxA)?.notice != null }
        assertEquals(WatchNotice.Denied(Seat.EAST, "老周"), WatchUi.of(chen.state, 0, maxA)!!.notice)
        assertNull(chen.table!!.follow)
        rig.settle(chen, people[0].phone, zhou.phone)
        assertHolds(chen, handIds(zhou.phone))
        assertNotNull(chen.table!!.spectatorView)
    }
}
