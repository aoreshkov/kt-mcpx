package app.oreshkov.ktmcpx.stdio

import app.oreshkov.ktmcpx.ModernBridge
import app.oreshkov.ktmcpx.ModernErrorCodes
import app.oreshkov.ktmcpx.ModernMetaKeys
import app.oreshkov.ktmcpx.ModernProtocol
import app.oreshkov.ktmcpx.fixture.fixtureServer
import app.oreshkov.ktmcpx.isKtMcpxSession
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmptyJsonObject
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.io.readLine
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.channels.Channels
import java.nio.channels.Pipe
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DualEraStdioTest {
    @Test
    fun `legacy and modern clients share one stream`() = serve(fixtureServer()) {
        // A legacy handshake, then a modern request in between legacy ones.
        send(legacy("L1", "initialize", initializeParams()))
        assertEquals("2025-11-25", responseTo("L1").result["protocolVersion"]!!.jsonPrimitive.content)
        send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")

        send(modern("M1", "server/discover"))
        val discover = responseTo("M1").result
        assertEquals(ModernProtocol.SUPPORTED_VERSIONS, discover["supportedVersions"]!!.jsonArray.map { it.jsonPrimitive.content })

        send(legacy("L2", "tools/list"))
        send(modern("M2", "tools/list"))
        val legacyTools = responseTo("L2").result
        val modernTools = responseTo("M2").result
        assertNull(legacyTools["resultType"], "the SDK session answered the legacy request")
        assertEquals("complete", modernTools["resultType"]!!.jsonPrimitive.content)
        assertEquals(names(legacyTools), names(modernTools))

        // Both eras read the same template resource.
        val uri = buildJsonObject { put("uri", "test://template/7/data") }
        send(legacy("L3", "resources/read", uri))
        send(modern("M3", "resources/read", uri))
        assertEquals(contentText(responseTo("L3").result), contentText(responseTo("M3").result))
    }

    @Test
    fun `modern progress lines come before the response`() = serve(fixtureServer()) {
        val params = buildJsonObject { put("name", "test_tool_with_progress") }
        send(modern("P1", "tools/call", params, extraMeta = mapOf("progressToken" to JsonPrimitive("tok"))))
        val (before, response) = collectUntil(JsonPrimitive("P1"))
        val progress = before.filter { it["method"]?.jsonPrimitive?.content == "notifications/progress" }
        assertEquals(listOf(0.0, 50.0, 100.0), progress.map { it["params"]!!.jsonObject["progress"]!!.jsonPrimitive.double })
        assertEquals("Progress tool completed", contentText(response.result))
    }

    @Test
    fun `per-session completions serve both eras`() = serve(fixtureServer()) {
        val params = buildJsonObject {
            put("ref", buildJsonObject {
                put("type", "ref/prompt")
                put("name", "test_prompt_with_arguments")
            })
            put("argument", buildJsonObject {
                put("name", "arg1")
                put("value", "par")
            })
        }
        send(modern("C1", "completion/complete", params))
        assertEquals(listOf("paris", "park", "party"), completionValues(responseTo("C1").result))

        send(legacy("L1", "initialize", initializeParams()))
        responseTo("L1")
        send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        send(legacy("C2", "completion/complete", params))
        assertEquals(listOf("paris", "park", "party"), completionValues(responseTo("C2").result))
    }

    @Test
    fun `a cancelled modern request is stopped and never answered`() {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val server = Server(
            Implementation("hang-server", "1"),
            ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools())),
        ).apply {
            addTool("hang", "Never returns.") {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        serve(server) {
            send(modern(1, "tools/call", buildJsonObject { put("name", "hang") }))
            started.await()
            send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", "notifications/cancelled")
                    put("params", buildJsonObject {
                        put("requestId", 1)
                        put("_meta", meta())
                    })
                }.toString(),
            )
            cancelled.await()
            send(modern(2, "server/discover"))
            val (before, _) = collectUntil(JsonPrimitive(2))
            assertTrue(before.none { it["id"] == JsonPrimitive(1) }, "the cancelled request was answered: $before")
        }
    }

    @Test
    fun `a listen stream and an MRTR round trip share the stream with other requests`() = serve(fixtureServer()) {
        send(modern("S1", "subscriptions/listen", buildJsonObject { put("notifications", buildJsonObject { put("toolsListChanged", true) }) }))
        val ack = next()
        assertEquals("notifications/subscriptions/acknowledged", ack["method"]!!.jsonPrimitive.content)

        send(modern("T1", "tools/call", buildJsonObject { put("name", "test_trigger_tool_change") }))
        val (before, _) = collectUntil(JsonPrimitive("T1"))
        // The broadcast is asynchronous: it may come before or after the tool's response.
        val change = before.firstOrNull { it["method"]?.jsonPrimitive?.content == "notifications/tools/list_changed" } ?: next()
        assertEquals("notifications/tools/list_changed", change["method"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive("S1"), change["params"]!!.jsonObject["_meta"]!!.jsonObject["io.modelcontextprotocol/subscriptionId"])

        val elicitation = mapOf(ModernMetaKeys.CLIENT_CAPABILITIES to buildJsonObject { put("elicitation", buildJsonObject {}) })
        val call = buildJsonObject { put("name", "test_input_required_result_elicitation") }
        send(modern("E1", "tools/call", call, extraMeta = elicitation))
        val first = responseTo("E1").result
        assertEquals("input_required", first["resultType"]!!.jsonPrimitive.content)
        val retry = JsonObject(
            call + mapOf(
                "inputResponses" to buildJsonObject {
                    put("user_name", buildJsonObject {
                        put("action", "accept")
                        put("content", buildJsonObject { put("name", "Ada") })
                    })
                },
                "requestState" to first["requestState"]!!,
            ),
        )
        send(modern("E2", "tools/call", retry, extraMeta = elicitation))
        assertEquals("Hello, Ada!", contentText(responseTo("E2").result))
    }

    @Test
    fun `lines that are not JSON-RPC get an error, blank lines nothing`() = serve(fixtureServer()) {
        send("")
        send("this is not json")
        assertEquals(ModernErrorCodes.PARSE_ERROR, next().errorCode)
        send("[1,2]")
        assertEquals(ModernErrorCodes.INVALID_REQUEST, next().errorCode)
        send("""{"jsonrpc":"2.0","id":5,"something":"else"}""")
        val invalid = next()
        assertEquals(ModernErrorCodes.INVALID_REQUEST, invalid.errorCode)
        assertEquals(JsonPrimitive(5), invalid["id"])
    }

    @Test
    fun `a line over the limit gets -32600 and the stream goes on`() = serve(fixtureServer(), maxLineLength = 1024) {
        send(modern("L1", "tools/call", buildJsonObject { put("name", "x".repeat(200_000)) }))
        val tooLong = next()
        assertEquals(ModernErrorCodes.INVALID_REQUEST, tooLong.errorCode)
        assertEquals(kotlinx.serialization.json.JsonNull, tooLong["id"])
        send(modern("L2", "tools/list"))
        assertTrue("test_simple_text" in names(responseTo("L2").result))
    }

    @Test
    fun `an unsupported modern version is -32022 on stdio too`() = serve(fixtureServer()) {
        send(modern("V1", "tools/list", version = "2099-01-01"))
        assertEquals(ModernErrorCodes.UNSUPPORTED_PROTOCOL_VERSION, responseTo("V1").errorCode)
    }

    @Test
    fun `the legacy session is configured first and closed at EOF, with no synthetic session left`() {
        val server = fixtureServer()
        val configured = CompletableDeferred<ServerSession>()
        serve(server, configure = { configured.complete(it) }) {
            send(modern("M1", "tools/list"))
            responseTo("M1")
            assertEquals(setOf(configured.await().sessionId), server.sessions.keys, "only the legacy session is open")
            assertFalse(configured.await().isKtMcpxSession, "the legacy session is the adopter's")
        }
        assertTrue(server.sessions.isEmpty(), "sessions left after EOF: ${server.sessions.keys}")
    }

    @Test
    fun `the Kotlin SDK client works unchanged over the dual-era stream`() = runBlocking {
        withTimeout(20.seconds) {
            val server = fixtureServer()
            val pipes = Pipes()
            coroutineScope {
                val serving = launch(Dispatchers.IO) {
                    serveDualEraStdio(server, ModernBridge(server), pipes.serverIn, pipes.serverOut)
                }
                val client = Client(Implementation("sdk-client", "1"))
                client.connect(StdioClientTransport(pipes.clientIn, pipes.clientOut))
                val tools = client.listTools().tools.map { it.name }
                assertTrue("test_simple_text" in tools, tools.toString())
                val result = client.callTool("test_simple_text", emptyMap())
                assertEquals("This is a simple text response for testing.", (result.content.single() as TextContent).text)
                client.close()
                pipes.closeClientOut()
                serving.join()
                pipes.closeServerOut()
            }
            assertTrue(server.sessions.isEmpty())
        }
    }

    @Test
    fun `Claude Code's captured stdio session is served on the modern path`() = serve(fixtureServer()) {
        // Claude Code 2.1.331, default negotiation, against fixture-server --stdio (tool-use id scrubbed).
        val lines = javaClass.getResource("/claude-code-2.1.331-stdio.jsonl")!!.readText().lines().filter { it.isNotBlank() }
        for (line in lines) {
            send(line)
            val id = Json.parseToJsonElement(line).jsonObject["id"]!!
            val response = collectUntil(id).second
            assertEquals("complete", response.result["resultType"]?.jsonPrimitive?.content, "not modern: $response")
        }
        assertEquals("This is a simple text response for testing.", contentText(log.last().result))
    }

    @Test
    fun `a modern-only client sees only its responses when a handler broadcasts to every session`() = serve(
        broadcastingServer(),
    ) {
        // No legacy initialize: the legacy session must stay silent, and the request set no log level.
        // The broadcast happens before the handler returns, so anything it wrote would come first.
        send(modern("B1", "tools/call", buildJsonObject { put("name", "broadcast") }))
        val first = next()
        assertEquals(JsonPrimitive("B1"), first["id"], "nothing before the response")
        assertEquals("broadcast", contentText(first.result))
        send(modern("B2", "tools/list"))
        assertEquals(JsonPrimitive("B2"), next()["id"], "nothing between the responses")
    }

    @Test
    fun `a request over the concurrency limit gets -32603 on stdio`() {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val server = Server(Implementation("limited", "1"), ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools()))).apply {
            addTool("block", "Waits for the gate.") {
                entered.complete(Unit)
                gate.await()
                CallToolResult(listOf(TextContent("done")))
            }
        }
        serve(server, bridgeConfig = { maxConcurrentRequests = 1 }) {
            send(modern("C1", "tools/call", buildJsonObject { put("name", "block") }))
            entered.await()
            send(modern("C2", "tools/list"))
            val refused = responseTo("C2")
            assertEquals(ModernErrorCodes.INTERNAL_ERROR, refused.errorCode)
            assertEquals("Too many requests in flight", refused["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
            gate.complete(Unit)
            assertEquals("done", contentText(responseTo("C1").result))
        }
    }

    @Test
    fun `a handler's result reaches a modern client over a server it did not configure for stdio`() = serve(
        Server(Implementation("plain", "1"), ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools()))).apply {
            addTool("hello", "Says hello.") { CallToolResult(listOf(TextContent("hello"))) }
        },
    ) {
        send(modern("H1", "tools/call", buildJsonObject { put("name", "hello") }))
        val result = responseTo("H1").result
        assertEquals("hello", contentText(result))
        assertFalse((result["isError"] as? JsonPrimitive)?.content == "true")
    }
}

/**
 * A server whose `broadcast` tool does what an adopter's log forwarder does: it sends a log message and a
 * resource list change to every session, skipping none.
 */
internal fun broadcastingServer(): Server {
    val server = Server(
        Implementation("broadcasting", "1"),
        ServerOptions(
            ServerCapabilities(
                tools = ServerCapabilities.Tools(),
                resources = ServerCapabilities.Resources(listChanged = true),
                logging = EmptyJsonObject,
            ),
        ),
    )
    server.addTool("broadcast", "Broadcasts a log and a resource list change.") {
        for (session in server.sessions.values) {
            try {
                session.sendLoggingMessage(LoggingMessageNotification(LoggingMessageNotificationParams(LoggingLevel.Info, JsonPrimitive("hello"))))
                session.sendResourceListChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A session that closed after the snapshot.
            }
        }
        CallToolResult(listOf(TextContent("broadcast")))
    }
    return server
}

/** Two in-memory pipes standing in for the stdin and stdout of a server process. */
private class Pipes {
    private val toServer = Pipe.open()
    private val fromServer = Pipe.open()
    private val clientOutStream = Channels.newOutputStream(toServer.sink())
    private val serverOutStream = Channels.newOutputStream(fromServer.sink())

    val clientOut = clientOutStream.asSink().buffered()
    val serverIn = Channels.newInputStream(toServer.source()).asSource().buffered()
    val serverOut = serverOutStream.asSink().buffered()
    val clientIn = Channels.newInputStream(fromServer.source()).asSource().buffered()

    fun closeClientOut() = clientOutStream.close()

    fun closeServerOut() = serverOutStream.close()
}

private class StdioSession(private val pipes: Pipes, private val received: Channel<JsonObject>) {
    /** Everything read so far, for failure messages. */
    val log: MutableList<JsonObject> = java.util.Collections.synchronizedList(mutableListOf())

    suspend fun send(line: String) = withContext(Dispatchers.IO) {
        pipes.clientOut.writeString(line + "\n")
        pipes.clientOut.flush()
    }

    suspend fun next(): JsonObject = received.receive().also { log += it }

    /** Every message up to the response with [id], and that response. */
    suspend fun collectUntil(id: JsonElement): Pair<List<JsonObject>, JsonObject> {
        val before = mutableListOf<JsonObject>()
        while (true) {
            val message = next()
            if (message["id"] == id && "method" !in message) return before to message
            before += message
        }
    }

    suspend fun responseTo(id: String): JsonObject = collectUntil(JsonPrimitive(id)).second
}

/** Runs [serveDualEraStdio] for [server] on pipes, runs [block] against it, then closes stdin and waits. */
private fun serve(
    server: Server,
    configure: suspend (ServerSession) -> Unit = {},
    bridgeConfig: ModernBridge.Config.() -> Unit = {},
    maxLineLength: Long = 4L * 1024 * 1024,
    block: suspend StdioSession.() -> Unit,
) = runBlocking {
    val pipes = Pipes()
    val received = Channel<JsonObject>(Channel.UNLIMITED)
    val session = StdioSession(pipes, received)
    try {
        withTimeout(10.seconds) {
            coroutineScope {
                val serving = launch(Dispatchers.IO) {
                    serveDualEraStdio(
                        server,
                        ModernBridge(server, bridgeConfig),
                        pipes.serverIn,
                        pipes.serverOut,
                        maxLineLength = maxLineLength,
                        configureLegacySession = configure,
                    )
                }
                val reader = readLines(pipes, received)
                session.block()
                pipes.closeClientOut()
                serving.join()
                pipes.closeServerOut()
                reader.join()
            }
        }
    } catch (e: Throwable) {
        pipes.closeClientOut()
        pipes.closeServerOut()
        throw AssertionError("${e.message}; read so far: ${session.log}", e)
    }
}

private fun CoroutineScope.readLines(pipes: Pipes, into: Channel<JsonObject>): Job = launch {
    while (true) {
        val line = runInterruptible(Dispatchers.IO) { pipes.clientIn.readLine() } ?: break
        into.send(Json.parseToJsonElement(line).jsonObject)
    }
    into.close()
}

private fun meta(version: String = ModernProtocol.VERSION, extra: Map<String, JsonElement> = emptyMap()) = buildJsonObject {
    put(ModernMetaKeys.PROTOCOL_VERSION, version)
    put(ModernMetaKeys.CLIENT_CAPABILITIES, buildJsonObject {})
    extra.forEach { (key, value) -> put(key, value) }
}

private fun modern(
    id: Any,
    method: String,
    params: JsonObject = JsonObject(emptyMap()),
    version: String = ModernProtocol.VERSION,
    extraMeta: Map<String, JsonElement> = emptyMap(),
): String = request(id, method, JsonObject(params + ("_meta" to meta(version, extraMeta))))

private fun legacy(id: String, method: String, params: JsonObject? = null): String = request(id, method, params)

private fun request(id: Any, method: String, params: JsonObject?): String = buildJsonObject {
    put("jsonrpc", "2.0")
    when (id) {
        is Number -> put("id", id)
        else -> put("id", id.toString())
    }
    put("method", method)
    if (params != null) put("params", params)
}.toString()

private fun initializeParams() = buildJsonObject {
    put("protocolVersion", "2025-11-25")
    put("capabilities", buildJsonObject {})
    put("clientInfo", buildJsonObject {
        put("name", "legacy-client")
        put("version", "1")
    })
}

private val JsonObject.result: JsonObject get() = this["result"]?.jsonObject ?: error("not a result: $this")

private val JsonObject.errorCode: Int? get() = (this["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.int

private fun names(toolsList: JsonObject): List<String> = toolsList["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

private fun contentText(result: JsonObject): String {
    val first = (result["content"] ?: result["contents"])!!.jsonArray.first().jsonObject
    return first["text"]!!.jsonPrimitive.content
}

private fun completionValues(result: JsonObject): List<String> =
    result["completion"]!!.jsonObject["values"]!!.jsonArray.map { it.jsonPrimitive.content }
