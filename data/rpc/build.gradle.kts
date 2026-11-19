// :data:rpc — JSON-RPC over HTTPS, Network Manager (Devnet/Testnet, genesis check, custom RPC), transaction
// coordinator + journal, chain queries (Phase D/E/F). No secrets ever cross this module (spec §12.1).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    api(project(":core:solana"))
    testImplementation(project(":core:crypto")) // test-only: signing in the live Devnet check
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    systemProperty("lockwallet.fixtures", rootProject.file("fixtures/vectors").absolutePath)
    inputs.dir(rootProject.file("fixtures/vectors"))
    // Live Mainnet read+simulation check (no funds, nothing signed or sent): ./gradlew :data:rpc:test -Plockwallet.mainnet=true
    systemProperty("lockwallet.mainnet", (project.findProperty("lockwallet.mainnet") ?: "false").toString())
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
