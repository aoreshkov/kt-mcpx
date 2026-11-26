package app.oreshkov.ktmcpx

import app.oreshkov.ktmcpx.internal.FORWARDED_ID
import app.oreshkov.ktmcpx.internal.ServerRequestDecision
import app.oreshkov.ktmcpx.internal.SyntheticOutcome
import app.oreshkov.ktmcpx.internal.SyntheticSession
import app.oreshkov.ktmcpx.internal.string
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * SDK 0.16.0 clears a closing session's connection before it cancels the session's notification job, so
 * a `list_changed` being delivered while the session closes throws "Not connected" as an uncaught
 * exception. A call that changes a list starts such a delivery on its own session, so the synthetic
 * session waits for it to arrive before closing.
 */
class ListChangedCloseTest {
    /** Handlers run here, so they still run while the tests keep [Dispatchers.Default] busy. */
    private val handlers = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @AfterTest
    fun closeHandlers() = handlers.close()

    private val server = Server(
        serverInfo = Implementation(name = "test-server", version = "1"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
            handlerCoroutineContext = handlers,
        ),
        instructions = "Test instructions.",
    ) {
        addTool("change", "Adds a tool while the notification threads are busy.") {
            // The SDK delivers list_changed on Dispatchers.Default: occupying it makes the delivery late.
            stallDefaultDispatcher()
            addTool("dynamic", "Added by a call.") { CallToolResult(content = listOf(TextContent("dynamic"))) }
            CallToolResult(content = listOf(TextContent("changed")))
        }
        addTool("echo", "Changes nothing.") { CallToolResult(content = listOf(TextContent("echo"))) }
    }

    private suspend fun call(tool: String, notifications: MutableList<String>): SyntheticOutcome =
        SyntheticSession(server, JsonObject(emptyMap()), null).run(
            onNotification = { notifications += it.string("method") ?: "?" },
            onServerRequest = { ServerRequestDecision.Refuse(-32601, "unexpected") },
        ) { _, forward ->
            forward(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", FORWARDED_ID)
                    put("method", "tools/call")
                    put("params", buildJsonObject { put("name", tool) })
                },
            )
        }

    @Test
    fun `a call that changes the tool list receives the list_changed before its session closes`() = runBlocking {
        val notifications = Collections.synchronizedList(mutableListOf<String>())
        assertIs<SyntheticOutcome.Answered>(call("change", notifications))
        assertTrue("notifications/tools/list_changed" in notifications, "got $notifications")
    }

    @Test
    fun `a call that changes nothing closes its session at once`() = runBlocking {
        val notifications = Collections.synchronizedList(mutableListOf<String>())
        call("echo", notifications)
        val took = measureTime { assertIs<SyntheticOutcome.Answered>(call("echo", notifications)) }
        assertTrue(took.inWholeMilliseconds < 300, "an unchanged call took $took")
    }

    private companion object {
        fun stallDefaultDispatcher() {
            repeat(Runtime.getRuntime().availableProcessors().coerceAtLeast(2)) {
                CoroutineScope(Dispatchers.Default).launch { Thread.sleep(200) }
            }
        }
    }
}
