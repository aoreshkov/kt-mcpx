// kt-mcpx-stdio: `serveDualEraStdio(server, bridge)`, both eras on one stdin/stdout pair.
// Modern messages go to the bridge; everything else goes to one long-lived SDK session.

plugins {
    id("kt-mcpx-library")
}

description = "Stdio transport for kt-mcpx: MCP 2026-07-28 and legacy messages on one stdin/stdout pair."

dependencies {
    api(project(":kt-mcpx-core"))
    api(libs.kotlinx.io.core)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":fixture-server"))
    testImplementation(project(":kt-mcpx-testing"))
    testImplementation(libs.mcp.client)
    testImplementation(libs.kotlinx.coroutines.test)
}
