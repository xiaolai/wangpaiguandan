import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies {
    // Rule profile loader (WI-02): profiles are JSON data files under rules/profiles/.
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}
tasks.test {
    // Tests read rules/profiles/*.json from the repository root; editing a profile must re-run them.
    inputs.dir(rootProject.file("rules/profiles")).withPropertyName("ruleProfiles").withPathSensitivity(PathSensitivity.RELATIVE)
    // AuditRegressionIndexTest reads the implementation plan from the sibling dev-docs repository and the test
    // sources of every module. Declare both, or an edit leaves the previous green result in place.
    val plan = generateSequence(rootProject.rootDir) { it.parentFile }
        .map { File(it, "dev-docs/implementation-plan.md") }
        .firstOrNull { it.isFile }
    if (plan != null) inputs.file(plan).withPropertyName("implementationPlan").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        listOf("core", "session", "app")
            .flatMap { m -> listOf("test", "androidTest", "sharedTest").map { File(rootProject.rootDir, "$m/src/$it") } }
            .filter { it.isDirectory }
            // A FileTree, not the directory: inputs.files() fingerprints a directory as one opaque entry,
            // so a changed test file inside it would otherwise leave the previous result in place.
            .map { rootProject.fileTree(it) },
    ).withPropertyName("siblingTestSources").withPathSensitivity(PathSensitivity.RELATIVE)
}

// WI-12 release gate helper (src/gate, cn.wpgd.app.core.gate.ProfileDigests): each rule profile's id,
// version, provisional flag and canonical hash, computed by the app's own loader, for
// `:app:verifyReleaseDistributable`. A separate source set, so `main` keeps no `java.*` dependency.
// It reads the same profile folder as the gate: rules/profiles, or `-Pwpgd.gateProfilesDir` (app/build.gradle.kts).
val gate: SourceSet = sourceSets.create("gate") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += output + compileClasspath + sourceSets.main.get().runtimeClasspath
}
tasks.register<JavaExec>("ruleProfileDigests") {
    val dir = providers.gradleProperty("wpgd.gateProfilesDir").map { File(it) }.getOrElse(rootProject.file("rules/profiles"))
    val profiles = fileTree(dir) { include("*.json") }
    val out = layout.buildDirectory.file("gate/rule-profile-digests.tsv")
    inputs.files(profiles).withPropertyName("profiles")
    inputs.property("profilesDir", dir.absolutePath)
    outputs.file(out)
    classpath = gate.runtimeClasspath
    mainClass.set("cn.wpgd.app.core.gate.ProfileDigestsKt")
    argumentProviders.add(CommandLineArgumentProvider { listOf(out.get().asFile.absolutePath) + profiles.files.map { it.absolutePath } })
}
