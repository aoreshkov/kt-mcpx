# kt-mcpx

A dual-era bridge for MCP servers built on the Kotlin SDK.

kt-mcpx lets a server built on the official Kotlin SDK answer MCP 2026-07-28 requests while its legacy
traffic stays on the SDK. Tools, resources and prompts keep their SDK registration code. kt-mcpx is a
library that kt-mcp uses; it is not kt-mcp, and it is not a port of any other project named "mcpx".

**Status:** 0.2 in development. Tools, resources, resource templates, prompts, completions, MRTR
(`input_required`) and `subscriptions/listen` work over Streamable HTTP and stdio, and every required
2026-07-28 conformance scenario passes. The Tasks extension is planned for 0.3.

kt-mcpx is meant to be deleted: once the Kotlin SDK serves both eras on one endpoint itself, servers
drop the dependency, the `dualEra` line and the stdio call, and this repository is archived. Issues
outside that scope (a dual-era bridge for SDK users) are closed with a pointer to the SDK.

## Installation

From Maven Central, with the same Kotlin SDK minor version (see [Compatibility](#compatibility)):

```kotlin
dependencies {
    implementation("app.oreshkov.ktmcpx:kt-mcpx-ktor:0.1.0")  // Streamable HTTP (brings kt-mcpx-core)
    implementation("app.oreshkov.ktmcpx:kt-mcpx-stdio:0.1.0") // stdio (brings kt-mcpx-core)
    testImplementation("app.oreshkov.ktmcpx:kt-mcpx-testing:0.1.0")
}
```

## Usage

```kotlin
val bridge = ModernBridge(server) {
    cache(CacheableMethod.ToolsList, ttl = 24.hours, scope = CacheScope.Private)
}
mcpStreamableHttp(path = "/mcp") { server } // legacy traffic, as before
dualEra("/mcp") { bridge }                 // 2026-07-28 POSTs on the same endpoint
```

`dualEra` matches only modern POSTs (an `Mcp-Method` header, or a 2026-07-28 `MCP-Protocol-Version`).
Everything else on the path, including a legacy `initialize`, GET streams and DELETE, still reaches the
SDK route. Each modern request runs through a short-lived SDK session of your `Server`, so
per-session handlers work. Nothing outlives the request.

On stdio, `serveDualEraStdio` replaces `StdioServerTransport`:

```kotlin
serveDualEraStdio(server, bridge) { session ->
    // optional: handlers you install on the stdio session, as you did after createSession
}
```

A modern line (`server/discover`, or a request whose `_meta` carries the 2026-07-28 protocol version)
goes to the bridge. Every other line goes to one long-lived SDK session, as before.
`notifications/cancelled` stops a modern request in flight. Nothing else may write to stdout. Set
`KotlinLoggingConfiguration.logStartupMessage = false`, or the SDK's logging prints a banner there.

## How it answers

- `server/discover` is built from the server's own `initialize` answer. It advertises only what kt-mcpx
  serves, so the legacy `tasks` capability is left out.
- Every result gets `resultType`, server info in `_meta`, and `ttlMs`/`cacheScope` on the cacheable
  methods (default: 60 s, private). `Tool.execution` is removed from `tools/list`.
- A resource that does not exist is -32602 with the URI in `data` (the SDK's -32002 is retired).
- Request errors follow the 2026-07-28 binding: `_meta`, header and version problems are 400 (-32602,
  -32020, -32022), and unknown or removed methods are 404 (-32601).
- Custom headers (SEP-2243): put `"x-mcp-header": "Region"` on a string, integer or boolean property
  of an SDK tool schema, and a `tools/call` over HTTP must carry a matching `Mcp-Param-Region` header,
  Base64-decoded when wrapped, or it gets -32020. A tool whose annotations are invalid is not checked,
  because clients drop it; `ModernParamHeaders.problems` and the contract kit say why.
- A handler that asks the client for something it did not declare (sampling, roots, elicitation)
  produces -32021 with `requiredCapabilities`.
- A handler that asks for something the client did declare, during `tools/call`, `prompts/get` or
  `resources/read`, produces `resultType: "input_required"` (MRTR, see below). During any other method
  the question is refused to the handler with -32601.
- Progress for the request's token, and log messages at or above the request's `_meta` log level, go
  on the response stream. On HTTP the answer switches to SSE when the first one arrives. On stdio they
  are written as lines before the response. Other notifications are dropped.
- `subscriptions/listen` gets `notifications/subscriptions/acknowledged`, then each `list_changed` and
  `resources/updated` its filter asks for, tagged with the subscription id. One long-lived SDK session
  of your `Server`, open while any stream is, receives them, so the SDK's own broadcasts and
  notifications you send to every entry of `server.sessions` both arrive. On HTTP the stream is SSE.
  Call `bridge.close()` at shutdown to end streams with their final result.

### Asking the client for input (MRTR)

Handlers keep calling `createElicitation`, `createMessage` and `listRoots`. A 2026-07-28 client has no
open connection to answer on, so the bridge ends the request with `input_required` and one input
request per question, and cancels the handler. When the client retries with `inputResponses`, the
handler runs again in a fresh session and each question is answered at once. Answers from earlier
rounds travel in a signed `requestState`, so a handler that asks several questions in turn gets all of
them on the round that completes it.

- **A handler must be idempotent up to the point where it asks**: everything before the question
  runs again on every round.
- Questions asked concurrently (in `async` blocks) within `inputRequestWindow` share one result.
- Keys stay the same across rounds: the method and a digest of the question. To choose one, put
  `ModernInputRequests.KEY_META` in the request's `_meta`.
- `requestState` is signed with a random key per bridge. Behind a load balancer, give every instance
  the same key with `requestStateSecret(bytes)`.

```kotlin
val bridge = ModernBridge(server) {
    requestStateSecret(secretFromYourConfig) // at least 32 bytes; only needed with several instances
}
```

## Compatibility

| kt-mcpx | Kotlin SDK | MCP revisions |
|---|---|---|
| 0.1 | 0.16 | 2026-07-28 through the bridge; legacy revisions through the SDK |

Each kt-mcpx minor version is built against exactly one SDK minor version: bump both together. The
artifacts are JVM 17 bytecode and need Kotlin 2.2 or newer.

## Conformance

CI runs the official MCP conformance suite against `fixture-server`, which serves both eras on one
endpoint. It runs 2026-07-28 against the bridge and 2025-11-25 against the SDK route. Every required
2026-07-28 scenario passes. The unscored ones that fail (the Tasks extension, and a pending scenario
the SDK's tool schema cannot express) are listed in `.github/conformance/` with their reasons.

## License

[Apache License 2.0](LICENSE).
