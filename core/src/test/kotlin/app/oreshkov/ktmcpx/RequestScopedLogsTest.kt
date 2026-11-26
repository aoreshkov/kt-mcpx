package app.oreshkov.ktmcpx

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * 2026-07-28 allows a log message only on the stream of the request that set the log level. An adopter
 * that broadcasts to every session in `server.sessions` reaches the bridge's sessions too; only the log a
 * request's own handler sends may reach that request's stream.
 */
class RequestScopedLogsTest {
    private fun log(text: String) =
        LoggingMessageNotification(LoggingMessageNotificationParams(LoggingLevel.Info, JsonPrimitive(text)))

    /** Sends [text] to every session, skipping none, and survives a session that closes meanwhile. */
    private suspend fun Server.broadcast(text: String) {
        for (session in sessions.values) {
            try {
                session.sendLoggingMessage(log(text))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The session closed after the snapshot.
            }
        }
    }

    /** Waits until [parties] callers have arrived. */
    private class Barrier(private val parties: Int) {
        private val arrived = AtomicInteger()
        private val all = CompletableDeferred<Unit>()

        suspend fun arrive() {
            if (arrived.incrementAndGet() == parties) all.complete(Unit)
            withTimeout(5.seconds) { all.await() }
        }
    }

    private val debug = meta(extra = mapOf(ModernMetaKeys.LOG_LEVEL to JsonPrimitive("debug")))

    /** The log lines on the stream of a `tools/call` of [tool] with [arguments]. */
    private suspend fun ModernBridge.logsOf(tool: String, arguments: Map<String, String> = emptyMap()): List<String> {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val args = buildJsonObject { arguments.forEach { (key, value) -> put(key, value) } }
        handle(toolCall(tool, args, debug), null) { notification ->
            if (notification["method"]!!.jsonPrimitive.content == "notifications/message") {
                lines += notification["params"]!!.jsonObject["data"]!!.jsonPrimitive.content
            }
        }
        return lines.toList()
    }

    @Test
    fun `a broadcast from one request's handler reaches only that request's stream`() = runBlocking {
        val inside = Barrier(2)
        val broadcast = Barrier(2)
        lateinit var server: Server
        server = testServer {
            addTool("tagged", "Logs its tag, then broadcasts it to every session.") { request ->
                val tag = request.arguments!!["tag"]!!.jsonPrimitive.content
                sendLoggingMessage(log("own $tag"))
                inside.arrive()
                server.broadcast("broadcast $tag")
                // Both sessions stay open until both broadcasts are out.
                broadcast.arrive()
                CallToolResult(content = emptyList())
            }
        }
        val bridge = ModernBridge(server)

        coroutineScope {
            val a = async { bridge.logsOf("tagged", mapOf("tag" to "a")) }
            val b = async { bridge.logsOf("tagged", mapOf("tag" to "b")) }
            assertEquals(listOf("own a", "broadcast a"), a.await())
            assertEquals(listOf("own b", "broadcast b"), b.await())
        }
    }

    @Test
    fun `a broadcast from outside any handler reaches no stream`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        lateinit var server: Server
        server = testServer {
            addTool("waiting", "Waits, then logs.") {
                entered.complete(Unit)
                gate.await()
                sendLoggingMessage(log("own"))
                CallToolResult(content = emptyList())
            }
        }
        val bridge = ModernBridge(server)

        coroutineScope {
            val logs = async { bridge.logsOf("waiting") }
            withTimeout(5.seconds) { entered.await() }
            server.broadcast("background")
            gate.complete(Unit)
            assertEquals(listOf("own"), logs.await())
        }
    }

    @Test
    fun `a handler's logs from a child coroutine and another dispatcher still arrive`() = runBlocking {
        val server = testServer {
            addTool("nested", "Logs from a child coroutine, then from Dispatchers.IO.") {
                coroutineScope { launch { sendLoggingMessage(log("child")) } }
                withContext(Dispatchers.IO) { sendLoggingMessage(log("io")) }
                CallToolResult(content = emptyList())
            }
        }

        assertEquals(listOf("child", "io"), ModernBridge(server).logsOf("nested"))
    }
}
