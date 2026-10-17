package cn.wpgd.app.release

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WI-12 (GDN-107, GDN-064): every component packaged into the release APK, transitive ones included,
 * and every file the app ships as an asset or resource (fonts included) maps to an entry in
 * `release/license-inventory.json`, and nothing is listed that is not shipped.
 *
 * The component list comes from Gradle itself (`:app:releaseRuntimeComponents`, which resolves
 * `releaseRuntimeClasspath` and reads each POM's declared licenses), so a new or bumped dependency
 * fails here until someone records its license. The project's own license is the owner's decision
 * (`release/PROJECT-LICENSE.md`); this test only checks the file is coherent, and the distribution
 * gate (`:app:verifyReleaseDistributable`) refuses a signed release until it is recorded.
 */
class LicenseInventoryTest {
    private val root = File("..").canonicalFile
    private val inventory: JsonObject = Json.parseToJsonElement(File(root, "release/license-inventory.json").readText()).jsonObject

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.list(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

    /** `component -> POM-declared licenses` as Gradle resolved them for this build. */
    private val resolved: Map<String, String> by lazy {
        val path = checkNotNull(System.getProperty("wpgd.releaseRuntimeComponents")) {
            "system property wpgd.releaseRuntimeComponents not set: run through Gradle (app/build.gradle.kts wires it)"
        }
        val f = File(path)
        check(f.isFile) { "release component list missing at $path (task :app:releaseRuntimeComponents)" }
        f.readLines().filter { it.isNotBlank() }.associate { it.substringBefore('\t') to it.substringAfter('\t') }.also {
            check(it.size > 10) { "release component list has only ${it.size} entries: the resolution looks broken" }
        }
    }

    /** POM license names this project has reviewed, and the SPDX identifier each one means. */
    private val reviewedLicenseNames = mapOf(
        "The Apache Software License, Version 2.0" to "Apache-2.0",
        "The Apache License, Version 2.0" to "Apache-2.0",
        "Apache-2.0" to "Apache-2.0",
        "Apache License, Version 2.0" to "Apache-2.0",
    )

    @Test fun everyReleaseDependencyHasAnEntryAndNoEntryIsStale() {
        val modules = resolved.keys.filter { !it.startsWith("project ") }.toSet()
        val listed = inventory.list("dependencies").map { it.str("module") }
        assertEquals("a module is listed once", listed.size, listed.toSet().size)
        val missing = modules - listed.toSet()
        val stale = listed.toSet() - modules
        assertTrue("release dependencies without a license entry in release/license-inventory.json: $missing", missing.isEmpty())
        assertTrue("license entries for modules the release no longer ships: $stale", stale.isEmpty())
        val firstParty = resolved.keys.filter { it.startsWith("project ") }.toSet()
        assertEquals("first-party modules", firstParty, inventory.list("firstParty").map { it.str("component") }.toSet())
        assertTrue("the release ships :core and :session", firstParty.containsAll(setOf("project :core", "project :session")))
    }

    @Test fun everyEntryMatchesTheLicenseItsPomDeclares() {
        for (e in inventory.list("dependencies")) {
            val module = e.str("module")
            val declared = resolved.getValue(module)
            assertEquals("$module: the inventory's 'declared' must equal the POM's licenses", declared, e.str("declared"))
            val names = declared.split(" | ")
            for (n in names) assertTrue("$module declares '$n', which nobody has reviewed: add it to reviewedLicenseNames only after checking its terms", n in reviewedLicenseNames)
            assertEquals("$module: SPDX id", names.map { reviewedLicenseNames.getValue(it) }.distinct().joinToString(" OR "), e.str("license"))
        }
    }

    /** Files the APK carries besides code: resources under app/src/main/res, assets dirs, and the packaged rule profiles. */
    private fun shippedFiles(): Set<String> {
        val dirs = listOf("app/src/main/res", "app/src/main/assets", "rules/profiles")
        return dirs.flatMap { d -> File(root, d).walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path } }.toSet()
    }

    @Test fun everyShippedAssetAndFontHasAnEntry() {
        val listed = inventory.list("assets").map { it.str("path") }
        val shipped = shippedFiles()
        assertTrue("found no shipped resource: the scan is broken", shipped.size >= 4)
        val missing = shipped - listed.toSet()
        assertTrue("shipped files without a license entry: $missing", missing.isEmpty())
        for (p in listed) assertTrue("asset entry $p does not exist", File(root, p).isFile)
        for (a in inventory.list("assets")) assertTrue("${a.str("path")}: license", a.str("license") in setOf("PROJECT", "OWNER-DECISION") || a.str("license").matches(Regex("[A-Za-z0-9.+-]+")))
        // Fonts anywhere in the app's sources (bundled or not yet wired) need an entry under "fonts".
        val fontExt = setOf("ttf", "otf", "ttc", "woff", "woff2")
        val fonts = File(root, "app/src").walkTopDown().filter { it.isFile && it.extension.lowercase() in fontExt }.map { it.relativeTo(root).path }.toSet()
        val fontEntries = inventory.list("fonts").map { it.str("path") }.toSet()
        assertEquals("font files and font entries", fonts, fontEntries)
        // The design tokens compiled into the app come from supplied design material: they must be listed.
        assertTrue("generated design tokens are listed", "app/src/main/kotlin/cn/wpgd/app/ui/DesignTokens.kt" in listed)
    }

    @Test fun projectLicenseIsEitherRecordedOrExplicitlyAwaitingTheOwner() {
        assertEquals("release/PROJECT-LICENSE.md", inventory.str("projectLicense"))
        val text = File(root, "release/PROJECT-LICENSE.md").readText()
        val spdx = Regex("(?m)^SPDX-License-Identifier: (\\S+)$").find(text)?.groupValues?.get(1)
        val placeholder = text.contains("**Status: OWNER DECISION REQUIRED**")
        assertTrue("PROJECT-LICENSE.md must either await the owner or record an SPDX id, not both or neither", (spdx == null) == placeholder)
        if (spdx != null) {
            val license = File(root, "LICENSE")
            assertTrue("a recorded project license ($spdx) needs its full text in LICENSE at the repository root", license.isFile)
            // The first line of LICENSE names the license the SPDX line records (add a pair here when the license changes).
            val titles = mapOf("MIT" to "MIT License")
            val title = titles[spdx] ?: throw AssertionError("no known LICENSE title for SPDX id $spdx: add it after checking the text")
            assertEquals("LICENSE is the $spdx text", title, license.readLines().firstOrNull { it.isNotBlank() }?.trim())
            // Recorded: no entry may still point at the unrecorded placeholder.
            assertTrue("first-party modules carry the recorded license $spdx", inventory.list("firstParty").all { it.str("license") == spdx })
            val placeholders = inventory.list("assets").filter { it.str("license") == "PROJECT" }.map { it.str("path") }
            assertTrue("assets still marked PROJECT although the license is recorded: $placeholders", placeholders.isEmpty())
        } else {
            // Not recorded: the entries that depend on it must say so, and the distribution gate refuses (see verifyReleaseDistributable).
            assertTrue(inventory.list("firstParty").all { it.str("license") == "PROJECT" })
            println("PROJECT LICENSE: OWNER DECISION REQUIRED (release/PROJECT-LICENSE.md); a signed release is refused until it is recorded")
        }
    }
}
