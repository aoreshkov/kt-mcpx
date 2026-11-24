package app.oreshkov.ktmcpx.ktor

import app.oreshkov.ktmcpx.ModernBridge
import app.oreshkov.ktmcpx.fixture.fixtureServer
import app.oreshkov.ktmcpx.testing.CapturedExchange
import app.oreshkov.ktmcpx.testing.CapturedTraffic
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Replays what Claude Code 2.1.331 sent kt-mcp (a stateless SDK route on `/w/sample/mcp`) against the
 * same route with `dualEra` added: the shape a stateless adopter gets.
 */
class CapturedTrafficTest {
    private val skippedHeaders = setOf("host", "content-length", "connection", "accept-encoding", "transfer-encoding")

    private fun ApplicationTestBuilder.ktMcpShape() {
        val server = fixtureServer()
        val bridge = ModernBridge(server)
        application {
            mcpStatelessStreamableHttp(path = "/w/sample/mcp") { server }
            routing { dualEra("/w/sample/mcp") { bridge } }
        }
    }

    private suspend fun ApplicationTestBuilder.replay(exchange: CapturedExchange): Pair<Int, String> {
        val response = client.request(exchange.request.path) {
            method = HttpMethod.parse(exchange.request.method)
            header("Host", "localhost")
            exchange.request.headers.filterKeys { it !in skippedHeaders && it != "content-type" }.forEach { (name, value) -> header(name, value) }
            if (exchange.request.body != JsonNull) setBody(TextContent(exchange.request.body.toString(), ContentType.Application.Json))
        }
        return response.status.value to response.bodyAsText()
    }

    @Test
    fun `with the default negotiation the discover probe now succeeds and the rest is unchanged`() = testApplication {
        ktMcpShape()
        val traffic = CapturedTraffic.CLAUDE_CODE_2_1_331_DEFAULT

        val (probeStatus, probeBody) = replay(traffic.first())
        assertEquals(200, probeStatus, probeBody)
        val result = Json.parseToJsonElement(probeBody).jsonObject["result"]!!.jsonObject
        assertEquals("complete", result["resultType"]!!.jsonPrimitive.content)
        assertEquals("server-discover-probe-1", Json.parseToJsonElement(probeBody).jsonObject["id"]!!.jsonPrimitive.content)

        for (exchange in traffic.drop(1)) {
            val (status, body) = replay(exchange)
            assertEquals(exchange.response.status, status, "$exchange: $body")
        }
    }

    @Test
    fun `a modern Claude Code session replays with the same answers`() = testApplication {
        ktMcpShape()

        for (exchange in CapturedTraffic.CLAUDE_CODE_2_1_331_MODERN) {
            val (status, body) = replay(exchange.copyToPath("/w/sample/mcp"))
            assertEquals(exchange.response.status, status, "$exchange: $body")
            val expected = exchange.response.body.jsonObject
            val actual = Json.parseToJsonElement(body).jsonObject
            assertEquals(expected["id"], actual["id"])
            assertEquals(expected["result"]!!.jsonObject.keys, actual["result"]!!.jsonObject.keys, body)
        }
    }

    private fun CapturedExchange.copyToPath(path: String) = CapturedExchange(
        seq,
        app.oreshkov.ktmcpx.testing.CapturedRequest(request.method, path, request.headers, request.body),
        response,
    )

    @Test
    fun `legacy negotiation never touches the bridge`() = testApplication {
        ktMcpShape()

        for (exchange in CapturedTraffic.CLAUDE_CODE_2_1_331_LEGACY) {
            val (status, body) = replay(exchange)
            assertEquals(exchange.response.status, status, "$exchange: $body")
            if (body.isNotBlank() && exchange.response.status == 200) {
                // Legacy answers have no modern envelope.
                assertEquals(null, Json.parseToJsonElement(body).jsonObject["result"]?.jsonObject?.get("resultType"), body)
            }
        }
    }
}
