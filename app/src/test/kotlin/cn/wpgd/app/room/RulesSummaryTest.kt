package cn.wpgd.app.room

import cn.wpgd.app.TestProfiles
import cn.wpgd.app.core.state.Seat
import cn.wpgd.app.session.host.AbsencePolicy
import cn.wpgd.app.session.wire.AbsenceMode
import cn.wpgd.app.session.wire.RoomMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GDN-007: before readying, every player sees tribute and return, resistance, the passing-A
 * condition and failure limit, card-count reporting and the room's assistance — from the frozen
 * profile and the settings the host actually sent.
 */
class RulesSummaryTest {
    @Test fun theSheetShowsEveryRuleAPlayerReadiesFor() {
        val rig = RoomRig(seed = 51)
        val host = rig.host("阿梅", RoomSettings("周末掼蛋", RoomMode.FRIENDLY, AbsencePolicy.SUBSTITUTE, 30))
        val zhou = rig.join(rig.phone("老周"), host, Seat.EAST)
        val room = zhou.table!!.roomView!!
        val sheet = RulesSheet.of(TestProfiles.rules, room.settings, room.settingsRevision)
        val m = TestProfiles.rules.matchSettings

        assertEquals(TestProfiles.profiles.match.id, sheet.summary.matchId)
        assertEquals(TestProfiles.profiles.base.hash.take(12), sheet.summary.baseHash)
        assertFalse("P1/M1 are signed (G1a, G1b), so the sheet does not mark them provisional", sheet.summary.provisional)
        assertEquals(m.returnMaxRank, sheet.returnMaxRank)
        assertEquals(m.minGainToPassA, sheet.minGainToPassA)
        assertEquals(m.maxAFailures, sheet.maxAFailures)
        // Audit #26: the sheet reads these from its summary, so the two can never disagree.
        val other = sheet.copy(summary = sheet.summary.copy(maxAFailures = 9, resistanceClaimed = !sheet.summary.resistanceClaimed))
        assertEquals(9, other.maxAFailures)
        assertEquals(other.summary.resistanceClaimed, other.resistanceClaimed)
        assertEquals(m.reportingThreshold, sheet.reportingThreshold)
        assertEquals(RoomMode.FRIENDLY, sheet.mode)
        assertTrue(sheet.hints)
        assertTrue(sheet.privateFollow)
        assertEquals(30, sheet.graceSeconds)
        assertEquals(AbsenceMode.SUBSTITUTE, sheet.absence)
        assertEquals(room.settingsRevision, sheet.settingsRevision)

        host.owner.updateSettings(RoomSettings("周末掼蛋", RoomMode.COMPETITIVE, AbsencePolicy.PAUSE, 10))
        rig.until { zhou.table!!.roomView!!.settings.policy.mode == RoomMode.COMPETITIVE }
        val changed = zhou.table!!.roomView!!
        val after = RulesSheet.of(TestProfiles.rules, changed.settings, changed.settingsRevision)
        assertFalse(after.hints)
        assertFalse(after.privateFollow)
        assertEquals(10, after.graceSeconds)
        assertEquals(AbsenceMode.PAUSE, after.absence)
        assertTrue(after.settingsRevision > sheet.settingsRevision)
    }
}
