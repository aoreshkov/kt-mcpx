package app.oreshkov.ktmcpx

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class BridgeSessionsTest {
    /** A transport that is not kt-mcpx's, as a legacy client's would be. */
    private class OtherTransport : AbstractTransport() {
        override suspend fun start() = Unit

        override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) = Unit

        override suspend fun close() = invokeOnCloseCallback()
    }

    @Test
    fun `a request's session is a kt-mcpx session while it runs, and leaves when it ends`() = runBlocking {
        val seen = mutableListOf<ServerSession>()
        lateinit var server: Server
        server = testServer {
            addTool("probe", "Records the server's sessions.") {
                seen += server.sessions.values
                CallToolResult(content = emptyList())
            }
        }
        ModernBridge(server).handle(toolCall("probe"), null)

        val session = seen.single()
        assertFalse(session.isKtMcpxSession, "closed")
        assertTrue(server.sessions.isEmpty())
    }

    @Test
    fun `the marker reads true inside the handler`() = runBlocking {
        val marks = mutableListOf<Boolean>()
        lateinit var server: Server
        server = testServer {
            addTool("probe", "Records the marker.") {
                marks += server.sessions.values.map { it.isKtMcpxSession }
                CallToolResult(content = emptyList())
            }
        }
        ModernBridge(server).handle(toolCall("probe"), null)

        assertEquals(listOf(true), marks)
    }

    @Test
    fun `a session over any other transport is not a kt-mcpx session`() = runBlocking {
        val server = testServer()
        val session = server.createSession(OtherTransport())
        try {
            assertFalse(session.isKtMcpxSession)
        } finally {
            session.close()
        }
    }

    @Test
    fun `the listen stream's listener session is a kt-mcpx session`() = runBlocking {
        val server = testServer()
        val bridge = ModernBridge(server)
        val filter = buildJsonObject { put("notifications", buildJsonObject { put("toolsListChanged", true) }) }
        val listening = async { bridge.handle(body("subscriptions/listen", filter), null) }
        withTimeout(5.seconds) { while (server.sessions.isEmpty()) delay(10) }

        assertTrue(server.sessions.values.all { it.isKtMcpxSession })
        bridge.close()
        listening.await()
    }

    @Test
    fun `a broadcast loop that catches per session survives a session that closed after the snapshot`() = runBlocking {
        val snapshot = mutableListOf<ServerSession>()
        lateinit var server: Server
        server = testServer {
            addTool("probe", "Takes a snapshot of the sessions.") {
                snapshot += server.sessions.values
                CallToolResult(content = emptyList())
            }
        }
        ModernBridge(server).handle(toolCall("probe"), null)
        val log = LoggingMessageNotification(LoggingMessageNotificationParams(LoggingLevel.Info, JsonPrimitive("late")))

        assertFailsWith<IllegalStateException> { snapshot.single().sendLoggingMessage(log) }
        var failures = 0
        for (session in snapshot) {
            if (session.isKtMcpxSession) continue
            try {
                session.sendLoggingMessage(log)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failures++
            }
        }
        // The closed session no longer reads as a kt-mcpx session, so the catch is what saves the loop.
        assertEquals(1, failures)
    }
}
