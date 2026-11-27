package app.oreshkov.ktmcpx.testing

import app.oreshkov.ktmcpx.ModernBridge
import app.oreshkov.ktmcpx.stdio.serveDualEraStdio
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.nio.channels.Channels
import java.nio.channels.Pipe

/**
 * Runs `serveDualEraStdio(server, bridge)` over in-memory pipes and calls [block] with a
 * [ModernTestClient] on them, the stdio counterpart of a `testApplication` test:
 *
 * ```kotlin
 * withModernStdioClient(server) { client ->
 *     ModernContract.verify(client, ToolProbe("echo")).assertPassed()
 * }
 * ```
 *
 * [configureLegacySession] runs on the stdio legacy session, as it does in `serveDualEraStdio`. When
 * [block] returns or throws, the server's stdin is closed, and the call waits for it to finish. For other
 * client settings, wrap the same transport: `ModernTestClient(client.transport, clientCapabilities = …)`.
 */
public suspend fun <T> withModernStdioClient(
    server: Server,
    bridge: ModernBridge = ModernBridge(server),
    configureLegacySession: suspend (ServerSession) -> Unit = {},
    block: suspend (ModernTestClient) -> T,
): T = coroutineScope {
    val toServer = Pipe.open()
    val fromServer = Pipe.open()
    val clientOut = Channels.newOutputStream(toServer.sink())
    val serverOut = Channels.newOutputStream(fromServer.sink())
    val serving = launch(Dispatchers.IO) {
        serveDualEraStdio(
            server,
            bridge,
            Channels.newInputStream(toServer.source()).asSource().buffered(),
            serverOut.asSink().buffered(),
            configureLegacySession = configureLegacySession,
        )
    }
    val transport = ModernStdioTestTransport(this, Channels.newInputStream(fromServer.source()).asSource().buffered(), clientOut.asSink().buffered())
    try {
        block(ModernTestClient(transport))
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            clientOut.close()
            serving.join()
            serverOut.close()
        }
    }
}
