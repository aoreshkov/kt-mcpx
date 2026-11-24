// kt-mcpx-ktor: `dualEra(path) { bridge }`, the modern POST route next to the SDK's own.

plugins {
    id("kt-mcpx-library")
}

description = "Ktor routing for kt-mcpx: MCP 2026-07-28 POSTs and the SDK's legacy route on one endpoint."

dependencies {
    api(project(":kt-mcpx-core"))
    api(libs.ktor.server.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":kt-mcpx-testing"))
    testImplementation(project(":fixture-server"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
}
