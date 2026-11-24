// Every module: Kotlin/JVM built on the catalog's JDK toolchain, emitting JVM 17 bytecode readable by
// any consumer on Kotlin 2.2 or newer (the SDK's own floor), warnings as errors, JUnit Platform tests.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
}

val libs = the<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(libs.findVersion("jdk").get().requiredVersion.toInt())
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // Compile against the JDK 17 API, not just its bytecode level, so nothing newer slips in.
        freeCompilerArgs.add("-Xjdk-release=17")
        languageVersion = KotlinVersion.KOTLIN_2_2
        apiVersion = KotlinVersion.KOTLIN_2_2
        allWarningsAsErrors = true
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    // kotlin-test resolves to kotlin-test-junit5 under useJUnitPlatform(), which also brings the
    // junit-platform-launcher Gradle 9 no longer supplies.
    "testImplementation"(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
