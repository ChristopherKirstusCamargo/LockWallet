// :core:crypto — BIP-39, SLIP-0010 Ed25519 (Phase A); Argon2id, AES-GCM, LWRP (Phase B).
// Spec §5: no Android APIs, no RPC, no logs, no UI, no storage.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    // Spec §6.4: identified, tested implementation; lightweight API only (no provider-name lookup).
    implementation(libs.bouncycastle.bcprov)
    testImplementation(libs.junit)
    testImplementation(libs.gson)
}

tasks.test {
    useJUnit()
    systemProperty("lockwallet.fixtures", rootProject.file("fixtures/vectors").absolutePath)
    inputs.dir(rootProject.file("fixtures/vectors"))
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
