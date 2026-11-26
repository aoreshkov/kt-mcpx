# kt-mcpx

A dual-era bridge for MCP servers built on the Kotlin SDK: it serves MCP 2026-07-28 requests by
translating them in-process into the SDK `Server` an adopter already has, while legacy traffic stays on
the SDK. Designed to be deleted once the SDK serves both eras itself.

Analysis and plan documents live in `docs/`, which is private and git-ignored.

## Layout

- `build-logic/`: convention plugins. `kt-mcpx-jvm` (toolchain from the catalog, JVM 17 bytecode,
  Kotlin language/API 2.2, warnings as errors, JUnit Platform) and `kt-mcpx-library` (adds
  `explicitApi` and KGP `abiValidation` with dumps in `<module>/api/`). Published modules use the
  latter. After an intended public API change, run `./gradlew updateKotlinAbi` and commit the dumps.
- Published modules (Gradle project name = artifact id, directory short):
  - `core/` (`:kt-mcpx-core`): `ModernBridge`. It validates a modern message, answers `server/discover`
    from a synthetic `initialize`, and runs every other request through a short-lived SDK session.
    Internals are in `internal/`. The SDK capability messages it recognises for -32021 are pinned by
    `CapabilityMessagesTest`: an SDK bump that rewords them fails there. SEP-2243 custom headers:
    `ModernParamHeaders` (public: annotation problems, client-side headers and encoding) and
    `internal/ParamHeaders.kt` (the `tools/call` check against `server.tools[name]`'s schema).
    MRTR: `internal/InputRequests.kt` (stable input keys, the HMAC-signed `requestState`) and the `Ask`
    decision in `SyntheticSession`, which collects questions until `inputRequestWindow` passes quietly.
    `subscriptions/listen`: `internal/Subscriptions.kt`, one long-lived listener session per bridge.
    Every bridge session uses `internal/InMemoryServerTransport`, which lets a `notifications/message`
    through only from its own forwarded request's handler (each `SyntheticSession` has a unique forwarded
    id; `SdkContextContractTest` pins the SDK behaviour this needs). `BridgeSessions.kt`'s public
    `isKtMcpxSession` relies on that transport being the bridge's alone. The concurrency caps are
    `Config.maxConcurrentRequests`/`maxListenStreams`, taken in `ModernBridge.handle` after validation.
  - `ktor/` (`:kt-mcpx-ktor`): `Route.dualEra(path) { bridge }`, with the header-only selector that routes
    modern POSTs (`ModernRequestSelector`) and lazy SSE.
  - `stdio/` (`:kt-mcpx-stdio`): `serveDualEraStdio(server, bridge) { legacySession -> }` in place of
    `StdioServerTransport`. Modern lines go to the bridge, everything else to one long-lived SDK session;
    `notifications/cancelled` cancels a modern request in flight. Its tests replay a scrubbed Claude Code
    stdio capture (`src/test/resources/`).
  - `testing/` (`:kt-mcpx-testing`): `ModernTestClient`, the `ModernContract` kit, and `CapturedTraffic`.
    The scrubbed client captures are under `src/main/resources/.../testing/fixtures/`.
- `fixture-server/`: the conformance fixture server (the suite's `test_*` tools, `test_custom_headers`
  with `x-mcp-header` annotations, the `test_input_required_result_*` tools and prompt asking through
  plain SDK calls, the `test_trigger_*_change` tools, prompts, resources, a template and a per-session
  completion handler, registered with SDK APIs). It serves both eras on
  `/mcp`. Never published. `./gradlew :fixture-server:installDist`, then
  `fixture-server/build/install/fixture-server/bin/fixture-server [port]` (default 3000), or
  `... fixture-server --stdio [trace-file]` for both eras on stdio (the trace gets every stdin line).
- `.github/conformance/`: the pinned suite (`@modelcontextprotocol/conformance` alpha) and one baseline per
  revision. Local run, with the fixture server up:
  `npm ci --prefix .github/conformance --ignore-scripts`, then
  `.github/conformance/node_modules/.bin/conformance server --url http://127.0.0.1:3000/mcp --requirements 2026-07-28 --expected-failures .github/conformance/baseline-2026-07-28.yml`
  (and the same for `2025-11-25`). A baseline entry that starts passing fails the run, so remove entries
  as features land.
- Publishing: `kt-mcpx-library` adds a signed Maven publication with sources and KDoc (Dokka) jars.
  `./gradlew publishToMavenLocal` for a local try-out; `./gradlew stage` collects every published module
  in `build/staging-repository/`, signed when `signingInMemoryKey` is set. `RELEASING.md` has the
  release steps; `.github/workflows/release.yml` runs them on a `vX.Y.Z` tag.
- Versions are only in `gradle/libs.versions.toml` and `gradle.properties`.

## Rules

- `docs/` is private and git-ignored: never commit, reference or link it.
- Each library minor version targets exactly one Kotlin SDK minor version (`mcpKotlinSdk`).
- No runtime dependencies beyond what the SDK and Ktor already bring.
- Adopters keep SDK registration APIs: no kt-mcpx type may be needed in tool, resource or prompt code.
- First publish is squashed, needs the user's explicit yes, and gets a leak check (no private paths,
  emails other than the public one, or `docs/` content).
