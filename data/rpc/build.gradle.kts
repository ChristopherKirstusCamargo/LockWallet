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
    // The live Mainnet check needs the network, so it is not part of the hermetic suite (nothing is skipped there):
    // it runs only through its own task, :data:rpc:mainnetTest.
    filter { excludeTestsMatching("*MainnetLiveTest") }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Live Mainnet read + simulation check (no funds, nothing signed or sent): ./gradlew :data:rpc:mainnetTest
val mainnetTest by tasks.registering(Test::class) {
    description = "Live Mainnet simulation of the app's exact messages (needs network)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnit()
    filter { includeTestsMatching("*MainnetLiveTest") }
    outputs.upToDateWhen { false }
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
