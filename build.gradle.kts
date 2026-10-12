// Root build: plugins are declared here (apply false) so every module shares
// the catalog versions. AGP 9 ships built-in Kotlin: Android modules do NOT
// apply org.jetbrains.kotlin.android; pure JVM modules use kotlin-jvm.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}
