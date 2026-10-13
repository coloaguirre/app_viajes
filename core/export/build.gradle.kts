// Pure Kotlin/JVM module: no Android dependencies, tests run without emulator.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    // docs/trip.schema.json is the contract with the desktop renderer: the writer is validated against it.
    test {
        resources.srcDir(rootProject.file("docs"))
        resources.srcDir(rootProject.file("tools/sample-trips")) // external GPX fixtures
        resources.srcDir(rootProject.file("tools/plan-fixtures")) // planner conformance cases shared with the desktop renderer
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:geo"))
    implementation(project(":core:timeline"))
    api(project(":core:videoplan"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.json.schema.validator)
}

tasks.withType<Test>().configureEach {
    // PlanConformanceTest: `-DupdatePlanFixtures=true` regenerates tools/plan-fixtures on purpose.
    systemProperty("updatePlanFixtures", System.getProperty("updatePlanFixtures") ?: "false")
    systemProperty("planFixturesDir", rootProject.file("tools/plan-fixtures").absolutePath)
}
