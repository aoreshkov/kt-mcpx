// The conformance fixture server: the tools the official suite calls (`test_simple_text`,
// `test_tool_with_progress`, …), registered with plain SDK APIs and served on one endpoint for both
// eras: the SDK route for legacy traffic and `dualEra` for 2026-07-28. Never published.
//
// ./gradlew :fixture-server:installDist
// fixture-server/build/install/fixture-server/bin/fixture-server [port, default 3000]
// fixture-server/build/install/fixture-server/bin/fixture-server --stdio [trace file]

plugins {
    id("kt-mcpx-jvm")
    application
}

dependencies {
    implementation(project(":kt-mcpx-ktor"))
    implementation(project(":kt-mcpx-stdio"))
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlin.logging)
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass = "app.oreshkov.ktmcpx.fixture.FixtureServerKt"
}
