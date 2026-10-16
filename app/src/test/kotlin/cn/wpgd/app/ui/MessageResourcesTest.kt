package cn.wpgd.app.ui

import cn.wpgd.app.core.match.Team
import cn.wpgd.app.core.match.TributeKind
import cn.wpgd.app.core.match.PlaceBasis
import cn.wpgd.app.owner.RejectionKey
import cn.wpgd.app.table.CueKind
import cn.wpgd.app.table.PreviewProblem
import cn.wpgd.app.table.ResultHeadline
import cn.wpgd.app.table.ResultLine
import cn.wpgd.app.table.SeatLabel
import cn.wpgd.app.table.SeatRelation
import cn.wpgd.app.table.TeamLabel
import cn.wpgd.app.table.TurnKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every message key the table logic emits has a Chinese string resource named after it
 * (`turn.you_lead` → `turn_you_lead`), and the screen's key → resource map covers it. No
 * user-facing text lives in a composable.
 */
class MessageResourcesTest {
    private val strings = File("src/main/res/values/strings.xml").readText()
    private val names = Regex("""<string name="([a-z0-9_]+)"""").findAll(strings).map { it.groupValues[1] }.toSet()

    private val keys: List<String> = CueKind.entries.map { it.messageKey } +
        RejectionKey.entries.map { it.messageKey } +
        TurnKey.entries.map { it.messageKey } +
        ResultHeadline.entries.map { it.messageKey } +
        PreviewProblem.entries.map { it.messageKey } +
        cn.wpgd.app.room.AddressProblem.entries.map { it.messageKey } +
        cn.wpgd.app.room.LookupFailure.entries.map { it.messageKey } +
        listOf<ResultLine>(
            ResultLine.Placing(SeatLabel.Relative(SeatRelation.SELF), 1, 1, PlaceBasis.PLAYED),
            ResultLine.PlacesInferred,
            ResultLine.LevelChange(TeamLabel.Own, 2, 3, 1),
            ResultLine.APassed(TeamLabel.Own),
            ResultLine.AFailed(TeamLabel.Named(Team.EAST_WEST), 1, 3),
            ResultLine.AReset(TeamLabel.Opponents, 2),
            ResultLine.AInterrupted(TeamLabel.Own),
            ResultLine.TributeNext(TributeKind.SINGLE, emptyList(), emptyList(), null),
        ).map { it.messageKey }

    @Test fun everyKeyHasAStringResourceNamedAfterIt() {
        assertTrue(keys.size > 50)
        val missing = keys.map { it.replace('.', '_') }.filter { it !in names }
        assertEquals("missing string resources", emptyList<String>(), missing)
    }

    @Test fun theScreenMapsEveryKeyToAResource() {
        val unmapped = keys.filter { Messages.resId(it) == null }
        assertEquals(emptyList<String>(), unmapped)
        assertEquals("one resource per key", keys.toSet().size, keys.mapNotNull { Messages.resId(it) }.toSet().size)
    }

    @Test fun noKeyIsMissingAndUnknownKeysMapToNothing() {
        assertEquals(null, Messages.resId("no.such.key"))
    }
}
