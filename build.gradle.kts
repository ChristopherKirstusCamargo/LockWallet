// LOCK WALLET — root build. Holds architecture guards that run in CI (`./gradlew ciCheck`).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// ---------------------------------------------------------------------------------------------
// Guard 1 — module boundaries (spec §5). Allowed *production* project dependencies per module.
// :app must never see :core:crypto (raw secret handling) — it reaches custody only via :custody:local.
// ---------------------------------------------------------------------------------------------
val allowedProjectDeps = mapOf(
    ":core:crypto" to emptySet(),
    ":core:solana" to emptySet(),
    ":custody:local" to setOf(":core:crypto", ":core:solana"),
    // Seed Vault provider: public-key signature verification only (no secret ever crosses this module).
    ":custody:seedvault" to setOf(":custody:local", ":core:crypto", ":core:solana"),
    ":data:rpc" to setOf(":core:solana"),
    ":data:price" to emptySet(), // public market price only (ADR 0017)
    ":app" to setOf(":custody:local", ":custody:seedvault", ":data:rpc", ":data:price", ":core:solana"),
)
val productionConfigs = setOf("api", "implementation", "compileOnly", "runtimeOnly")

val verifyModuleBoundaries by tasks.registering {
    group = "verification"
    description = "Fails if a module declares a production dependency outside the spec §5 matrix."
    val declared: Map<String, Set<String>> = subprojects.filter { it.buildFile.exists() }.associate { p ->
        p.path to p.configurations.filter { it.name in productionConfigs }
            .flatMap { c -> c.dependencies.withType(ProjectDependency::class.java).map { it.path } }
            .toSet()
    }
    doLast {
        val problems = mutableListOf<String>()
        declared.forEach { (module, deps) ->
            val allowed = allowedProjectDeps[module]
            if (allowed == null) problems += "$module is not in the approved module list (spec §5)"
            else (deps - allowed).forEach { problems += "$module -> $it is not allowed (spec §5)" }
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
        logger.lifecycle("Module boundaries OK: $declared")
    }
}

// ---------------------------------------------------------------------------------------------
// Guard 2 — forbidden APIs in production sources (spec §2.3, §5, §8; v2.1 §2.3).
// ---------------------------------------------------------------------------------------------
data class Rule(val id: String, val regex: Regex, val why: String)

val everywhere = listOf(
    Rule("LOG", Regex("""\bandroid\.util\.Log\b|\bLog\.[vdiwe]\(|\bTimber\b"""), "no logging (spec §2.3)"),
    Rule("PRINT", Regex("""\bprintln\(|\bprint\(|System\.(out|err)\b|printStackTrace\("""), "no stdout/stack traces"),
    Rule("PROVIDER", Regex("""Security\.(addProvider|insertProviderAt)"""), "no global JCA provider registration (spec §6.4)"),
    // 2.0.0 (ADR 0023): Mainnet only. Test clusters, faucets and airdrops must never come back into the app.
    Rule("TEST_CLUSTER", Regex("""(?i)api\.(devnet|testnet)\.solana\.com|requestAirdrop|faucet\.solana"""), "Mainnet-only app: no test-cluster endpoint/faucet (ADR 0023)"),
)
val pureCore = listOf(
    Rule("ANDROID", Regex("""^\s*import\s+android(x)?\."""), "core modules are pure JVM (spec §5)"),
    Rule("NET", Regex("""^\s*import\s+(java\.net|javax\.net|okhttp3|io\.ktor)\."""), "no network in core (spec §5)"),
)
val noFloat = Rule("FLOAT", Regex("""\b(Double|Float)\b|\.to(Double|Float)\("""), "no Float/Double for amounts (spec §2.3)")

val forbiddenRules = mapOf(
    "core/crypto" to everywhere + pureCore,
    "core/solana" to everywhere + pureCore + noFloat,
    "custody/local" to everywhere,
    "custody/seedvault" to everywhere,
    "data/rpc" to everywhere + noFloat,
    "data/price" to everywhere + noFloat + pureCore.filter { it.id == "ANDROID" },
    "app" to everywhere,
)

val verifyForbiddenApis by tasks.registering {
    group = "verification"
    description = "Scans production sources for APIs the spec forbids."
    val root = rootDir
    doLast {
        val problems = mutableListOf<String>()
        forbiddenRules.forEach { (dir, rules) ->
            val src = File(root, "$dir/src/main")
            if (!src.exists()) return@forEach
            src.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    val code = line.substringBefore("//")
                    rules.filter { it.regex.containsMatchIn(code) }.forEach { r ->
                        problems += "${f.relativeTo(root)}:${i + 1} [${r.id}] ${r.why}"
                    }
                }
            }
        }
        if (problems.isNotEmpty()) throw GradleException("Forbidden API usage:\n" + problems.joinToString("\n"))
        logger.lifecycle("Forbidden API scan OK")
    }
}

// ---------------------------------------------------------------------------------------------
// Guard 3 — the final APK manifest (spec §16, test #15, v2.2 SEC-09): inspect the built artifact.
// ---------------------------------------------------------------------------------------------
val verifyReleaseApk by tasks.registering(Exec::class) {
    group = "verification"
    description = "Inspects the release APK's compiled manifest with aapt2 (backup, extraction, cleartext, exports)."
    dependsOn(":app:assembleRelease")
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: ""
    commandLine(
        "python3", "tools/verify_apk_manifest.py",
        "--aapt2", "$sdk/build-tools/36.0.0/aapt2",
        "--apk", "app/build/outputs/apk/release/app-release-unsigned.apk",
        "--release",
    )
}

tasks.register("ciCheck") {
    group = "verification"
    description = "Everything CI must pass (Phases A–C)."
    dependsOn(
        verifyModuleBoundaries, verifyForbiddenApis,
        ":core:crypto:test", ":core:solana:test", ":data:rpc:test", ":data:price:test",
        ":custody:local:testDebugUnitTest", ":custody:seedvault:testDebugUnitTest", ":app:testDebugUnitTest",
        ":app:assembleDebug", verifyReleaseApk, ":app:lintRelease",
    )
}
