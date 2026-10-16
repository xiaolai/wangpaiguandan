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
    // WI-05: the session wraps the :core reducers and projections. :core must never depend on :session.
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}
tasks.test {
    // Tests read the rule profiles and the shared wire fixtures (the iPhone client reads the same files).
    inputs.dir(rootProject.file("rules/profiles")).withPropertyName("ruleProfiles").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("core/src/test/resources/fixtures/wire")).withPropertyName("wireFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    // The architecture guard scans :core main sources for any reference to this module.
    inputs.dir(rootProject.file("core/src/main")).withPropertyName("coreMainSources").withPathSensitivity(PathSensitivity.RELATIVE)
}
