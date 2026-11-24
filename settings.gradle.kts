rootProject.name = "kt-mcpx"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Toolchain auto-provisioning: downloads the JDK named by `jdk` in libs.versions.toml when no
    // matching one is installed. Settings plugins are applied before the version catalog exists,
    // so this is the one version that cannot live in the catalog.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// Published modules. Project names are the artifact ids; the directories stay short.
for (module in listOf("core", "ktor", "stdio", "testing")) {
    include(":kt-mcpx-$module")
    project(":kt-mcpx-$module").projectDir = file(module)
}

// The conformance fixture server: SDK tools served on both eras. Never published.
include(":fixture-server")
