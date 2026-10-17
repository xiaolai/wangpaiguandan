// WI-12: newer R8 than AGP 8.11 bundles, required by Kotlin 2.4 (reason in gradle/libs.versions.toml).
buildscript {
    repositories { google() }
    dependencies { classpath(libs.r8) }
}
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
