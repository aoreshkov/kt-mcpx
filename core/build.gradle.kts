// kt-mcpx-core: the transport-neutral bridge. It validates a modern request, answers
// server/discover, runs everything else through a short-lived SDK session, and wraps the result in
// the 2026-07-28 envelope.

plugins {
    id("kt-mcpx-library")
}

description = "Serves MCP 2026-07-28 requests with an existing Kotlin SDK Server, transport-neutral."

dependencies {
    api(libs.mcp.server)
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
