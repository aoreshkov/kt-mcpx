package app.oreshkov.ktmcpx

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SubscriptionsTest {
    private fun server(
        capabilities: ServerCapabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = true),
            prompts = ServerCapabilities.Prompts(listChanged = true),
            resources = ServerCapabilities.Resources(listChanged = true, subscribe = true),
        ),
    ): Server = testServer(capabilities) {
        for (uri in listOf("test://a", "test://b")) {
            addResource(uri, uri, "A resource.", "text/plain") { ReadResourceResult(listOf(TextResourceContents("x", it.params.uri))) }
        }
    }

    /** One open stream: its events, and the reply [ModernBridge.handle] eventually returns. */
    private class Listening(val events: Channel<JsonObject>, val reply: Deferred<ModernReply>) {
        suspend fun next(): JsonObject = withTimeout(5.seconds) { events.receive() }

        suspend fun none(): JsonObject? = withTimeoutOrNull(300.milliseconds) { events.receive() }
    }

    private fun CoroutineScope.listen(bridge: ModernBridge, filter: JsonObject, id: JsonElement = JsonPrimitive("sub-1")): Listening {
        val events = Channel<JsonObject>(Channel.UNLIMITED)
        val reply = async { bridge.handle(body("subscriptions/listen", buildJsonObject { put("notifications", filter) }, id = id), null) { events.send(it) } }
        return Listening(events, reply)
    }

    @Test
    fun `the acknowledgement comes first, then only the subscribed list changes`() = runBlocking {
        val server = server()
        val bridge = ModernBridge(server)
        val stream = listen(bridge, buildJsonObject { put("toolsListChanged", true) })

        val ack = stream.next()
        assertEquals("notifications/subscriptions/acknowledged", ack.string("method"))
        assertEquals(buildJsonObject { put("toolsListChanged", true) }, ack.params["notifications"])
        assertEquals(JsonPrimitive("sub-1"), ack.subscriptionId)

        server.addPrompt("p", "A prompt.") { GetPromptResult(listOf(PromptMessage(Role.User, TextContent("p")))) }
        server.addTool("new_tool", "A new tool.") { CallToolResult(content = listOf(TextContent("new"))) }

        val change = stream.next()
        assertEquals("notifications/tools/list_changed", change.string("method"))
        assertEquals(JsonPrimitive("sub-1"), change.subscriptionId)
        assertNull(stream.none(), "the prompt change is not on a tools-only stream")
        stream.reply.cancel()
    }

    @Test
    fun `the acknowledgement leaves out what the server cannot send`() = runBlocking {
        val bridge = ModernBridge(server(ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true), resources = ServerCapabilities.Resources())))
        val stream = listen(bridge, buildJsonObject {
            put("toolsListChanged", true)
            put("promptsListChanged", true)
            put("resourcesListChanged", true)
            put("resourceSubscriptions", buildJsonArray { add(JsonPrimitive("test://a")) })
        })

        assertEquals(buildJsonObject { put("toolsListChanged", true) }, stream.next().params["notifications"])
        stream.reply.cancel()
    }

    @Test
    fun `resource updates reach only the streams subscribed to that URI`() = runBlocking {
        val server = server()
        val bridge = ModernBridge(server)
        val stream = listen(bridge, buildJsonObject { put("resourceSubscriptions", buildJsonArray { add(JsonPrimitive("test://a")) }) })
        assertEquals(JsonArray(listOf(JsonPrimitive("test://a"))), stream.next().params["notifications"]!!.jsonObject["resourceSubscriptions"])

        // How an adopter notifies today: one notification per SDK session.
        for (uri in listOf("test://b", "test://a")) {
            server.sessions.keys.forEach { server.sendResourceUpdated(it, ResourceUpdatedNotification(ResourceUpdatedNotificationParams(uri))) }
        }

        val update = stream.next()
        assertEquals("notifications/resources/updated", update.string("method"))
        assertEquals("test://a", update.params.string("uri"))
        assertNull(stream.none())
        stream.reply.cancel()
    }

    @Test
    fun `the listener session lives only while a stream is open`() = runBlocking {
        val server = server()
        val bridge = ModernBridge(server)
        val first = listen(bridge, buildJsonObject { put("toolsListChanged", true) }, JsonPrimitive(1))
        val second = listen(bridge, buildJsonObject { put("promptsListChanged", true) }, JsonPrimitive(2))
        first.next()
        second.next()
        assertEquals(1, server.sessions.size, "one listener session serves every stream")

        first.reply.cancel()
        second.reply.cancel()
        first.reply.join()
        second.reply.join()

        withTimeout(5.seconds) { while (server.sessions.isNotEmpty()) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun `close ends every stream with its result, and refuses new ones the same way`() = runBlocking {
        val server = server()
        val bridge = ModernBridge(server)
        val stream = listen(bridge, buildJsonObject { put("toolsListChanged", true) }, JsonPrimitive(9))
        stream.next()

        bridge.close()

        val result = withTimeout(5.seconds) { stream.reply.await() }.response.result
        assertEquals("complete", result.string("resultType"))
        assertEquals(JsonPrimitive(9), result["_meta"]!!.jsonObject[SUBSCRIPTION_ID])
        assertEquals("test-server", result["_meta"]!!.jsonObject[ModernMetaKeys.SERVER_INFO]!!.jsonObject.string("name"))
        assertTrue(server.sessions.isEmpty())

        val late = listen(bridge, buildJsonObject { put("toolsListChanged", true) }, JsonPrimitive(10))
        assertEquals("complete", withTimeout(5.seconds) { late.reply.await() }.response.result.string("resultType"))
        assertNull(late.none(), "no acknowledgement after close")
    }

    @Test
    fun `a malformed filter is -32602`() = runBlocking {
        val bridge = ModernBridge(server())
        for (filter in listOf<JsonElement?>(null, JsonPrimitive(true), buildJsonObject { put("toolsListChanged", "yes") }, buildJsonObject { put("resourceSubscriptions", "test://a") })) {
            val params = buildJsonObject { if (filter != null) put("notifications", filter) }
            assertEquals(ModernErrorCodes.INVALID_PARAMS, bridge.handle(body("subscriptions/listen", params), null).response.code, filter.toString())
        }
    }

    private val JsonObject.params: JsonObject get() = this["params"]!!.jsonObject

    private val JsonObject.subscriptionId: JsonElement? get() = params["_meta"]?.jsonObject?.get(SUBSCRIPTION_ID)

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    private companion object {
        const val SUBSCRIPTION_ID = "io.modelcontextprotocol/subscriptionId"
    }
}
