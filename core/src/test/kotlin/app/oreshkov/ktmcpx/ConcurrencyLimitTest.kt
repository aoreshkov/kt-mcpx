package app.oreshkov.ktmcpx

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ConcurrencyLimitTest {
    private val entered = Channel<Unit>(Channel.UNLIMITED)
    private val gate = CompletableDeferred<Unit>()

    private val server = testServer {
        addTool("block", "Waits for the gate.") {
            entered.send(Unit)
            gate.await()
            CallToolResult(content = listOf(TextContent("done")))
        }
        addTool("hang", "Never returns.") {
            entered.send(Unit)
            awaitCancellation()
        }
    }

    private suspend fun awaitEntered() = withTimeout(5.seconds) { entered.receive() }

    private fun ModernReply.Response.assertOverloaded() {
        assertEquals(503, httpStatus)
        assertEquals(ModernErrorCodes.INTERNAL_ERROR, code)
        assertEquals("Too many requests in flight", error["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a request over the limit is refused at once, and the permit comes back`() = runBlocking {
        val bridge = ModernBridge(server) { maxConcurrentRequests = 1 }
        val first = async { bridge.handle(toolCall("block"), null).response }
        awaitEntered()

        bridge.handle(toolCall("echo"), null).response.assertOverloaded()
        bridge.handle(body("server/discover"), null).response.assertOverloaded()

        gate.complete(Unit)
        assertEquals(200, first.await().httpStatus)
        assertEquals(200, bridge.handle(toolCall("echo"), null).response.httpStatus)
    }

    @Test
    fun `a cancelled request gives its permit back`() = runBlocking {
        val bridge = ModernBridge(server) { maxConcurrentRequests = 1 }
        val call = launch { bridge.handle(toolCall("hang"), null) }
        awaitEntered()
        call.cancel()
        call.join()

        assertEquals(200, bridge.handle(toolCall("echo"), null).response.httpStatus)
    }

    @Test
    fun `a timed-out request gives its permit back`() = runBlocking {
        val bridge = ModernBridge(server) {
            maxConcurrentRequests = 1
            requestTimeout = 100.milliseconds
        }

        assertEquals(ModernErrorCodes.INTERNAL_ERROR, bridge.handle(toolCall("hang"), null).response.code)
        assertEquals(200, bridge.handle(toolCall("echo"), null).response.httpStatus)
    }

    @Test
    fun `listen streams have their own limit`() = runBlocking {
        val bridge = ModernBridge(server) {
            maxConcurrentRequests = 1
            maxListenStreams = 1
        }
        val filter = buildJsonObject { put("notifications", buildJsonObject { put("toolsListChanged", true) }) }
        val acknowledged = CompletableDeferred<Unit>()
        val stream = async { bridge.handle(body("subscriptions/listen", filter), null) { acknowledged.complete(Unit) } }
        withTimeout(5.seconds) { acknowledged.await() }

        bridge.handle(body("subscriptions/listen", filter), null).response.assertOverloaded()
        assertEquals(200, bridge.handle(toolCall("echo"), null).response.httpStatus, "the open stream takes no request permit")

        bridge.close()
        stream.await()
    }

    @Test
    fun `the limits must be positive`() {
        assertFailsWith<IllegalArgumentException> { ModernBridge(server) { maxConcurrentRequests = 0 } }
        assertFailsWith<IllegalArgumentException> { ModernBridge(server) { maxListenStreams = 0 } }
    }
}
