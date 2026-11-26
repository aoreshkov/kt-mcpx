package app.oreshkov.ktmcpx

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.CompleteRequest
import io.modelcontextprotocol.kotlin.sdk.types.CompleteResult
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.EmptyJsonObject
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.PingRequest
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ProgressToken
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.SamplingMessage
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TaskSupport
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolExecution
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ModernBridgeTest {
    @Test
    fun `discover answers from the server's initialize result`() = runBlocking {
        val server = testServer(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
                resources = ServerCapabilities.Resources(listChanged = true, subscribe = true),
                logging = EmptyJsonObject,
                tasks = ServerCapabilities.Tasks(list = EmptyJsonObject, cancel = EmptyJsonObject),
            ),
        )
        val bridge = ModernBridge(server) { cache(CacheableMethod.ServerDiscover, ttl = 1.hours, scope = CacheScope.Public) }

        val reply = bridge.handle(body("server/discover"), headersFor(body("server/discover"))).response

        assertEquals(200, reply.httpStatus)
        assertEquals(JsonPrimitive(7), reply.message["id"])
        val result = reply.result
        assertEquals("complete", result["resultType"]!!.jsonPrimitive.content)
        assertEquals(listOf(ModernProtocol.VERSION), result["supportedVersions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Test instructions.", result["instructions"]!!.jsonPrimitive.content)
        assertEquals(3_600_000, result["ttlMs"]!!.jsonPrimitive.long)
        assertEquals("public", result["cacheScope"]!!.jsonPrimitive.content)
        val serverInfo = result["_meta"]!!.jsonObject[ModernMetaKeys.SERVER_INFO]!!.jsonObject
        assertEquals("test-server", serverInfo["name"]!!.jsonPrimitive.content)
        assertEquals("1.2.3", serverInfo["version"]!!.jsonPrimitive.content)
        // Only what the bridge serves: no legacy tasks; listChanged/subscribe stay for subscriptions/listen.
        val capabilities = result["capabilities"]!!.jsonObject
        assertEquals(setOf("tools", "resources", "logging"), capabilities.keys)
        assertEquals(setOf("listChanged"), capabilities["tools"]!!.jsonObject.keys)
        assertEquals(setOf("listChanged", "subscribe"), capabilities["resources"]!!.jsonObject.keys)
    }

    @Test
    fun `tools list is in the modern envelope with the configured cache hint`() = runBlocking {
        val server = testServer {
            addTool(
                Tool(name = "tasked", inputSchema = ToolSchema(), execution = ToolExecution(taskSupport = TaskSupport.Optional)),
            ) { CallToolResult(content = emptyList()) }
        }
        val bridge = ModernBridge(server) { cache(CacheableMethod.ToolsList, ttl = 24.hours) }

        val result = bridge.handle(body("tools/list"), headersFor(body("tools/list"))).response.result

        assertEquals("complete", result["resultType"]!!.jsonPrimitive.content)
        assertEquals(86_400_000, result["ttlMs"]!!.jsonPrimitive.long)
        assertEquals("private", result["cacheScope"]!!.jsonPrimitive.content)
        val tools = result["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("echo", "tasked"), tools.map { it["name"]!!.jsonPrimitive.content })
        assertTrue(tools.none { "execution" in it }, "execution is gone from 2026-07-28: $tools")
        assertEquals("test-server", result["_meta"]!!.jsonObject[ModernMetaKeys.SERVER_INFO]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unconfigured cacheable method gets the default hint, others none`() = runBlocking {
        val bridge = ModernBridge(testServer())

        val list = bridge.handle(body("tools/list"), null).response.result
        val call = bridge.handle(toolCall("echo"), null).response.result

        assertEquals(CachePolicy.DEFAULT.ttl.inWholeMilliseconds, list["ttlMs"]!!.jsonPrimitive.long)
        assertEquals("private", list["cacheScope"]!!.jsonPrimitive.content)
        assertNull(call["ttlMs"])
        assertNull(call["cacheScope"])
    }

    @Test
    fun `tools call runs the SDK handler and keeps pass-through meta`() = runBlocking {
        val seenMeta = CompletableDeferred<JsonObject?>()
        val server = testServer {
            addTool(name = "meta", description = "Reports its _meta.") { request ->
                seenMeta.complete(request.params.meta?.json)
                CallToolResult(content = listOf(TextContent("ok")))
            }
        }
        val bridge = ModernBridge(server)
        val requestMeta = meta(
            extra = mapOf(
                "progressToken" to JsonPrimitive("p-1"),
                "traceparent" to JsonPrimitive("00-abc-def-01"),
                "claudecode/toolUseId" to JsonPrimitive("toolu_1"),
            ),
        )

        val echo = bridge.handle(toolCall("echo", buildJsonObject { put("text", "hi") }), null).response
        val metaCall = bridge.handle(toolCall("meta", meta = requestMeta), null).response

        assertEquals("hi", echo.result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("complete", metaCall.result["resultType"]!!.jsonPrimitive.content)
        val forwarded = seenMeta.await()!!
        assertEquals(setOf("progressToken", "traceparent", "claudecode/toolUseId"), forwarded.keys)
    }

    @Test
    fun `progress for the request's token reaches the stream, other traffic does not`() = runBlocking {
        val server = testServer {
            addTool(name = "busy", description = "Reports progress and triggers a broadcast.") { request ->
                val token = request.params.meta?.progressToken!!
                notification(ProgressNotification(ProgressNotificationParams(token, 1.0, 2.0)))
                notification(ProgressNotification(ProgressNotificationParams(ProgressToken("someone-else"), 1.0, 2.0)))
                sendToolListChanged()
                notification(ProgressNotification(ProgressNotificationParams(token, 2.0, 2.0)))
                CallToolResult(content = listOf(TextContent("done")))
            }
        }
        val bridge = ModernBridge(server)
        val stream = Collections.synchronizedList(mutableListOf<JsonObject>())

        val reply = bridge.handle(toolCall("busy", meta = meta(extra = mapOf("progressToken" to JsonPrimitive("mine")))), null) {
            stream += it
        }.response

        assertEquals("done", reply.result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(listOf("notifications/progress", "notifications/progress"), stream.map { it["method"]!!.jsonPrimitive.content })
        assertEquals(listOf(1.0, 2.0), stream.map { it["params"]!!.jsonObject["progress"]!!.jsonPrimitive.content.toDouble() })
    }

    @Test
    fun `log messages go out only at or above the request's log level`() = runBlocking {
        val server = testServer {
            addTool(name = "chatty", description = "Logs at three levels.") {
                for (level in listOf(LoggingLevel.Debug, LoggingLevel.Warning, LoggingLevel.Error)) {
                    sendLoggingMessage(LoggingMessageNotification(LoggingMessageNotificationParams(level, JsonPrimitive(level.name))))
                }
                CallToolResult(content = emptyList())
            }
        }
        val bridge = ModernBridge(server)

        suspend fun levelsFor(logLevel: String?): List<String> {
            val seen = Collections.synchronizedList(mutableListOf<String>())
            val extra = if (logLevel == null) emptyMap() else mapOf(ModernMetaKeys.LOG_LEVEL to JsonPrimitive(logLevel))
            bridge.handle(toolCall("chatty", meta = meta(extra = extra)), null) {
                seen += it["params"]!!.jsonObject["level"]!!.jsonPrimitive.content
            }
            return seen.toList()
        }

        assertEquals(emptyList(), levelsFor(null))
        assertEquals(listOf("warning", "error"), levelsFor("warning"))
        assertEquals(listOf("debug", "warning", "error"), levelsFor("debug"))
    }

    @Test
    fun `a request that needs an undeclared capability is -32021 with strict SDK capabilities`() = runBlocking {
        val server = testServer(enforceStrictCapabilities = true) {
            addTool(name = "sample", description = "Asks for sampling.") { sampleHello() }
        }

        val reply = ModernBridge(server).handle(toolCall("sample"), null).response

        assertEquals(ModernErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY, reply.code)
        assertEquals(400, reply.httpStatus)
        assertEquals(buildJsonObject { put("sampling", JsonObject(emptyMap())) }, reply.error["data"]!!.jsonObject["requiredCapabilities"])
    }

    @Test
    fun `a request that needs an undeclared capability is -32021 without strict SDK capabilities`() = runBlocking {
        val handlerEnded = CompletableDeferred<Throwable?>()
        val server = testServer(enforceStrictCapabilities = false) {
            addTool(name = "sample", description = "Asks for sampling.") {
                try {
                    sampleHello()
                } catch (e: Throwable) {
                    handlerEnded.complete(e)
                    throw e
                }
            }
        }

        val reply = ModernBridge(server).handle(toolCall("sample"), null).response

        assertEquals(ModernErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY, reply.code)
        // The bridge aborts the call and closes the session, which cancels the waiting handler.
        assertTrue(withTimeout(5.seconds) { handlerEnded.await() } is CancellationException)
    }

    @Test
    fun `a ping from the handler is answered by the bridge`() = runBlocking {
        val server = testServer {
            addTool(name = "pinger", description = "Pings the client.") {
                ping(PingRequest())
                CallToolResult(content = listOf(TextContent("pong")))
            }
        }

        val reply = ModernBridge(server).handle(toolCall("pinger"), null).response

        assertEquals("pong", reply.result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `per-session handlers installed on connect serve modern requests`() = runBlocking {
        val server = testServer(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(), completions = EmptyJsonObject)) {
            onConnect {
                sessions.values.forEach { session ->
                    session.setRequestHandler<CompleteRequest>(Method.Defined.CompletionComplete) { request, _ ->
                        CompleteResult(CompleteResult.Completion(values = listOf(request.argument.value + "!"), total = 1, hasMore = false))
                    }
                }
            }
        }
        val params = buildJsonObject {
            put("ref", buildJsonObject {
                put("type", "ref/prompt")
                put("name", "p")
            })
            put("argument", buildJsonObject {
                put("name", "a")
                put("value", "x")
            })
        }

        val reply = ModernBridge(server).handle(body("completion/complete", params), null).response

        assertEquals("x!", reply.result["completion"]!!.jsonObject["values"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("complete", reply.result["resultType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a method whose capability the server lacks is -32601, HTTP 404`() = runBlocking {
        val reply = ModernBridge(testServer()).handle(body("prompts/list"), null).response

        assertEquals(ModernErrorCodes.METHOD_NOT_FOUND, reply.code)
        assertEquals(404, reply.httpStatus)
    }

    @Test
    fun `no session outlives its request`() = runBlocking {
        val server = testServer()
        val bridge = ModernBridge(server)

        coroutineScope {
            List(200) { index ->
                async {
                    val method = if (index % 2 == 0) "tools/list" else "server/discover"
                    bridge.handle(body(method), null)
                }
            }.forEach { it.await() }
        }
        repeat(200) { bridge.handle(toolCall("echo"), null) }

        assertEquals(0, server.sessions.size)
    }

    @Test
    fun `the request timeout ends a stuck call with -32603`() = runBlocking {
        val server = testServer {
            addTool(name = "stuck", description = "Never answers.") { awaitCancellation() }
        }
        val bridge = ModernBridge(server) { requestTimeout = 200.milliseconds }

        val reply = bridge.handle(toolCall("stuck"), null).response

        assertEquals(ModernErrorCodes.INTERNAL_ERROR, reply.code)
        assertEquals(0, server.sessions.size)
    }

    @Test
    fun `notifications sent after the answer never reach the stream`() = runBlocking {
        val late = CompletableDeferred<Unit>()
        val outside = CoroutineScope(Dispatchers.Default)
        val server = testServer {
            addTool(name = "late", description = "Answers, then keeps talking.") { request ->
                val token = request.params.meta?.progressToken!!
                val connection = this
                outside.launch {
                    try {
                        delay(100.milliseconds)
                        connection.notification(ProgressNotification(ProgressNotificationParams(token, 1.0, 1.0)))
                    } catch (_: Exception) {
                        // The session may already be closed; either way nothing may reach the stream.
                    } finally {
                        late.complete(Unit)
                    }
                }
                CallToolResult(content = emptyList())
            }
        }
        val stream = Collections.synchronizedList(mutableListOf<JsonObject>())

        ModernBridge(server).handle(toolCall("late", meta = meta(extra = mapOf("progressToken" to JsonPrimitive("t")))), null) { stream += it }
        withTimeout(5.seconds) { late.await() }

        assertTrue(stream.isEmpty(), "late traffic leaked: $stream")
    }

    @Test
    fun `a client notification is accepted and ignored`() = runBlocking {
        val reply = ModernBridge(testServer()).handle("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}""", null)

        assertEquals(ModernReply.Accepted, reply)
    }

    @Test
    fun `stdio has no header rules`() = runBlocking {
        val reply = ModernBridge(testServer()).handle(toolCall("echo"), headers = null).response

        assertEquals(200, reply.httpStatus)
        assertFalse(reply.result["content"] !is JsonArray)
    }

    private suspend fun ClientConnection.sampleHello(): CallToolResult {
        createMessage(
            CreateMessageRequest(
                CreateMessageRequestParams(maxTokens = 10, messages = listOf(SamplingMessage(Role.User, listOf(TextContent("hello"))))),
            ),
        )
        return CallToolResult(content = listOf(TextContent("sampled")))
    }
}
