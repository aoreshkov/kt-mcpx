// kt-mcpx-testing: a raw 2026-07-28 client over Ktor's HttpClient, the contract kit adopters run
// against their own endpoints, and captured client traffic as fixtures.

plugins {
    id("kt-mcpx-library")
}

description = "Test kit for kt-mcpx: a raw MCP 2026-07-28 client, a contract check and captured client traffic."

dependencies {
    api(project(":kt-mcpx-core"))
    api(libs.ktor.client.core)
    implementation(libs.kotlinx.coroutines.core)
}
