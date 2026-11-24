package app.oreshkov.ktmcpx

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SEP-2243: `x-mcp-header` annotations, value encoding, and the bridge's `Mcp-Param-*` checks. */
class ParamHeadersTest {
    private fun schema(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val deploySchema = schema(
        """
        {"type":"object","properties":{
          "region":{"type":"string","x-mcp-header":"Region"},
          "priority":{"type":"integer","x-mcp-header":"Priority"},
          "dryRun":{"type":"boolean","x-mcp-header":"Dry-Run"},
          "target":{"type":"object","properties":{"zone":{"type":"string","x-mcp-header":"Zone"}}},
          "note":{"type":"string"}
        }}
        """,
    )

    private val server = testServer {
        addTool(
            name = "deploy",
            description = "Deploys.",
            inputSchema = ToolSchema(properties = deploySchema["properties"]!!.jsonObject),
        ) { CallToolResult(content = listOf(TextContent("deployed"))) }
        addTool(
            name = "broken",
            description = "Annotated on a number, which clients reject.",
            inputSchema = ToolSchema(properties = schema("""{"ratio":{"type":"number","x-mcp-header":"Ratio"}}""")),
        ) { CallToolResult(content = listOf(TextContent("ran"))) }
    }
    private val bridge = ModernBridge(server)

    private fun call(tool: String, arguments: JsonObject, params: Map<String, String>?): ModernReply.Response {
        val text = toolCall(tool, arguments)
        val headers = params?.let { headersFor(text).let { h -> ModernHeaders(h.method, h.name, h.protocolVersion, it) } }
        return runBlocking { bridge.handle(text, headers).response }
    }

    private fun base64(value: String) = "=?base64?" + Base64.getEncoder().encodeToString(value.encodeToByteArray()) + "?="

    private fun assertAccepted(reply: ModernReply.Response) = assertEquals(200, reply.httpStatus, reply.message.toString())

    private fun assertMismatch(reply: ModernReply.Response) {
        assertEquals(400, reply.httpStatus, reply.message.toString())
        assertEquals(ModernErrorCodes.HEADER_MISMATCH, reply.code, reply.message.toString())
    }

    private val region = buildJsonObject { put("region", "us-west1") }

    @Test
    fun `valid annotations have no problems, nested properties included`() {
        assertEquals(emptyList(), ModernParamHeaders.problems(deploySchema))
        val headers = ModernParamHeaders.headersFor(
            deploySchema,
            buildJsonObject {
                put("region", "us-west1")
                put("priority", 42.0)
                put("dryRun", false)
                put("target", buildJsonObject { put("zone", "b") })
                put("note", "not mirrored")
            },
        )
        assertEquals(
            mapOf("Mcp-Param-Region" to "us-west1", "Mcp-Param-Priority" to "42", "Mcp-Param-Dry-Run" to "false", "Mcp-Param-Zone" to "b"),
            headers,
        )
    }

    @Test
    fun `absent and null arguments get no header`() {
        val headers = ModernParamHeaders.headersFor(deploySchema, buildJsonObject { put("region", JsonNull) })
        assertEquals(emptyMap(), headers)
    }

    @Test
    fun `annotations in the wrong place, of the wrong type or repeated are problems`() {
        val cases = mapOf(
            """{"type":"object","x-mcp-header":"Root"}""" to "/",
            """{"type":"object","properties":{"a":{"type":"array","items":{"type":"string","x-mcp-header":"A"}}}}""" to "/properties/a/items",
            """{"type":"object","properties":{"a":{"anyOf":[{"type":"string","x-mcp-header":"A"}]}}}""" to "/properties/a/anyOf/0",
            """{"type":"object","${'$'}defs":{"d":{"type":"string","x-mcp-header":"D"}}}""" to "/${'$'}defs/d",
            """{"type":"object","properties":{"a":{"type":"number","x-mcp-header":"A"}}}""" to "string, integer or boolean",
            """{"type":"object","properties":{"a":{"type":"object","x-mcp-header":"A"}}}""" to "string, integer or boolean",
            """{"type":"object","properties":{"a":{"type":"string","x-mcp-header":"Has Space"}}}""" to "HTTP token",
            """{"type":"object","properties":{"a":{"type":"string","x-mcp-header":""}}}""" to "HTTP token",
            """{"type":"object","properties":{"a":{"type":"string","x-mcp-header":"Same"},"b":{"type":"string","x-mcp-header":"SAME"}}}""" to "case-insensitive",
        )
        for ((text, expected) in cases) {
            val problems = ModernParamHeaders.problems(schema(text))
            assertTrue(problems.size == 1 && expected in problems.single(), "$text: $problems")
            assertEquals(emptyMap(), ModernParamHeaders.headersFor(schema(text), buildJsonObject { put("a", "x") }))
        }
    }

    @Test
    fun `instance data and a property named like the keyword are not annotations`() {
        val text = """{"type":"object","properties":{"x-mcp-header":{"type":"string"},"a":{"type":"object","default":{"x-mcp-header":"D"}}}}"""
        assertEquals(emptyList(), ModernParamHeaders.problems(schema(text)))
    }

    @Test
    fun `values are encoded as in the spec's table`() {
        assertEquals("us-west1", ModernParamHeaders.encode("us-west1"))
        assertEquals("=?base64?SGVsbG8sIOS4lueVjA==?=", ModernParamHeaders.encode("Hello, 世界"))
        assertEquals("=?base64?IHBhZGRlZCA=?=", ModernParamHeaders.encode(" padded "))
        assertEquals("=?base64?bGluZTEKbGluZTI=?=", ModernParamHeaders.encode("line1\nline2"))
        assertEquals("=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=", ModernParamHeaders.encode("=?base64?literal?="))
        assertEquals("a b", ModernParamHeaders.encode("a b"))
    }

    @Test
    fun `matching headers pass, Base64 decoded and names in any case`() {
        assertAccepted(call("deploy", region, mapOf("Region" to "us-west1")))
        assertAccepted(call("deploy", region, mapOf("rEgIoN" to "us-west1")))
        assertAccepted(call("deploy", region, mapOf("Region" to base64("us-west1"))))
        assertAccepted(call("deploy", buildJsonObject { put("region", " padded ") }, mapOf("Region" to base64(" padded "))))
        assertAccepted(call("deploy", buildJsonObject { put("target", buildJsonObject { put("zone", "b") }) }, mapOf("Zone" to "b")))
    }

    @Test
    fun `integers compare numerically and booleans exactly`() {
        assertAccepted(call("deploy", buildJsonObject { put("priority", 42) }, mapOf("Priority" to "42.0")))
        assertMismatch(call("deploy", buildJsonObject { put("priority", 42) }, mapOf("Priority" to "43")))
        assertAccepted(call("deploy", buildJsonObject { put("dryRun", true) }, mapOf("Dry-Run" to "true")))
        assertMismatch(call("deploy", buildJsonObject { put("dryRun", true) }, mapOf("Dry-Run" to "TRUE")))
    }

    @Test
    fun `a value without both Base64 markers is literal`() {
        val encoded = Base64.getEncoder().encodeToString("Hello".encodeToByteArray())
        assertAccepted(call("deploy", buildJsonObject { put("region", encoded) }, mapOf("Region" to encoded)))
        assertAccepted(call("deploy", buildJsonObject { put("region", "=?base64?$encoded") }, mapOf("Region" to "=?base64?$encoded")))
    }

    @Test
    fun `missing, disagreeing, broken and unexpected headers are -32020`() {
        assertMismatch(call("deploy", region, emptyMap()))
        assertMismatch(call("deploy", region, mapOf("Region" to "eu-west1")))
        assertMismatch(call("deploy", buildJsonObject { put("region", "Hello") }, mapOf("Region" to "=?base64?SGVsbG8?=")))
        assertMismatch(call("deploy", buildJsonObject { put("region", "Hello") }, mapOf("Region" to "=?base64?SGVs!!!bG8=?=")))
        assertMismatch(call("deploy", buildJsonObject { put("region", "Hello") }, mapOf("Region" to "Hellö")))
        assertMismatch(call("deploy", buildJsonObject { put("region", " padded ") }, mapOf("Region" to "padded")))
        assertMismatch(call("deploy", buildJsonObject {}, mapOf("Region" to "us-west1")))
    }

    @Test
    fun `unknown headers, unknown tools, invalid annotations and stdio are not checked`() {
        assertAccepted(call("deploy", buildJsonObject { put("note", "n") }, mapOf("Other" to "x")))
        assertAccepted(call("deploy", region, null))
        assertAccepted(call("broken", buildJsonObject { put("ratio", 0.5) }, emptyMap()))
        val unknown = call("missing-tool", region, emptyMap())
        assertTrue(unknown.httpStatus != 400 || unknown.code != ModernErrorCodes.HEADER_MISMATCH, unknown.message.toString())
    }

    @Test
    fun `a Base64 Mcp-Name without padding is rejected`() {
        val text = toolCall("echo")
        val reply = runBlocking { bridge.handle(text, ModernHeaders("tools/call", "=?base64?ZWNobw?=", ModernProtocol.VERSION)).response }
        assertMismatch(reply)
    }
}
