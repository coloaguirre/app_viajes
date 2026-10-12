// Pure Kotlin/JVM module: no Android dependencies, tests run without emulator.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

sourceSets {
    // Sample trips are shared fixtures: JVM tests, emulator replay and the renderer.
    test { resources.srcDir(rootProject.file("tools/sample-trips")) }
}

dependencies {
    implementation(project(":core:model"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
