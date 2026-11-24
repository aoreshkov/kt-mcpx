# Changelog

All notable changes to kt-mcpx are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Until 1.0, a minor version can change the
API incompatibly, and each minor version is built against exactly one Kotlin SDK minor version (see
[Compatibility](README.md#compatibility)).

## [Unreleased]

## [0.1.0] - 2026-11-24

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

[Unreleased]: https://github.com/aoreshkov/kt-mcpx/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/aoreshkov/kt-mcpx/releases/tag/v0.1.0
