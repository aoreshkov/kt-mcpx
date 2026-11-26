package app.oreshkov.ktmcpx

import app.oreshkov.ktmcpx.internal.InMemoryServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession

/**
 * `true` while this session is one kt-mcpx created: one per modern request while it runs, and the
 * bridge's `subscriptions/listen` listener. Code that broadcasts to every session in `server.sessions`
 * should skip these: a modern client gets notifications only for its own request or on a listen stream,
 * and the bridge drops anything else they receive.
 *
 * `false` once the session has closed, as for any closed session. A broadcast loop must catch a failure
 * for each session on its own (rethrowing `CancellationException`), since a session can close between
 * the snapshot of `server.sessions` and the send.
 */
public val ServerSession.isKtMcpxSession: Boolean
    get() = transport is InMemoryServerTransport
