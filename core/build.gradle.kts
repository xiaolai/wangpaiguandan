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
}
