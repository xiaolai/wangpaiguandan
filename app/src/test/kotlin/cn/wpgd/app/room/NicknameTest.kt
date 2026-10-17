package cn.wpgd.app.room

import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.data.NicknameProblem
import cn.wpgd.app.data.NicknameRepository
import cn.wpgd.app.data.NicknameResult
import cn.wpgd.app.owner.JoinPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GDN-002, audit A35, in a room: the nickname is persisted and bounded (data/NicknameTest), names that
 * read as a seat or role cannot be chosen, and a name never claims a seat — taking a held seat needs
 * that seat's capability, not the same name.
 */
class NicknameTest {
    @Test fun aNameCannotClaimSomeoneElsesSeat() {
        val rig = RoomRig(seed = 61)
        val host = rig.host("阿梅")
        val zhou = rig.join(rig.phone("老周"), host, Seat.EAST)
        rig.run(300)
        // Another phone with the same name asks for 老周's seat: the seat is taken; the name earns nothing.
        val impostor = rig.phone("老周")
        rig.ask(impostor, host, Seat.EAST)
        rig.until { impostor.table!!.join!!.phase == JoinPhase.SEAT_TAKEN }
        assertEquals("老周", host.table!!.roomView!!.seats.single { it.seat == Seat.EAST }.nickname)
        assertEquals(JoinPhase.ADMITTED, zhou.table!!.join!!.phase)
        assertEquals(Seat.EAST, zhou.table!!.seat)
    }

    @Test fun namesThatReadAsASeatOrRoleAreRefusedAndTheNameSurvivesRestarts() {
        val store = InMemoryKeyValueStore()
        for (raw in listOf("房主", "东家", "AI", "观众", "对家")) {
            assertEquals(raw, NicknameResult.Invalid(NicknameProblem.RESERVED), NicknameRepository(store).set(raw))
        }
        assertTrue(NicknameRepository(store).set("陈阿姨") is NicknameResult.Valid)
        assertEquals("陈阿姨", NicknameRepository(store).current()?.value)
    }
}
