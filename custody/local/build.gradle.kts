// :custody:local — Keystore KEK, LWLE envelope, PIN + attempt limiter, LWRP import/export, device
// capability detection (Phase B). The only module allowed to see :core:crypto besides tests.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.lockwallet.custody.local"
    compileSdk = 36
    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("lockwallet.fixtures", rootProject.file("fixtures/vectors").absolutePath)
            it.inputs.dir(rootProject.file("fixtures/vectors"))
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:crypto"))
    api(project(":core:solana"))
    testImplementation(libs.junit)
    testImplementation(libs.gson)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
