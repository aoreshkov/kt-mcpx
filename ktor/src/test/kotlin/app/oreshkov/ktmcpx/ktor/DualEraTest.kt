package app.oreshkov.ktmcpx.ktor

import app.oreshkov.ktmcpx.ModernBridge
import app.oreshkov.ktmcpx.ModernErrorCodes
import app.oreshkov.ktmcpx.ModernHeaderNames
import app.oreshkov.ktmcpx.ModernProtocol
import app.oreshkov.ktmcpx.fixture.fixtureModule
import app.oreshkov.ktmcpx.fixture.fixtureServer
import app.oreshkov.ktmcpx.testing.ModernContract
import app.oreshkov.ktmcpx.testing.ModernTestClient
import app.oreshkov.ktmcpx.testing.ToolProbe
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val LOCALHOST = mapOf(HttpHeaders.Host to "localhost")

internal fun ApplicationTestBuilder.modernClient(path: String = "/mcp", http: HttpClient = client): ModernTestClient =
    ModernTestClient(http, path, extraHeaders = LOCALHOST)

class DualEraTest {
    @Test
    fun `the fixture endpoint passes the contract kit`() = testApplication {
        application { fixtureModule() }

        ModernContract.verify(modernClient(), ToolProbe("test_simple_text")).assertPassed()
    }

    @Test
    fun `a request over the concurrency limit is 503 with Retry-After`() = testApplication {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val server = fixtureServer().apply {
            addTool("block", "Waits for the gate.") {
                entered.complete(Unit)
                gate.await()
                CallToolResult(content = listOf(TextContent("done")))
            }
        }
        val bridge = ModernBridge(server) { maxConcurrentRequests = 1 }
        application { routing { dualEra("/mcp") { bridge } } }

        coroutineScope {
            val first = async { modernClient().send("tools/call", buildJsonObject { put("name", "block") }) }
            withTimeout(5.seconds) { entered.await() }
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "tools/list")
                put("params", buildJsonObject { put("_meta", modernClient().defaultMeta()) })
            }
            val refused = client.post("/mcp") {
                header(HttpHeaders.Host, "localhost")
                header(ModernHeaderNames.METHOD, "tools/list")
                header(ModernHeaderNames.PROTOCOL_VERSION, ModernProtocol.VERSION)
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }

            assertEquals(503, refused.status.value)
            assertEquals("1", refused.headers[HttpHeaders.RetryAfter])
            val error = Json.parseToJsonElement(refused.bodyAsText()).jsonObject["error"]!!.jsonObject
            assertEquals(ModernErrorCodes.INTERNAL_ERROR, error["code"]!!.jsonPrimitive.int)
            gate.complete(Unit)
            assertEquals(200, first.await().status)
        }
    }

    @Test
    fun `a parameterized path picks its bridge per call, next to a stateless SDK route`() = testApplication {
        val bridges = mapOf("a" to ModernBridge(fixtureServer()), "b" to ModernBridge(fixtureServer()))
        application {
            mcpStatelessStreamableHttp(path = "/w/{ws}/mcp") { fixtureServer() }
            routing { dualEra("/w/{ws}/mcp") { bridges.getValue(call.parameters["ws"]!!) } }
        }

        ModernContract.verify(modernClient("/w/a/mcp"), ToolProbe("test_simple_text")).assertPassed()
        ModernContract.verify(modernClient("/w/b/mcp")).assertPassed()
    }

    @Test
    fun `progress switches the answer to an SSE stream that ends with the result`() = testApplication {
        application { fixtureModule() }
        val params = buildJsonObject {
            put("name", "test_tool_with_progress")
            put("arguments", buildJsonObject {})
        }
        val meta = JsonPrimitive("progress-1").let { token ->
            kotlinx.serialization.json.JsonObject(modernClient().defaultMeta() + ("progressToken" to token))
        }

        val response = modernClient().send("tools/call", params, meta = meta)

        assertEquals(200, response.status)
        assertTrue(response.isEventStream, response.toString())
        assertEquals(List(3) { "notifications/progress" }, response.notifications.map { it["method"]!!.jsonPrimitive.content })
        assertEquals(listOf("0.0", "50.0", "100.0"), response.notifications.map { it["params"]!!.jsonObject["progress"]!!.jsonPrimitive.content.toDouble().toString() })
        assertEquals("complete", response.result!!["resultType"]!!.jsonPrimitive.content)
        assertEquals(response.messages.last(), response.final)
    }

    @Test
    fun `without SSE in Accept the answer stays JSON and progress is dropped`() = testApplication {
        application { fixtureModule() }
        val client = modernClient()
        val params = buildJsonObject {
            put("name", "test_tool_with_progress")
            put("arguments", buildJsonObject {})
        }
        val meta = kotlinx.serialization.json.JsonObject(client.defaultMeta() + ("progressToken" to JsonPrimitive("p")))

        val response = client.send("tools/call", params, meta = meta, headers = mapOf(HttpHeaders.Accept to "application/json"))

        assertEquals(200, response.status)
        assertFalse(response.isEventStream)
        assertEquals(1, response.messages.size)
        assertEquals("Progress tool completed", response.result!!["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a call with no notifications answers JSON even when SSE is accepted`() = testApplication {
        application { fixtureModule() }

        val response = modernClient().send("tools/call", buildJsonObject {
            put("name", "test_simple_text")
            put("arguments", buildJsonObject {})
        })

        assertFalse(response.isEventStream)
        assertEquals("application/json", response.contentType?.substringBefore(';'))
    }

    @Test
    fun `an undeclared capability is -32021, HTTP 400, on the wire`() = testApplication {
        application { fixtureModule() }

        val response = modernClient().send("tools/call", buildJsonObject {
            put("name", "test_missing_capability")
            put("arguments", buildJsonObject {})
        })

        assertEquals(400, response.status)
        assertEquals(ModernErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY, response.errorCode)
    }

    @Test
    fun `Mcp-Param headers travel over HTTP and are checked against the arguments`() = testApplication {
        application { fixtureModule() }
        val client = modernClient()
        val arguments = buildJsonObject {
            put("region", "Hello, 世界")
            put("priority", 3)
            put("verbose", true)
        }
        assertEquals(
            mapOf("Mcp-Param-Region" to "=?base64?SGVsbG8sIOS4lueVjA==?=", "Mcp-Param-Priority" to "3", "Mcp-Param-Verbose" to "true"),
            client.paramHeadersFor("test_custom_headers", arguments),
        )

        val result = client.callTool("test_custom_headers", arguments)
        assertTrue(result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("世界"), result.toString())

        val params = buildJsonObject {
            put("name", "test_custom_headers")
            put("arguments", arguments)
        }
        val lowercase = client.paramHeadersFor("test_custom_headers", arguments).mapKeys { it.key.lowercase() }
        assertEquals(200, client.send("tools/call", params, headers = lowercase).status)
        val missing = client.send("tools/call", params, headers = lowercase - "mcp-param-verbose")
        assertEquals(400, missing.status)
        assertEquals(ModernErrorCodes.HEADER_MISMATCH, missing.errorCode)
    }

    @Test
    fun `subscriptions listen streams over SSE until the bridge closes it`() = testApplication {
        val server = fixtureServer()
        val bridge = ModernBridge(server)
        application { routing { dualEra("/mcp") { bridge } } }
        val client = modernClient()
        val filter = buildJsonObject { put("notifications", buildJsonObject { put("toolsListChanged", true) }) }

        coroutineScope {
            val listening = async { client.send("subscriptions/listen", filter) }
            // The listener session exists once the stream is subscribed.
            withTimeout(5.seconds) { while (server.sessions.isEmpty()) delay(10) }
            client.callTool("test_trigger_tool_change")
            withTimeout(5.seconds) { while (server.tools.keys.none { it == "test_dynamic_tool" }) delay(10) }
            delay(200)
            bridge.close()
            val response = withTimeout(5.seconds) { listening.await() }

            assertTrue(response.isEventStream, response.toString())
            assertEquals(
                listOf("notifications/subscriptions/acknowledged", "notifications/tools/list_changed"),
                response.notifications.map { it["method"]!!.jsonPrimitive.content },
            )
            assertEquals("complete", response.result!!["resultType"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `subscriptions listen without SSE in Accept is 406`() = testApplication {
        application { fixtureModule() }
        val filter = buildJsonObject { put("notifications", buildJsonObject { put("toolsListChanged", true) }) }

        val response = modernClient().send("subscriptions/listen", filter, headers = mapOf(HttpHeaders.Accept to "application/json"))

        assertEquals(406, response.status)
        assertEquals(ModernErrorCodes.INVALID_REQUEST, response.errorCode)
    }

    @Test
    fun `a body over the limit is refused with 413`() = testApplication {
        application {
            val bridge = ModernBridge(fixtureServer())
            routing { dualEra("/mcp", maxRequestBodySize = 64) { bridge } }
        }

        val response = modernClient().send("tools/list", rawBody = "{" + " ".repeat(200) + "}")

        assertEquals(413, response.status)
        assertEquals(ModernErrorCodes.INVALID_REQUEST, response.errorCode)
    }
}
