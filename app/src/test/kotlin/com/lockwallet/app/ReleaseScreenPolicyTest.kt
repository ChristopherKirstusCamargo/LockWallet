package com.lockwallet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FLAG_SECURE policy: screen capture can only ever be allowed in the isolated inspection build (debug variant,
 * package com.lockwallet.app.test, both Gradle properties set). The official release never allows it, whatever
 * properties are passed, and no runtime toggle exists.
 */
class ReleaseScreenPolicyTest {
    private val gradle = File("build.gradle.kts").takeIf { it.isFile } ?: File("app/build.gradle.kts")

    @Test fun policyNeedsAllThreeConditions() {
        val id = MainActivity.INSPECTION_APPLICATION_ID
        assertTrue(MainActivity.screenCaptureAllowed(true, true, id))
        assertFalse(MainActivity.screenCaptureAllowed(true, false, id))           // release build
        assertFalse(MainActivity.screenCaptureAllowed(true, true, "com.lockwallet.app")) // production package
        assertFalse(MainActivity.screenCaptureAllowed(false, true, id))
        assertFalse(MainActivity.screenCaptureAllowed(true, false, "com.lockwallet.app"))
    }

    @Test fun regularBuildsUnderTestKeepCaptureBlocked() {
        // CI runs without the inspection properties: the compiled flag must be false here.
        assertFalse(BuildConfig.SCREENSHOT_CAPTURE_ALLOWED)
        assertFalse(MainActivity.screenCaptureAllowed(BuildConfig.SCREENSHOT_CAPTURE_ALLOWED, BuildConfig.DEBUG, BuildConfig.APPLICATION_ID))
    }

    @Test fun releaseVariantHardcodesFalseAndInspectionNeedsTheTestPackage() {
        val s = gradle.readText()
        val release = s.substringAfter("        release {").substringBefore("\n        }")
        assertTrue(release.contains("""buildConfigField("boolean", "SCREENSHOT_CAPTURE_ALLOWED", "false")"""))
        assertEquals(1, Regex("SCREENSHOT_CAPTURE_ALLOWED").findAll(release).count())
        val debug = s.substringAfter("        debug {").substringBefore("\n        }")
        assertTrue(debug.contains("""buildConfigField("boolean", "SCREENSHOT_CAPTURE_ALLOWED", inspectionBuild.toString())"""))
        assertTrue(s.contains("""val inspectionBuild = testBuild && providers.gradleProperty("lockwallet.inspectionBuild").orNull == "true""""))
        assertTrue(s.contains("applicationId = if (testBuild) {\n            \"com.lockwallet.app.test\""))
        // No other place may define the flag (e.g. a product flavor or defaultConfig override).
        assertEquals(2, Regex("""buildConfigField\("boolean", "SCREENSHOT_CAPTURE_ALLOWED"""").findAll(s).count())
    }

    @Test fun noRuntimeSwitchTouchesTheSecureFlag() {
        val src = File(gradle.parentFile, "src/main/kotlin")
        val offenders = src.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { it.contains("clearFlags(WindowManager.LayoutParams.FLAG_SECURE") || it.contains("FLAG_SECURE.inv()") } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
        val main = File(src, "com/lockwallet/app/MainActivity.kt").readText()
        assertEquals(1, Regex("""setFlags\(WindowManager\.LayoutParams\.FLAG_SECURE""").findAll(main).count())
        assertTrue(main.contains("if (!screenCaptureAllowed(BuildConfig.SCREENSHOT_CAPTURE_ALLOWED, BuildConfig.DEBUG, BuildConfig.APPLICATION_ID))"))
    }
}
