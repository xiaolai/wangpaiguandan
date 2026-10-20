package cn.wpgd.app.release

import cn.wpgd.app.BuildConfig
import cn.wpgd.app.core.ai.BaselineHeuristicPolicy
import cn.wpgd.app.core.rules.MatchOptions
import cn.wpgd.app.core.rules.RuleOptions
import cn.wpgd.app.core.rules.RuleProfile
import cn.wpgd.app.core.rules.RuleProfileLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * WI-12 (GDN-046, GDN-110): `release/capability-catalog.json` lists only rule profiles, transports,
 * platforms and AI policies that have recorded evidence, and never more than that evidence shows.
 *
 * Evidence kinds: `jvm-test` and `emulator-test` name a test source in this repository that holds
 * at least one `@Test`; `device-record` names a filled result under `dev-docs/evidence/` that says
 * `Outcome: PASS`. Bluetooth and iPhone are radio and device claims, so only a device record can
 * admit them; none exists, so neither may be listed.
 */
class CapabilityCatalogTest {
    private val root = File("..").canonicalFile

    // `dev-docs/` is a sibling repository: one level above the primary checkout, two above a linked
    // worktree in `worktrees/<name>/`, so a fixed `..` is wrong there. Probe upward for the directory
    // that holds the plan; the hosted runner has this repository alone.
    private val workspace: File? = generateSequence(root) { it.parentFile }
        .firstOrNull { File(it, "dev-docs/implementation-plan.md").isFile }

    @Before fun requireTheRecords() {
        assumeTrue("dev-docs/ not found at or above $root: skipping the catalog evidence and decision checks", workspace != null)
    }

    /** A catalogue path names a file in this repository, or one in the sibling `dev-docs/` repository. */
    private fun repoFile(path: String): File = File(if (path.startsWith("dev-docs/")) checkNotNull(workspace) else root, path)

    private val catalog: JsonObject = Json.parseToJsonElement(File(root, "release/capability-catalog.json").readText()).jsonObject

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.list(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun checkEvidence(owner: String, evidence: List<JsonObject>) {
        assertTrue("$owner lists no evidence", evidence.isNotEmpty())
        for (e in evidence) {
            val kind = e.str("kind")
            val path = e.str("path")
            val f = repoFile(path)
            assertTrue("$owner: evidence $path does not exist", f.isFile)
            when (kind) {
                "jvm-test" -> {
                    assertTrue("$owner: $path is not a JVM test source", path.contains("/src/test/"))
                    assertTrue("$owner: $path holds no @Test", f.readText().contains("@Test"))
                }
                "emulator-test" -> {
                    assertTrue("$owner: $path is not an on-device test source", path.startsWith("app/src/androidTest/"))
                    assertTrue("$owner: $path holds no @Test", f.readText().contains("@Test"))
                }
                "device-record" -> {
                    assertTrue("$owner: $path is not under dev-docs/evidence/", path.startsWith("dev-docs/evidence/"))
                    assertTrue("$owner: $path does not record 'Outcome: PASS'", Regex("(?m)^Outcome: PASS\\b").containsMatchIn(f.readText()))
                }
                else -> throw AssertionError("$owner: unknown evidence kind '$kind'")
            }
        }
    }

    private fun hasDeviceRecord(evidence: List<JsonObject>) = evidence.any { it.str("kind") == "device-record" }

    /** The profiles the app packages (app/build.gradle.kts adds rules/profiles/ to the APK assets). */
    private val packaged: Map<String, RuleProfile> by lazy {
        File(root, "rules/profiles").listFiles { f -> f.extension == "json" }!!.associate { f ->
            val text = f.readText()
            val registry = if (f.name.startsWith("M")) MatchOptions.registry else RuleOptions.registry
            val p = RuleProfileLoader.parse(text, registry).getOrThrow()
            p.id to p
        }
    }

    @Test fun catalogListsExactlyThePackagedProfilesWithMatchingIdVersionAndHash() {
        val listed = catalog.list("profiles")
        assertEquals("the catalog must list every packaged profile and no other", packaged.keys, listed.map { it.str("id") }.toSet())
        for (entry in listed) {
            val p = packaged.getValue(entry.str("id"))
            assertEquals("${p.id} version", p.version, entry.str("version"))
            assertEquals("${p.id} hash: the catalog records the evidence for one exact profile; re-record it after a profile change", p.hash, entry.str("hash"))
            assertEquals("${p.id} kind", if (p.id.startsWith("M")) "match" else "base", entry.str("kind"))
        }
    }

    /**
     * Why [entry] may not be listed as a release profile, or an empty list when it may: a release
     * profile is not provisional and cites a signed decision under dev-docs/decisions/ that names its
     * exact id, version and hash and says it is signed. A provisional profile is development-only and
     * has no signed decision.
     */
    private fun releaseProblems(entry: JsonObject, p: RuleProfile): List<String> {
        val problems = ArrayList<String>()
        val status = entry.str("status")
        if (status !in setOf("development-only", "release")) problems += "${p.id}: unknown status $status"
        if (p.provisional) {
            if (status != "development-only") problems += "${p.id} is provisional (unsigned), so it is development-only"
            if (entry.getValue("signedDecision") !is JsonNull) problems += "${p.id} is provisional, so it has no signed decision"
        }
        if (status == "release") {
            if (p.provisional) problems += "${p.id} is release but provisional"
            val decision = (entry.getValue("signedDecision") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val file = decision?.takeIf { it.startsWith("dev-docs/decisions/") }?.let { repoFile(it) }
            if (file == null || !file.isFile) {
                problems += "${p.id}: a release profile cites its signed decision under dev-docs/decisions/"
            } else {
                val text = file.readText()
                if (!Regex("(?m)^\\*\\*Status: SIGNED\\b").containsMatchIn(text)) problems += "${p.id}: $decision does not say **Status: SIGNED**"
                for (fact in listOf(p.id, p.version, p.hash)) if (!text.contains(fact)) problems += "${p.id}: $decision does not name $fact"
            }
        }
        return problems
    }

    @Test fun provisionalProfilesAreNeverReleaseReady() {
        for (entry in catalog.list("profiles")) {
            val p = packaged.getValue(entry.str("id"))
            assertEquals(emptyList<String>(), releaseProblems(entry, p))
        }
    }

    /** G1a and G1b are signed (2026-10-16): the catalog lists only signed profiles, each released against its decision. */
    @Test fun theCatalogListsOnlySignedReleaseProfiles() {
        val listed = catalog.list("profiles")
        assertTrue(listed.isNotEmpty())
        for (entry in listed) {
            val p = packaged.getValue(entry.str("id"))
            assertFalse("${p.id} is provisional: only signed profiles are listed", p.provisional)
            assertEquals("${p.id} status", "release", entry.str("status"))
        }
        assertFalse("rules are signed: no 'signed-rules' omission", catalog.list("omissions").any { it.str("id") == "signed-rules" })
    }

    /** The check above refuses an unsigned profile listed as release, a decision that names another version and hash, and a missing decision. */
    @Test fun releaseCheckRefusesAnUnsignedProfileOrAMismatchedDecision() {
        val entry = catalog.list("profiles").first()
        val p = packaged.getValue(entry.str("id"))
        val unsigned = RuleProfileLoader.parse(
            File(root, "rules/profiles/${p.id}.json").readText().replace("\"provisional\": false", "\"provisional\": true"),
            if (p.id.startsWith("M")) MatchOptions.registry else RuleOptions.registry,
        ).getOrThrow()
        assertTrue(unsigned.provisional)
        assertTrue(releaseProblems(entry, unsigned).any { it.contains("release but provisional") })
        // The same profile at another version has another hash; the decision names neither.
        val renamed = RuleProfileLoader.parse(
            File(root, "rules/profiles/${p.id}.json").readText().replace("\"version\": \"${p.version}\"", "\"version\": \"9.9.9\""),
            if (p.id.startsWith("M")) MatchOptions.registry else RuleOptions.registry,
        ).getOrThrow()
        assertTrue(renamed.hash != p.hash)
        assertTrue(releaseProblems(entry, renamed).any { it.contains("does not name") })
        val noDecision = JsonObject(entry + ("signedDecision" to JsonNull))
        assertTrue(releaseProblems(noDecision, p).any { it.contains("cites its signed decision") })
    }

    @Test fun everyProfileHasProvenanceFixturesEvidenceAndAnUnsupportedList() {
        for (entry in catalog.list("profiles")) {
            val id = entry.str("id")
            val p = packaged.getValue(id)
            for (path in entry.strings("provenance") + entry.strings("fixtures")) assertTrue("$id: $path does not exist", repoFile(path).isFile)
            assertTrue("$id has no provenance", entry.strings("provenance").isNotEmpty())
            assertTrue("$id has no fixtures", entry.strings("fixtures").isNotEmpty())
            checkEvidence(id, entry.list("evidence"))
            assertTrue("$id: GDN-046 needs a visible unsupported-settings list", entry.strings("unsupported").isNotEmpty())
            // Every option the registry marks pending must be named as unsupported-until-decided.
            val registry = if (entry.str("kind") == "match") MatchOptions.registry else RuleOptions.registry
            val pending = registry.all.filter { it.pending }.map { it.id }.toSet()
            assertEquals("$id: pending options", pending, entry.strings("pendingOptions").toSet())
            for (o in pending) assertTrue("$id: '$o' is pending but no unsupported line names it", entry.strings("unsupported").any { it.contains(o) })
            if (p.provisional) assertTrue("$id: a provisional profile's unsupported list says it is not signed", entry.strings("unsupported").any { it.contains("Not signed") })
        }
    }

    @Test fun everyTransportPlatformAndAiEntryHasRecordedEvidence() {
        for (t in catalog.list("transports")) checkEvidence("transport ${t.str("id")}", t.list("evidence"))
        for (p in catalog.list("platforms")) checkEvidence("platform ${p.str("id")}", p.list("evidence"))
        for (a in catalog.list("ai")) checkEvidence("ai ${a.str("policy")}", a.list("evidence"))
        val transports = catalog.list("transports").map { it.str("id") }
        val platforms = catalog.list("platforms").map { it.str("id") }
        assertTrue("unknown transport in $transports", transports.all { it in setOf("in-process", "lan", "bluetooth") })
        assertTrue("unknown platform in $platforms", platforms.all { it in setOf("android", "iphone") })
        assertEquals("a transport is listed once", transports.size, transports.toSet().size)
    }

    @Test fun bluetoothAndIphoneAreListedOnlyWithADeviceRecord() {
        val bt = catalog.list("transports").filter { it.str("id") == "bluetooth" }
        val ios = catalog.list("platforms").filter { it.str("id") == "iphone" }
        for (e in bt + ios) assertTrue("${e.str("id")} is a device claim: it needs a device-record", hasDeviceRecord(e.list("evidence")))
        // No device record exists in this repository yet, so neither may be listed at all.
        val records = File(checkNotNull(workspace), "dev-docs/evidence").walkTopDown().filter { it.isFile && Regex("(?m)^Outcome: PASS\\b").containsMatchIn(it.readText()) }.toList()
        if (records.isEmpty()) {
            assertTrue("Bluetooth is listed without any device record", bt.isEmpty())
            assertTrue("iPhone is listed without any device record", ios.isEmpty())
        }
        // This build has Bluetooth switched off: it may not claim it whatever the records say.
        if (!BuildConfig.BLE_TRANSPORT) assertTrue("this build has no Bluetooth entry", bt.isEmpty())
        val omitted = catalog.list("omissions").map { it.str("id") }.toSet()
        if (bt.isEmpty()) assertTrue("an absent Bluetooth transport is named as an omission", "bluetooth" in omitted)
        if (ios.isEmpty()) assertTrue("an absent iPhone client is named as an omission", "iphone" in omitted)
    }

    @Test fun claimsMatchTheCodeTheyDescribe() {
        val android = catalog.list("platforms").single { it.str("id") == "android" }
        assertEquals("the catalog's Android floor is the build's minSdk", 26, android.getValue("minSdk").jsonPrimitive.int)
        val gradle = File("build.gradle.kts").readText()
        assertTrue("app/build.gradle.kts minSdk changed: re-record the catalog", gradle.contains("minSdk = 26"))
        val ai = catalog.list("ai").single()
        val policy = BaselineHeuristicPolicy.id
        assertEquals(policy.name, ai.str("policy"))
        assertEquals(policy.version, ai.str("version"))
        // Every entry says what is still owed rather than leaving it implied.
        for (section in listOf("transports", "platforms", "ai")) for (e in catalog.list(section)) {
            assertTrue("$section entry ${e["id"] ?: e["policy"]} has no claim", e.str("claim").isNotBlank())
            assertTrue("$section entry ${e["id"] ?: e["policy"]} must list what is not yet evidenced", e.getValue("notYetEvidenced") is JsonArray)
        }
        assertTrue(catalog.getValue("schema") is JsonPrimitive)
    }
}
