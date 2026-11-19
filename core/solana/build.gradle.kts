// :core:solana — types, Base58, program IDs (Phase A); codec, parser, allowlist, Token-2022 TLV (later gates).
// Spec §5: no network, no UI dependency, no automatic signing.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    testImplementation(project(":core:crypto")) // test-only: address cross-check of derivation vectors
    testImplementation(libs.junit)
    testImplementation(libs.gson)
}

tasks.test {
    useJUnit()
    systemProperty("lockwallet.fixtures", rootProject.file("fixtures/vectors").absolutePath)
    inputs.dir(rootProject.file("fixtures/vectors"))
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
