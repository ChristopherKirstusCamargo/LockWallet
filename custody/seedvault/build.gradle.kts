// :custody:seedvault — Solana Mobile Seed Vault custody provider (Phase G, spec §23.3, ADR 0021).
// The seed never enters this process: Seed Vault holds it, authenticates the user and signs.
// This module only stores public data (auth token, derivation path, public key) and verifies signatures.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.lockwallet.custody.seedvault"
    compileSdk = 36
    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":custody:local"))
    implementation(project(":core:crypto"))
    implementation(project(":core:solana"))
    implementation(libs.seedvault.sdk)
    testImplementation(project(":core:crypto"))
    testImplementation(libs.bouncycastle.bcprov)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
