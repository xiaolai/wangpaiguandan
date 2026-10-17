package cn.wpgd.app.release

import cn.wpgd.app.BuildConfig
import cn.wpgd.app.TestProfiles
import cn.wpgd.app.table.RuleProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WI-12, plan section 3 (G1a, G1b): a release build refuses to start with an unsigned (provisional)
 * rule profile; a development build plays any profile. The packaged profiles are P1-base-v1 and
 * M1-match-v1, signed by the project owner on 2026-10-16 (dev-docs/decisions/G1a-base-rules.md,
 * G1b-match-profile.md), so a release configuration starts with them; the same profiles with the
 * provisional flag set must still be refused, one by one.
 */
class ReleaseGateTest {
    private val packaged = TestProfiles.profiles

    /** The packaged profiles with the provisional flag on, as an unsigned profile would be. */
    private fun unsigned(): RuleProfiles {
        fun flag(name: String) = File("../rules/profiles/$name.json").readText().replace("\"provisional\": false", "\"provisional\": true")
            .also { check(it.contains("\"provisional\": true")) { "$name: could not set the provisional flag" } }
        return RuleProfiles.parse(flag("P1-base-v1"), flag("M1-match-v1"))
    }

    @Test fun thePackagedProfilesAreSigned() {
        assertFalse("P1-base-v1 is signed (G1a)", packaged.base.provisional)
        assertFalse("M1-match-v1 is signed (G1b)", packaged.match.provisional)
    }

    @Test fun releaseConfigurationWithUnsignedProfilesFailsLoudly() {
        val u = unsigned()
        assertTrue(u.base.provisional && u.match.provisional)
        val e = assertThrows(UnsignedRuleProfileException::class.java) { ReleaseGate.check(BuildMode.RELEASE) { u } }
        assertTrue(e.message!!, e.message!!.contains("P1-base-v1") && e.message!!.contains("M1-match-v1"))
        assertTrue(e.message!!, e.message!!.contains("G1a") && e.message!!.contains("G1b"))
    }

    @Test fun releaseConfigurationRefusesEvenOneUnsignedProfile() {
        val u = unsigned()
        val e1 = assertThrows(UnsignedRuleProfileException::class.java) { ReleaseGate.check(BuildMode.RELEASE) { RuleProfiles(u.base, packaged.match) } }
        assertTrue(e1.message!!.contains("P1-base-v1") && !e1.message!!.contains("M1-match-v1"))
        val e2 = assertThrows(UnsignedRuleProfileException::class.java) { ReleaseGate.check(BuildMode.RELEASE) { RuleProfiles(packaged.base, u.match) } }
        assertTrue(e2.message!!.contains("M1-match-v1") && !e2.message!!.contains("P1-base-v1"))
    }

    @Test fun releaseConfigurationWithTheSignedPackagedProfilesStarts() {
        ReleaseGate.check(BuildMode.RELEASE) { packaged }
    }

    @Test fun developmentConfigurationStillPlaysUnsignedProfiles() {
        ReleaseGate.check(BuildMode.DEVELOPMENT) { unsigned() }
        // A development build does not even load the profiles to decide.
        ReleaseGate.check(BuildMode.DEVELOPMENT) { throw AssertionError("profiles loaded by a development build's gate") }
    }

    @Test fun theUnitTestBuildIsADevelopmentBuild() {
        // Unit tests run the debug variant; only the `release` build type sets RELEASE_GATE (app/build.gradle.kts).
        assertFalse(BuildConfig.RELEASE_GATE)
        assertEquals(BuildMode.DEVELOPMENT, BuildMode.of(BuildConfig.RELEASE_GATE))
        assertEquals(BuildMode.RELEASE, BuildMode.of(true))
    }

    @Test fun onlyTheReleaseBuildTypeTurnsTheGateOn() {
        val gradle = File("build.gradle.kts").readText()
        val gates = Regex("""buildConfigField\("boolean", "RELEASE_GATE", "(true|false)"\)""").findAll(gradle).map { it.groupValues[1] }.toList()
        assertEquals("debug, release and r8check each set RELEASE_GATE", listOf("false", "true", "false"), gates)
        val release = gradle.substringAfter("getByName(\"release\") {").substringBefore("}")
        assertTrue("the release build type sets RELEASE_GATE true", release.contains("\"RELEASE_GATE\", \"true\""))
        assertTrue("the release build type is minified", release.contains("isMinifyEnabled = true"))
        // The application enforces it before anything else runs.
        val app = File("src/main/kotlin/cn/wpgd/app/WpgdApplication.kt").readText()
        assertTrue("WpgdApplication.onCreate runs the release gate", Regex("""override fun onCreate\(\)[\s\S]*ReleaseGate\.check\(BuildMode\.of\(BuildConfig\.RELEASE_GATE\)\)""").containsMatchIn(app))
    }
}
