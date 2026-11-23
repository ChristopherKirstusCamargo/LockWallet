import java.util.Properties

// :app — Compose UI, flows, state, explanatory text, confirmation (spec §5).
// Must never handle raw secrets nor build signable bytes directly; it cannot depend on :core:crypto.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lockwallet.app"
    compileSdk = 36 // Gate 0, see docs/verification-log.md (G0-*)

    defaultConfig {
        // Application id fixed since 2.0.0 (Mainnet); never changes, so updates install in place. Spec §2.3 still holds:
        // the network is never switched by a hidden flag / remote config / dev build.
        applicationId = "com.lockwallet.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 15
        versionName = "Final"
    }

    // Release signing is opt-in: pass -Plockwallet.signingProps=/path/outside/repo/signing.properties.
    // The keystore and its passwords never live in the repository (see docs/RELEASE-CHECKLIST.md).
    val signingProps = (project.findProperty("lockwallet.signingProps") as String?)?.let { file(it) }?.takeIf { it.isFile }
    if (signingProps != null) {
        val p = Properties().apply { signingProps.inputStream().use { load(it) } }
        signingConfigs {
            create("lockRelease") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
                storeType = p.getProperty("storeType", "PKCS12")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (signingProps != null) signingConfig = signingConfigs.getByName("lockRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without -Plockwallet.signingProps the release artifact is UNSIGNED (CI inspection only).
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    // Do not embed the Google-encrypted dependency metadata blob (supply-chain transparency, ADR 0014).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        // Security-relevant checks escalated to errors.
        error += setOf("AllowBackup", "DataExtractionRules", "InsecureBaseConfiguration", "UnprotectedSMSBroadcastReceiver",
            "ExportedService", "ExportedReceiver", "ExportedContentProvider", "SetWorldReadable", "SetWorldWritable",
            "TrustAllX509TrustManager", "BadHostnameVerifier", "LogConditional")
        disable += setOf("OldTargetApi", "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Optional: LW_SHOTS=/dir writes RenderSmokeTest screenshots for manual design review (off in CI).
        unitTests.all {
            it.systemProperty("lockwallet.shots", System.getenv("LW_SHOTS") ?: "")
            it.systemProperty("lockwallet.iconPreview", System.getenv("LW_ICON_PREVIEW") ?: "")
            it.systemProperty("lockwallet.resDir", project.file("src/main/res").absolutePath)
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.kotlin_module")
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":custody:local"))
    implementation(project(":custody:seedvault"))
    implementation(project(":data:rpc"))
    implementation(project(":data:price"))
    implementation(project(":core:solana"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.zxing.core)
    implementation(libs.mwa.walletlib)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
