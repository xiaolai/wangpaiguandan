package cn.wpgd.app.diagnostics

import cn.wpgd.app.TestProfiles
import cn.wpgd.app.VirtualSolo
import cn.wpgd.app.data.IdentityRepository
import cn.wpgd.app.data.InMemoryKeyValueStore
import cn.wpgd.app.drive
import cn.wpgd.app.human
import cn.wpgd.app.release.BuildMode
import cn.wpgd.app.session.diagnostics.DiagnosticsReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WI-12 (GDN-108, P8) at the app level: the export the settings screen shows for a running solo
 * table holds the build mode, the app version, both profiles (with their provisional flag), the owner's counters and the
 * session's revisions, and no card of the player's hand, no table id and no permanent identity.
 */
class AppDiagnosticsTest {
    private fun lines(text: String) = text.lines().drop(1).filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun words(text: String) = Regex("[A-Za-z0-9_-]+").findAll(text).map { it.value }.toSet()

    @Test fun soloTableExportHasRevisionsAndProfilesAndNoHandOrIdentity() {
        val crashes = CrashCounter(InMemoryKeyValueStore())
        crashes.record()
        crashes.record()
        val identity = IdentityRepository(InMemoryKeyValueStore()).identity().id
        val solo = VirtualSolo(seed = 7).start()
        val vm = solo.viewModel()
        val human = solo.human(vm, 7)
        solo.drive(human) { (solo.table?.revision ?: 0) > 20 }
        val table = solo.table!!
        val hand = table.view!!.deal!!.hand.map { it.id }.toSet()
        assertTrue(hand.size > 5)

        val diagnostics = AppDiagnostics(BuildMode.DEVELOPMENT, versionCode = 1, versionName = "0.0.1", profiles = { TestProfiles.profiles }, crashes = crashes)
        val text = diagnostics.export(solo.owner)
        assertEquals(emptyList<String>(), DiagnosticsReport.validate(text))
        val l = lines(text)
        assertEquals("DEVELOPMENT", l["build.mode"])
        assertEquals("1", l["app.version_code"])
        assertEquals("0.0.1", l["app.version_name"])
        assertEquals("P1-base-v1", l["profile.base.id"])
        assertEquals("M1-match-v1", l["profile.match.id"])
        assertEquals(TestProfiles.profiles.base.hash, l["profile.base.hash"])
        assertEquals("false", l["profile.base.provisional"])
        assertEquals("false", l["profile.match.provisional"])
        assertEquals("SOLO", l["owner.table"])
        assertEquals("true", l["owner.loop_responsive"])
        assertEquals("2", l["process.crashes"])
        assertEquals("NONE", l["owner.failure_kind"])
        assertEquals(table.revision.toString(), l["client.revision"])
        assertTrue(l.getValue("host.revision").toLong() >= table.revision)
        assertEquals(solo.owner.metrics.commandsSent.get().toString(), l["owner.commands_sent"])
        assertTrue(l.getValue("owner.receipts").toLong() > 0)
        // WI-07: the AI seats' decision times, so the p95 budget can be read on a device (HP-1).
        assertTrue(l.getValue("owner.ai_decisions").toLong() > 0)
        assertEquals(solo.owner.metrics.aiDecisionMs.count.toString(), l["owner.ai_decisions"])
        assertTrue(l.getValue("owner.ai_decision_ms_p95").toLong() >= 0)
        assertTrue(l.getValue("owner.ai_decision_ms_max").toLong() >= l.getValue("owner.ai_decision_ms_p95").toLong())

        val w = words(text)
        assertTrue("export names cards of the hand: ${w intersect hand}", (w intersect hand).isEmpty())
        assertTrue("export holds the permanent identity", !text.contains(identity))
        assertTrue("export holds the table id", !text.contains(table.tableId))
        solo.owner.shutdown()
    }

    @Test fun withoutATableTheExportStillHasBuildProfilesAndCrashes() {
        val diagnostics = AppDiagnostics(BuildMode.RELEASE, versionCode = 3, versionName = "0.0.3", profiles = { TestProfiles.profiles }, crashes = CrashCounter(InMemoryKeyValueStore()))
        val l = lines(diagnostics.export(null))
        assertEquals("RELEASE", l["build.mode"])
        assertEquals("0.0.3", l["app.version_name"])
        assertEquals("NONE", l["owner.table"])
        assertEquals("0", l["process.crashes"])
        assertTrue(l.keys.none { it.startsWith("host.") || it.startsWith("client.") })
        val solo = VirtualSolo(seed = 11).start()
        solo.owner.shutdown()
        assertEquals("a shut-down owner has no table", "NONE", lines(diagnostics.export(solo.owner))["owner.table"])
    }

    /** First release (plan WI-12, HP-6): version name 0.0.1, version code 1; the next test build must raise the code. */
    @Test fun theBuildIsVersionZeroZeroOneWithCodeOne() {
        assertEquals("0.0.1", cn.wpgd.app.BuildConfig.VERSION_NAME)
        assertEquals(1, cn.wpgd.app.BuildConfig.VERSION_CODE)
    }

    @Test fun aVersionNameThatIsNotMajorMinorPatchIsRefused() {
        val e = runCatching { AppDiagnostics(BuildMode.DEVELOPMENT, versionCode = 1, versionName = "0.1.0-dev", profiles = { TestProfiles.profiles }, crashes = CrashCounter(InMemoryKeyValueStore())) }.exceptionOrNull()
        assertTrue("free text in a version name is refused, got $e", e is IllegalArgumentException)
    }

    @Test fun failureKindNamesTheTypeNeverTheMessage() {
        assertEquals(cn.wpgd.app.session.diagnostics.FailureKind.ILLEGAL_STATE, failureKind(IllegalStateException("card 0S3 in hand")))
        assertEquals(cn.wpgd.app.session.diagnostics.FailureKind.ILLEGAL_ARGUMENT, failureKind(IllegalArgumentException()))
        assertEquals(cn.wpgd.app.session.diagnostics.FailureKind.IO, failureKind(java.io.IOException()))
        assertEquals(cn.wpgd.app.session.diagnostics.FailureKind.OTHER, failureKind(RuntimeException()))
    }
}
