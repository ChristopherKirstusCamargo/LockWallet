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

// The Default Ring SDK is Rust-only. Build the pinned JNI bridge for Android.
val privacyJniLibs = layout.buildDirectory.dir("generated/privacyJniLibs")
android.sourceSets.getByName("main").jniLibs.srcDir(privacyJniLibs)

val buildPrivacyRingNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the pinned Solana Default Ring Rust SDK bridge for Android."
    val manifest = rootProject.file("native/privacy-ring/Cargo.toml")
    val sourceDir = rootProject.file("native/privacy-ring/src")
    inputs.file(manifest)
    inputs.dir(sourceDir)
    inputs.file(rootProject.file("rust-toolchain.toml"))
    outputs.dir(privacyJniLibs)
    workingDir = rootProject.file("native/privacy-ring")
    doFirst { privacyJniLibs.get().asFile.mkdirs() }
    commandLine(
        "bash", "-lc",
        "if ! cargo ndk --version >/dev/null 2>&1; then cargo install cargo-ndk --locked; fi && " +
            "cargo ndk -t arm64-v8a -t x86_64 -o '${privacyJniLibs.get().asFile.absolutePath}' " +
            "build --release --manifest-path '${manifest.absolutePath}'"
    )
    environment("CARGO_TARGET_DIR", layout.buildDirectory.dir("privacy-cargo-target").get().asFile.absolutePath)
}

tasks.named("preBuild").configure { dependsOn(buildPrivacyRingNative) }

dependencies {
    implementation(project(":core:crypto"))
    api(project(":core:solana"))
    testImplementation(libs.junit)
    testImplementation(libs.gson)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
