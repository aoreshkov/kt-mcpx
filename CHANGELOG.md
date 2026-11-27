# Changelog

All notable changes to kt-mcpx are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0, a minor version can change the
API incompatibly, and each minor version is built against exactly one Kotlin SDK minor version (see
[Compatibility](README.md#compatibility)).

## [Unreleased]

## [0.2.0] - 2026-11-27

Built against Kotlin SDK 0.16, like 0.1.

### Added

- `kt-mcpx-core`: MRTR (SEP-2322). On `tools/call`, `prompts/get` and `resources/read`, a handler that
  asks the client for something (`createElicitation`, `createMessage`, `listRoots`) now gets a
  `resultType: "input_required"` answer instead of an error. On the retry the bridge runs the handler
  again and answers each question from `inputResponses`; answers from earlier rounds come back in an
  HMAC-signed `requestState`, bound to the request and valid for `requestStateTtl` (default 30
  minutes). Questions asked concurrently within `inputRequestWindow` (default 50 ms) share one result.
  Input request keys stay stable across rounds, and a handler can name one with
  `_meta[ModernInputRequests.KEY_META]`. A URL elicitation loses its `elicitationId`, and the SDK's
  `UrlElicitationRequiredException` (-32042) becomes an `input_required` result too. Handlers must be
  idempotent up to the point where they ask. Set `requestStateSecret` on every instance behind a load
  balancer.
- `kt-mcpx-core`: `subscriptions/listen` (SEP-2575). The SDK's `list_changed` and `resources/updated`
  notifications reach every listen stream whose filter asks for them, tagged with the subscription
  id, after a `notifications/subscriptions/acknowledged` that names what the server can honour. One
  long-lived SDK session per bridge, open only while a stream is, receives them. `server/discover`
  advertises `listChanged` and `subscribe` again. `ModernBridge.close()` ends open streams with their
  final result.
- `kt-mcpx-ktor`: `subscriptions/listen` is served as an SSE stream; a request for it without
  `text/event-stream` in `Accept` gets 406.
- `kt-mcpx-core`: `ServerSession.isKtMcpxSession`, so code that broadcasts to every entry of
  `server.sessions` can skip the bridge's sessions.
- `kt-mcpx-core`: `ModernBridge.Config.maxConcurrentRequests` (default 1000) and `maxListenStreams`
  (default 100). A request over its limit gets -32603 at once: HTTP 503 with `Retry-After: 1` on Ktor,
  the same error line on stdio.
- `kt-mcpx-stdio`: `serveDualEraStdio(maxLineLength = …)` (default 4 MiB). A longer line gets -32600
  with a `null` id and is skipped without being held in memory.
- `kt-mcpx-testing`: the contract kit runs on stdio. `withModernStdioClient(server) { client -> }` runs
  `serveDualEraStdio` over in-memory pipes; `ModernStdioTestTransport` reaches any stdio server,
  including a real process. `ModernTestClient` now sits on a `ModernTestTransport`
  (`ModernHttpTestTransport` or the stdio one); its HTTP constructor is unchanged. On stdio,
  `ModernContract` skips its header and status checks.
- `kt-mcpx-testing`: two contract checks: no answer carries a log message its request did not ask for,
  and no answer carries a `list_changed` or `resources/updated` notification.

### Changed

- `kt-mcpx-stdio`: `serveDualEraStdio` takes `maxLineLength` before `configureLegacySession`. A call
  that passes `configureLegacySession` as a trailing lambda or by name is unchanged; one that passes it
  as the fifth positional argument must name it.

### Fixed

- `kt-mcpx-core`: a log message that an adopter broadcast to every session in `server.sessions` reached
  every modern request's stream that asked for logs. A request's stream now carries only the log
  messages its own handler sent, as 2026-07-28 requires, and `subscriptions/listen` streams carry none.
- `kt-mcpx-stdio`: the legacy SDK session no longer writes `list_changed` broadcasts to stdout before a
  legacy client has sent `initialize`, so a modern-only client never sees notifications it did not
  subscribe to.
- `kt-mcpx-core`: a modern call that changes the tool, prompt or resource list no longer leaves a
  "Not connected" uncaught exception in the log. SDK 0.16.0 can fail to deliver a `list_changed` to a
  session that closes while it is delivering one, so the call's session now waits for its own
  `list_changed` (up to 500 ms, usually far less) before closing. Calls that change no list are not
  delayed.

## [0.1.0] - 2026-11-26

First release, built against Kotlin SDK 0.16.

### Added

- `kt-mcpx-core`: `ModernBridge` serves MCP 2026-07-28 requests with an existing SDK `Server`.
  `server/discover` is built from the server's own `initialize` answer; every other request runs
  through a short-lived SDK session, so per-session handlers work. Tools, resources, resource
  templates, prompts and completions are served, with the 2026-07-28 envelope (`resultType`, server
  info in `_meta`, `ttlMs`/`cacheScope` on cacheable methods) and its request errors.
- `kt-mcpx-core`: progress for the request's token and log messages at or above the request's log
  level are relayed on the response stream. A handler that asks the client for a capability it did
  not declare gets -32021 with `requiredCapabilities`.
- `kt-mcpx-core`: SEP-2243 custom headers. `x-mcp-header` annotations on SDK tool schemas are checked
  against `Mcp-Param-*` headers on `tools/call` (-32020), and `ModernParamHeaders` reports invalid
  annotations and builds a client's headers.
- `kt-mcpx-ktor`: `Route.dualEra(path) { bridge }` takes modern POSTs, selected from headers alone,
  next to the SDK's own Streamable HTTP route on the same path, with lazy SSE.
- `kt-mcpx-stdio`: `serveDualEraStdio(server, bridge)` serves both eras on one stdin/stdout pair in
  place of `StdioServerTransport`; `notifications/cancelled` stops a modern request in flight.
- `kt-mcpx-testing`: `ModernTestClient`, the `ModernContract` kit to run against your own endpoints,
  and `CapturedTraffic`, scrubbed Claude Code 2.1.331 requests for compatibility tests.

[Unreleased]: https://github.com/aoreshkov/kt-mcpx/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/aoreshkov/kt-mcpx/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/aoreshkov/kt-mcpx/releases/tag/v0.1.0
