plugins {
    `kotlin-dsl`
}

// Precompiled convention plugins apply other plugins by id WITHOUT a version, so those plugins'
// artifacts must be on this build's classpath.
dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.dokka.gradle.plugin)
}
