package cn.wpgd.app.ui

import cn.wpgd.app.R
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.wire.Occupant
import cn.wpgd.app.table.AbsentSeatUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Who holds a seat, and who is away, is always named: the seat lines can be told apart. */
class SeatPresenceTextTest {
    private val strings = File("src/main/res/values/strings.xml").readText()

    private fun text(name: String): String =
        checkNotNull(Regex("""<string name="$name">([^<]*)</string>""").find(strings)) { "no string $name" }.groupValues[1]

    /** Audit #33: an absent player was summarised exactly like a present one. */
    @Test fun aWatcherSeesAnAbsentPlayerAsAway() {
        val absent = holderText(Occupant.ABSENT)
        assertNotEquals(holderText(Occupant.HUMAN), absent)
        assertEquals(HolderText(R.string.watch_holder_away, withName = true), absent)
        assertTrue("the away holder names the player", "%1\$s" in text("watch_holder_away"))
        assertEquals(HolderText(null, withName = true), holderText(Occupant.HUMAN))
    }

    /** Audit #34: two seats awaiting their players produced two identical lines. */
    @Test fun everyAwaySeatLineNamesItsSeat() {
        val awaiting = absentLine(AbsentSeatUi(Seat.EAST, "老周", null, waitingForPlayer = true))
        assertEquals(R.string.host_awaiting_player, awaiting)
        assertTrue("%1\$s" in text("host_awaiting_player"))
        assertEquals(R.string.host_absent_grace, absentLine(AbsentSeatUi(Seat.EAST, "老周", 20, waitingForPlayer = false)))
        assertEquals(R.string.host_absent, absentLine(AbsentSeatUi(Seat.EAST, "老周", null, waitingForPlayer = false)))
    }
}
