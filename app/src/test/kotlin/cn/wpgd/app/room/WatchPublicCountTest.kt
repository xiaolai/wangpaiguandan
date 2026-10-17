package cn.wpgd.app.room

import cn.wpgd.app.TestProfiles
import cn.wpgd.app.core.match.CountAnswer
import cn.wpgd.app.core.match.CountReport
import cn.wpgd.app.core.state.Seat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Audit #25: a watcher sees the same public card count as a player. Reports and answers are separate
 * lists whose relative order is unknown; counts only fall, so the smallest is the newest.
 */
class WatchPublicCountTest {
    @Test fun anEarlierAnswerDoesNotOverwriteALaterReport() {
        val rig = RoomRig(seed = 91)
        val chen = rig.phone("陈阿姨")
        rig.startedRoom(rig.host("阿梅"), mapOf(Seat.EAST to rig.phone("老周")), watchers = listOf(chen))
        val t = checkNotNull(chen.table)
        val sv = checkNotNull(t.spectatorView)
        val match = sv.match.copy(
            reports = listOf(CountReport(Seat.EAST, 5)),
            answers = listOf(CountAnswer(Seat.SOUTH, Seat.EAST, 8)),
        )
        val state = chen.state.copy(table = t.copy(spectatorView = sv.copy(match = match)))
        val watch = checkNotNull(WatchUi.of(state, 0, TestProfiles.rules.matchSettings.maxAFailures))
        assertEquals(5, watch.seats.single { it.seat == Seat.EAST }.publicCount)
    }
}
