pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LockWallet"

// Spec §5 — only the modules the MVP architecture names. Future modules (:custody:seedvault,
// :core:airgap, transports, connect) must NOT be created ahead of their phase (spec §5, v2.2 §12).
include(":core:crypto")
include(":core:solana")
include(":custody:local")
include(":custody:seedvault") // Phase G (spec §23.3)
include(":data:rpc")
include(":data:price")
include(":app")
