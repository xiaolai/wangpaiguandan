import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// WI-10: the `bleTransport` kill-switch flag (plan section 3). Off by default. Off, the app shows no
// Bluetooth entry and its manifest declares no Bluetooth permission; on (`-Pwpgd.bleTransport=true`),
// src/bleTransport/AndroidManifest.xml is merged in. An offline app has no remote switch, so the
// flag is a build property; a flag-on build writes to build-ble/ so the two never share outputs.
val bleTransport: Boolean = providers.gradleProperty("wpgd.bleTransport").map { it.toBooleanStrict() }.getOrElse(false)
if (bleTransport) layout.buildDirectory.set(layout.projectDirectory.dir("build-ble"))

// WI-12: release signing. Off by default, so `assembleRelease` makes an unsigned (not installable)
// artifact for the minify/R8 check. `-Pwpgd.releaseSigning=true` asks for a signed, distributable
// release: the key comes from environment variables or the untracked `release-signing.properties`
// (git-ignored; never commit a key), every value must be present, and the distribution gate
// (`verifyReleaseDistributable`) must pass first. Missing values fail the build at configuration.
val releaseSigningRequested: Boolean = providers.gradleProperty("wpgd.releaseSigning").map { it.toBooleanStrict() }.getOrElse(false)
// The distribution gate's profile-folder override (verifyReleaseDistributable, below) exists for
// tools/ci.sh's negative controls. The APK packages rules/profiles, so a signed release never takes it.
val gateProfilesOverride: String? = providers.gradleProperty("wpgd.gateProfilesDir").orNull
if (releaseSigningRequested && gateProfilesOverride != null) {
    throw GradleException("wpgd.gateProfilesDir is for gate tests only: a signed release must gate the packaged rules/profiles it ships")
}
val releaseSigningKeys = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
val releaseSigningValues: Map<String, String> = if (!releaseSigningRequested) emptyMap() else {
    val file = rootProject.file("release-signing.properties")
    val props = Properties()
    if (file.isFile) file.inputStream().use { props.load(it) }
    val values: Map<String, String?> = releaseSigningKeys.associateWith { k -> providers.environmentVariable("WPGD_RELEASE_$k").orNull ?: props.getProperty(k) }
    val missing = values.filterValues { it.isNullOrBlank() }.keys
    if (missing.isNotEmpty()) {
        throw GradleException(
            "wpgd.releaseSigning=true but the release key is incomplete: missing ${missing.joinToString { "WPGD_RELEASE_$it" }} " +
                "(environment variables, or the same names without the WPGD_RELEASE_ prefix in ${file.path}). No release is signed with a partial key.",
        )
    }
    val store = rootProject.file(checkNotNull(values["STORE_FILE"]))
    if (!store.isFile) throw GradleException("wpgd.releaseSigning=true but the key store ${store.path} does not exist")
    values.mapValues { checkNotNull(it.value) }
}

// WI-12: which test build type the on-device tests run against. Default `debug`; `r8check` runs them
// against a minified build (same R8 rules as release) to show serialization survives shrinking.
val testBuildTypeName: String = providers.gradleProperty("wpgd.testBuildType").getOrElse("debug")

android {
    namespace = "cn.wpgd.app"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "cn.wpgd.app"
        minSdk = 26
        targetSdk = 36
        // First release: 0.0.1, code 1. Every later test or release build raises versionCode (HP-6 installs N-1, then N).
        versionCode = 1
        versionName = "0.0.1"
        // WI-08b: Compose UI tests on a device or emulator (:app:connectedDebugAndroidTest).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "BLE_TRANSPORT", bleTransport.toString())
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    if (releaseSigningRequested) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(releaseSigningValues.getValue("STORE_FILE"))
            storePassword = releaseSigningValues.getValue("STORE_PASSWORD")
            keyAlias = releaseSigningValues.getValue("KEY_ALIAS")
            keyPassword = releaseSigningValues.getValue("KEY_PASSWORD")
        }
    }
    buildTypes {
        // WI-12 release gate (plan section 3): RELEASE_GATE makes the app refuse to start with an
        // unsigned (provisional) rule profile (cn.wpgd.app.release.ReleaseGate). Only `release` sets it.
        getByName("debug") {
            buildConfigField("boolean", "RELEASE_GATE", "false")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "RELEASE_GATE", "true")
            signingConfig = if (releaseSigningRequested) signingConfigs.getByName("release") else null
        }
        // Same shrinking and R8 rules as release, signed with the debug key and without the release
        // gate, so the app and its on-device tests run minified (`-Pwpgd.testBuildType=r8check`). A
        // check build only: never distributed.
        create("r8check") {
            initWith(getByName("release"))
            buildConfigField("boolean", "RELEASE_GATE", "false")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            proguardFile("proguard-r8check-rules.pro")
            testProguardFiles("proguard-test-rules.pro")
        }
    }
    testBuildType = testBuildTypeName
    // WI-08: solo play reads the rule profiles from the same files the JVM tests and the iPhone client use.
    sourceSets.getByName("main").assets.srcDir(rootProject.file("rules/profiles"))
    // WI-08b: one set of table fixtures (a recording TableSession, a playing-view builder) for the
    // JVM tests and the on-device tests, so both exercise the same scenarios.
    sourceSets.getByName("test").kotlin.srcDir("src/sharedTest/kotlin")
    sourceSets.getByName("androidTest").kotlin.srcDir("src/sharedTest/kotlin")
    // WI-12: R8SerializationTest round-trips the shared wire fixtures on a device (also under r8check).
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("core/src/test/resources/fixtures/wire"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
androidComponents {
    onVariants { variant ->
        if (bleTransport) variant.sources.manifests.addStaticManifestFile("src/bleTransport/AndroidManifest.xml")
    }
}
dependencies {
    implementation(project(":core"))
    // WI-08: the session owner hosts an in-process SessionHost and SessionClient for solo play.
    implementation(project(":session"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.lifecycle.viewmodel)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    debugImplementation(libs.compose.tooling)
    // WI-06: BackupRulesTest (JVM unit test of the manifest and data-extraction rules, audit A29, P7).
    // WI-08: the table logic tests (JVM unit tests, no Android framework).
    testImplementation(libs.junit)
    // WI-08b: Compose UI tests (versions from the Compose BOM) and the androidx.test runner.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.junit)
    // WI-12: R8SerializationTest passes MatchStateCodec's JsonObject between its encode and decode (types only).
    androidTestImplementation(libs.kotlinx.serialization.json)
    // Declares the empty activity createComposeRule() launches; debug builds only, no permission.
    debugImplementation(libs.compose.ui.test.manifest)
    // WI-12: the on-device tests also run against the minified r8check build (never distributed).
    "r8checkImplementation"(libs.compose.ui.test.manifest)
    // WI-12: CapabilityCatalogTest and LicenseInventoryTest read the checked-in JSON under release/.
    testImplementation(libs.kotlinx.serialization.json)
}

// WI-12 license inventory (GDN-107): every module packaged into the release APK, transitive ones
// included, one line each: `group:name:version<TAB>licenses declared in its POM` (a POM without a
// <licenses> element inherits its parent's). First-party modules are `project :name<TAB>-`.
// LicenseInventoryTest fails when any of them has no entry in release/license-inventory.json, or
// when an entry's license differs from what the POM declares.
val pomQuery: DependencyHandler = dependencies
fun pomLicenses(group: String, module: String, version: String, depth: Int = 0): List<String> {
    check(depth < 6) { "POM parent chain too deep at $group:$module:$version" }
    val result = pomQuery.createArtifactResolutionQuery()
        .forModule(group, module, version)
        .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
        .execute()
    val pom = result.resolvedComponents.flatMap { it.getArtifacts(MavenPomArtifact::class.java) }
        .filterIsInstance<org.gradle.api.artifacts.result.ResolvedArtifactResult>().singleOrNull()?.file
        ?: throw GradleException("no POM resolved for $group:$module:$version: the license inventory needs every POM")
    val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(pom)
    val root = doc.documentElement
    fun child(e: org.w3c.dom.Element, name: String): org.w3c.dom.Element? =
        (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>().firstOrNull { it.tagName == name }
    val licenses = child(root, "licenses")?.let { ls ->
        (0 until ls.childNodes.length).map { ls.childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>()
            .filter { it.tagName == "license" }.map { (child(it, "name")?.textContent ?: "").trim() }.filter { it.isNotEmpty() }
    }.orEmpty()
    if (licenses.isNotEmpty()) return licenses
    val parent = child(root, "parent") ?: return emptyList()
    return pomLicenses(child(parent, "groupId")!!.textContent.trim(), child(parent, "artifactId")!!.textContent.trim(), child(parent, "version")!!.textContent.trim(), depth + 1)
}
val releaseRuntimeComponents = tasks.register("releaseRuntimeComponents") {
    val root = configurations.named("releaseRuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
    val out = layout.buildDirectory.file("licenses/release-runtime-components.txt")
    inputs.property("components", root.map { r ->
        val seen = LinkedHashSet<String>()
        fun walk(c: org.gradle.api.artifacts.result.ResolvedComponentResult) {
            val id = when (val i = c.id) {
                is org.gradle.api.artifacts.component.ModuleComponentIdentifier -> "${i.group}:${i.module}:${i.version}"
                is org.gradle.api.artifacts.component.ProjectComponentIdentifier -> "project ${i.projectPath}"
                else -> throw GradleException("unexpected component ${i.displayName} on releaseRuntimeClasspath")
            }
            if (!seen.add(id)) return
            c.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().filter { !it.isConstraint }.forEach { walk(it.selected) }
        }
        walk(r)
        seen.filter { it != "project :app" }.sorted()
    })
    outputs.file(out)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val list = inputs.properties["components"] as List<String>
        check(list.isNotEmpty()) { "releaseRuntimeClasspath resolved to no component" }
        val lines = list.map { c ->
            if (c.startsWith("project ")) "$c\t-" else {
                val (g, m, v) = c.split(":")
                "$c\t" + pomLicenses(g, m, v).joinToString(" | ").ifEmpty { "NONE DECLARED" }
            }
        }
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

// WI-12 distribution gate (plan section 3, G1a/G1b; GDN-107). A signed release must not carry a
// provisional rule profile, nor a profile without a signed decision record naming it under
// dev-docs/decisions/ ("**Status: SIGNED**" plus the profile id, version and canonical hash, the same
// facts CapabilityCatalogTest requires), and must not ship before the project license is recorded. The
// hash is computed by the app's own loader (`:core:ruleProfileDigests`), so an option changed under an
// unchanged id and version no longer matches its signed decision. Runs before every signed release
// build; run it alone with `./gradlew :app:verifyReleaseDistributable`. `-Pwpgd.gateProfilesDir=<dir>`
// points it at another profile folder, for tools/ci.sh's negative controls only: the APK packages
// rules/profiles, so a signed release build refuses the override rather than gate other files.
// (The override is refused for a signed release where releaseSigningRequested is read, above.)
val gateProfilesDir: File = gateProfilesOverride?.let { File(it) } ?: rootProject.file("rules/profiles")
val verifyReleaseDistributable = tasks.register("verifyReleaseDistributable") {
    val decisions = rootProject.fileTree("dev-docs/decisions") { include("*.md") }
    val license = rootProject.file("release/PROJECT-LICENSE.md")
    val digests = project(":core").layout.buildDirectory.file("gate/rule-profile-digests.tsv")
    dependsOn(":core:ruleProfileDigests")
    inputs.file(digests).withPropertyName("profileDigests")
    inputs.files(decisions).withPropertyName("decisions")
    inputs.file(license).withPropertyName("projectLicense")
    doLast {
        val problems = ArrayList<String>()
        // One line per profile file: name, id, version, provisional, hash; or name, ERROR, message.
        val rows = digests.get().asFile.readLines().filter { it.isNotBlank() }.map { it.split("\t") }
        if (rows.isEmpty()) problems += "no rule profile under ${gateProfilesDir.path}"
        val signed = decisions.files.map { it.readText() }.filter { Regex("(?m)^\\*\\*Status: SIGNED\\*\\*$").containsMatchIn(it) }
        for (row in rows) {
            if (row.size == 3 && row[1] == "ERROR") {
                problems += "rule profile file ${row[0]} does not load: ${row[2]}"
                continue
            }
            check(row.size == 5) { "malformed profile digest line: $row" }
            val (_, id, version, provisional, hash) = row
            if (provisional != "false") {
                problems += "rule profile $id $version is provisional (unsigned; gates G1a/G1b)"
            } else if (signed.none { it.contains("`$id`") && it.contains(version) && it.contains(hash) }) {
                problems += "rule profile $id $version (hash $hash) has no signed decision naming its id, version and hash under dev-docs/decisions/ (gates G1a/G1b)"
            }
        }
        // Same rule as LicenseInventoryTest: recorded = an SPDX identifier line and no placeholder status line.
        val text = license.readText()
        val recorded = Regex("(?m)^SPDX-License-Identifier: \\S+$").containsMatchIn(text) && !text.contains("**Status: OWNER DECISION REQUIRED**")
        if (!recorded) problems += "project license not recorded: ${license.path} says OWNER DECISION REQUIRED"
        if (problems.isNotEmpty()) throw GradleException("This build is not distributable:\n  - " + problems.joinToString("\n  - "))
        logger.lifecycle("Distribution gate passed: ${rows.size} signed profiles, each with a signed decision; project license recorded.")
    }
}
if (releaseSigningRequested) {
    tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseDistributable) }
}

tasks.withType<Test>().configureEach {
    // WI-08 tests read the rule profiles the app packages; editing a profile must re-run them.
    inputs.dir(rootProject.file("rules/profiles")).withPropertyName("ruleProfiles").withPathSensitivity(PathSensitivity.RELATIVE)
    // WI-12: the catalog, inventory and evidence files the release tests check.
    inputs.dir(rootProject.file("release")).withPropertyName("releaseRecords").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(rootProject.fileTree("dev-docs") { include("evidence/**", "decisions/**") }).withPropertyName("evidenceRecords").withPathSensitivity(PathSensitivity.RELATIVE)
    val components = layout.buildDirectory.file("licenses/release-runtime-components.txt")
    dependsOn(releaseRuntimeComponents)
    inputs.file(components).withPropertyName("releaseRuntimeComponents").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("wpgd.releaseRuntimeComponents", components.get().asFile.absolutePath)
}
