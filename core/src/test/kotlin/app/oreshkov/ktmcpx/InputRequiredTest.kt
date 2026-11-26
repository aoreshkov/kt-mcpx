package app.oreshkov.ktmcpx

import app.oreshkov.ktmcpx.internal.RequestStateCodec
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.CompleteRequest
import io.modelcontextprotocol.kotlin.sdk.types.CompleteResult
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequest
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestFormParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestURLParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.EmptyJsonObject
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.RequestMeta
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.SamplingMessage
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.StringSchema
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.UrlElicitationRequiredException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InputRequiredTest {
    private val everything = buildJsonObject {
        put("elicitation", buildJsonObject {
            put("form", EmptyJsonObject)
            put("url", EmptyJsonObject)
        })
        put("sampling", EmptyJsonObject)
        put("roots", EmptyJsonObject)
    }

    @Test
    fun `a question becomes an input request, and the retry answers it`() = runBlocking {
        val bridge = ModernBridge(testServer { addTool("greet", "Greets the user.") { greet() } })

        val first = bridge.handle(call("greet"), null).response
        assertEquals("input_required", first.result.string("resultType"))
        val (key, inputRequest) = first.inputRequests.entries.single()
        assertTrue(key.startsWith("elicitation-"), key)
        assertEquals("elicitation/create", inputRequest.jsonObject.string("method"))
        assertEquals("What is your name?", inputRequest.jsonObject["params"]!!.jsonObject.string("message"))
        assertTrue(first.result.string("requestState")!!.isNotEmpty())
        assertEquals("test-server", first.result["_meta"]!!.jsonObject[ModernMetaKeys.SERVER_INFO]!!.jsonObject.string("name"))

        val second = bridge.handle(call("greet", mapOf(key to accept("name" to "Alice")), first.requestState), null).response
        assertEquals("complete", second.result.string("resultType"))
        assertEquals("Hello, Alice!", second.text)
    }

    @Test
    fun `a handler that asks twice gets both answers, one round each`() = runBlocking {
        val runs = AtomicInteger()
        val server = testServer {
            addTool("two_questions", "Asks two questions in turn.") {
                runs.incrementAndGet()
                val name = elicit("Your name?", "name")
                val color = elicit("Your favourite colour?", "color")
                CallToolResult(content = listOf(TextContent("${name.content?.string("name")} likes ${color.content?.string("color")}")))
            }
        }
        val bridge = ModernBridge(server)

        val round1 = bridge.handle(call("two_questions"), null).response
        val nameKey = round1.inputRequests.keys.single()
        val round2 = bridge.handle(call("two_questions", mapOf(nameKey to accept("name" to "Alice")), round1.requestState), null).response
        val colorKey = round2.inputRequests.keys.single()
        assertNotEquals(nameKey, colorKey)
        assertNotEquals(round1.requestState, round2.requestState)
        // The client answers only the new question; the first answer comes back from requestState.
        val round3 = bridge.handle(call("two_questions", mapOf(colorKey to accept("color" to "blue")), round2.requestState), null).response

        assertEquals("Alice likes blue", round3.text)
        assertEquals(3, runs.get())
    }

    @Test
    fun `a key named in _meta is used and taken out of the request the client sees`() = runBlocking {
        val bridge = ModernBridge(
            testServer {
                addTool("named", "Asks with a named key.") {
                    val answer = elicit("Your name?", "name", key = "user_name")
                    CallToolResult(content = listOf(TextContent("Hi ${answer.content?.string("name")}")))
                }
            },
        )

        val first = bridge.handle(call("named"), null).response
        val params = first.inputRequests["user_name"]!!.jsonObject["params"]!!.jsonObject
        assertFalse("_meta" in params, params.toString())
        // No requestState needed when the client knows the key.
        assertEquals("Hi Bob", bridge.handle(call("named", mapOf("user_name" to accept("name" to "Bob"))), null).response.text)
    }

    @Test
    fun `questions asked concurrently share one result`() = runBlocking {
        val bridge = ModernBridge(
            testServer {
                addTool("three", "Asks three things at once.") {
                    coroutineScope {
                        val name = async { elicit("Your name?", "name", key = "name") }
                        val greeting = async { sample("Generate a greeting", key = "greeting") }
                        val roots = async { listRoots() }
                        CallToolResult(content = listOf(TextContent("${name.await().action} ${greeting.await().model} ${roots.await().roots.size}")))
                    }
                }
            },
        )

        val first = bridge.handle(call("three"), null).response
        assertEquals(setOf("elicitation/create", "sampling/createMessage", "roots/list"), first.inputRequests.values.map { it.jsonObject.string("method") }.toSet())

        val answers = first.inputRequests.mapValues { (_, request) ->
            when (request.jsonObject.string("method")) {
                "elicitation/create" -> accept("name" to "Alice")
                "sampling/createMessage" -> buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonObject {
                        put("type", "text")
                        put("text", "Hello!")
                    })
                    put("model", "test-model")
                }
                else -> buildJsonObject { put("roots", kotlinx.serialization.json.JsonArray(emptyList())) }
            }
        }
        assertEquals("Accept test-model 0", bridge.handle(call("three", answers, first.requestState), null).response.text)
    }

    @Test
    fun `an answer keyed for one question never reaches a different one`() = runBlocking {
        val bridge = ModernBridge(testServer { addTool("greet", "Greets the user.") { greet() } })
        val first = bridge.handle(call("greet"), null).response

        // Wrong and extra keys are ignored: the question is asked again, as SEP-2322 recommends.
        val retry = bridge.handle(call("greet", mapOf("elicitation-000000000000" to accept("name" to "Eve")), first.requestState), null).response

        assertEquals("input_required", retry.result.string("resultType"))
        assertEquals(first.inputRequests.keys, retry.inputRequests.keys)
    }

    @Test
    fun `a tampered, foreign or malformed requestState is -32602`() = runBlocking {
        val bridge = ModernBridge(testServer { addTool("greet", "Greets the user.") { greet() } })
        val first = bridge.handle(call("greet"), null).response
        val key = first.inputRequests.keys.single()
        val answer = mapOf(key to accept("name" to "Alice"))

        for (state in listOf(JsonPrimitive(first.requestState!!.jsonPrimitive.content + "-TAMPERED"), JsonPrimitive("not-a-state"), JsonPrimitive(42))) {
            val reply = bridge.handle(call("greet", answer, state), null).response
            assertEquals(ModernErrorCodes.INVALID_PARAMS, reply.code, state.toString())
            assertEquals(400, reply.httpStatus)
        }
        // Another bridge has another key.
        val other = ModernBridge(testServer { addTool("greet", "Greets the user.") { greet() } })
        assertEquals(ModernErrorCodes.INVALID_PARAMS, other.handle(call("greet", answer, first.requestState), null).response.code)
        // The state is bound to the request's arguments.
        val foreign = bridge.handle(call("greet", answer, first.requestState, arguments = buildJsonObject { put("x", 1) }), null).response
        assertEquals(ModernErrorCodes.INVALID_PARAMS, foreign.code)
    }

    @Test
    fun `a shared secret lets another bridge instance accept the state`() = runBlocking {
        val secret = ByteArray(32) { it.toByte() }
        fun bridge() = ModernBridge(testServer { addTool("greet", "Greets the user.") { greet() } }) { requestStateSecret(secret) }
        val first = bridge().handle(call("greet"), null).response
        val key = first.inputRequests.keys.single()

        val reply = bridge().handle(call("greet", mapOf(key to accept("name" to "Alice")), first.requestState), null).response

        assertEquals("Hello, Alice!", reply.text)
    }

    @Test
    fun `an expired requestState is -32602`() {
        var now = 1_000L
        val codec = RequestStateCodec(ByteArray(32), ttlMs = 100, clock = { now })
        val params = buildJsonObject { put("name", "greet") }
        val state = JsonPrimitive(codec.seal("tools/call", params, emptyMap()))

        assertTrue(codec.open(state, "tools/call", params) is RequestStateCodec.Opened.Ok)
        assertTrue(codec.open(state, "prompts/get", params) is RequestStateCodec.Opened.Rejected)
        now += 101
        val expired = codec.open(state, "tools/call", params)
        assertTrue(expired is RequestStateCodec.Opened.Rejected && "expired" in expired.message)
    }

    @Test
    fun `inputResponses that are not objects are -32602`() = runBlocking {
        val bridge = ModernBridge(testServer { addTool("greet", "Greets the user.") { greet() } })
        for (responses in listOf<JsonElement>(kotlinx.serialization.json.JsonNull, JsonPrimitive("x"), buildJsonObject { put("k", 12345) })) {
            val text = body("tools/call", buildJsonObject {
                put("name", "greet")
                put("arguments", EmptyJsonObject)
                put("inputResponses", responses)
            }, meta(everything))
            assertEquals(ModernErrorCodes.INVALID_PARAMS, bridge.handle(text, null).response.code, responses.toString())
        }
    }

    @Test
    fun `prompts and resources may ask too, and a retried read is not cached`() = runBlocking {
        val server = testServer(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(),
                prompts = ServerCapabilities.Prompts(),
                resources = ServerCapabilities.Resources(),
            ),
        ) {
            addPrompt("ask_prompt", "Asks first.") {
                val answer = elicit("Context?", "context", key = "ctx")
                GetPromptResult(listOf(PromptMessage(Role.User, TextContent("ctx=${answer.content?.string("context")}"))))
            }
            addResource("test://ask", "ask", "Asks first.", "text/plain") { request ->
                val answer = elicit("Which?", "which", key = "which")
                ReadResourceResult(listOf(TextResourceContents("which=${answer.content?.string("which")}", request.params.uri, "text/plain")))
            }
        }
        val bridge = ModernBridge(server)

        val prompt = bridge.handle(body("prompts/get", buildJsonObject { put("name", "ask_prompt") }, meta(everything)), null).response
        assertEquals(setOf("ctx"), prompt.inputRequests.keys)
        val read = bridge.handle(body("resources/read", buildJsonObject { put("uri", "test://ask") }, meta(everything)), null).response
        assertEquals(setOf("which"), read.inputRequests.keys)

        val retried = bridge.handle(
            body("resources/read", buildJsonObject {
                put("uri", "test://ask")
                put("inputResponses", buildJsonObject { put("which", accept("which" to "this")) })
            }, meta(everything)),
            null,
        ).response
        assertEquals("which=this", retried.result["contents"]!!.jsonArray[0].jsonObject.string("text"))
        assertEquals(0, retried.result["ttlMs"]!!.jsonPrimitive.long)
    }

    @Test
    fun `a URL elicitation loses its elicitationId, and so does UrlElicitationRequiredException`() = runBlocking {
        val bridge = ModernBridge(
            testServer {
                addTool("url_ask", "Sends the user to a URL.") {
                    val answer = createElicitation(message = "Sign in", elicitationId = "random-${System.nanoTime()}", url = "https://example.com/login")
                    CallToolResult(content = listOf(TextContent("action=${answer.action}")))
                }
                addTool("url_throw", "Needs a URL flow first.") {
                    throw UrlElicitationRequiredException(listOf(ElicitRequestURLParams("Authorize", "e-1", "https://example.com/oauth")))
                }
            },
        )

        val asked = bridge.handle(call("url_ask"), null).response
        val (key, request) = asked.inputRequests.entries.single()
        val params = request.jsonObject["params"]!!.jsonObject
        assertEquals("url", params.string("mode"))
        assertFalse("elicitationId" in params, params.toString())
        // The id differs on every run, but the key does not, so the retry is answered.
        val answered = bridge.handle(call("url_ask", mapOf(key to buildJsonObject { put("action", "accept") }), asked.requestState), null).response
        assertEquals("action=Accept", answered.text)

        val thrown = bridge.handle(call("url_throw"), null).response
        val thrownParams = thrown.inputRequests.values.single().jsonObject["params"]!!.jsonObject
        assertEquals("https://example.com/oauth", thrownParams.string("url"))
        assertFalse("elicitationId" in thrownParams, thrownParams.toString())

        val noUrl = bridge.handle(call("url_throw", capabilities = buildJsonObject { put("elicitation", EmptyJsonObject) }), null).response
        assertEquals(ModernErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY, noUrl.code)
    }

    @Test
    fun `a question during a method that cannot answer input_required is refused to the handler`() = runBlocking {
        val server = testServer(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(), completions = EmptyJsonObject)) {
            onConnect {
                sessions.values.forEach { session ->
                    session.setRequestHandler<CompleteRequest>(Method.Defined.CompletionComplete) { _, _ ->
                        val outcome = try {
                            clientConnection(session.sessionId).greet()
                            "answered"
                        } catch (e: McpException) {
                            "refused ${e.code}"
                        }
                        CompleteResult(CompleteResult.Completion(listOf(outcome)))
                    }
                }
            }
        }
        val text = body("completion/complete", buildJsonObject {
            put("ref", buildJsonObject {
                put("type", "ref/prompt")
                put("name", "p")
            })
            put("argument", buildJsonObject {
                put("name", "a")
                put("value", "")
            })
        }, meta(everything))

        val reply = ModernBridge(server).handle(text, null).response

        assertEquals("complete", reply.result.string("resultType"))
        assertEquals("refused -32601", reply.result["completion"]!!.jsonObject["values"]!!.jsonArray[0].jsonPrimitive.content)
    }

    private fun call(
        tool: String,
        responses: Map<String, JsonObject>? = null,
        state: JsonElement? = null,
        arguments: JsonObject = EmptyJsonObject,
        capabilities: JsonObject = everything,
    ): String = body("tools/call", buildJsonObject {
        put("name", tool)
        put("arguments", arguments)
        if (responses != null) put("inputResponses", JsonObject(responses))
        if (state != null) put("requestState", state)
    }, meta(capabilities))

    private fun accept(vararg content: Pair<String, String>): JsonObject = buildJsonObject {
        put("action", "accept")
        put("content", buildJsonObject { content.forEach { (k, v) -> put(k, v) } })
    }

    private val ModernReply.Response.inputRequests: JsonObject get() = result["inputRequests"]!!.jsonObject

    private val ModernReply.Response.requestState: JsonElement? get() = result["requestState"]

    private val ModernReply.Response.text: String
        get() = result["content"]!!.jsonArray[0].jsonObject.string("text")!!

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
}

private suspend fun ClientConnection.greet(): CallToolResult {
    val answer = elicit("What is your name?", "name")
    return CallToolResult(content = listOf(TextContent("Hello, ${(answer.content?.get("name") as? JsonPrimitive)?.content}!")))
}

private suspend fun ClientConnection.elicit(message: String, field: String, key: String? = null): ElicitResult = createElicitation(
    ElicitRequest(
        ElicitRequestFormParams(
            message = message,
            requestedSchema = ElicitRequestParams.RequestedSchema(properties = mapOf(field to StringSchema()), required = listOf(field)),
            meta = key?.let { RequestMeta(buildJsonObject { put(ModernInputRequests.KEY_META, it) }) },
        ),
    ),
)

private suspend fun ClientConnection.sample(prompt: String, key: String) = createMessage(
    CreateMessageRequest(
        CreateMessageRequestParams(
            maxTokens = 50,
            messages = listOf(SamplingMessage(Role.User, listOf(TextContent(prompt)))),
            meta = RequestMeta(buildJsonObject { put(ModernInputRequests.KEY_META, key) }),
        ),
    ),
)
