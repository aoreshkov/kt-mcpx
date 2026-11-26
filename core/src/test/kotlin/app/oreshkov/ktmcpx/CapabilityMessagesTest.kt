package app.oreshkov.ktmcpx

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.SamplingMessage
import io.modelcontextprotocol.kotlin.sdk.types.StringSchema
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * With strict capabilities (the SDK default), SDK 0.16.0 refuses a request to a client that did not
 * declare the capability before it reaches the transport, and its tool wrapper reports only the text.
 * The bridge recognises that text (`internal/Capabilities.kt`). These cases run the real SDK, so an SDK
 * release that rewords a message fails here, not silently in production.
 */
class CapabilityMessagesTest {
    private val empty = JsonObject(emptyMap())

    private val server = testServer(enforceStrictCapabilities = true) {
        tool("sample") { createMessage(sampling(withTools = false)) }
        tool("sample_with_tools") { createMessage(sampling(withTools = true)) }
        tool("roots") { listRoots() }
        tool("elicit_form") {
            createElicitation("Name?", ElicitRequestParams.RequestedSchema(properties = mapOf("name" to StringSchema())))
        }
        tool("elicit_url") { createElicitation(message = "Open it", elicitationId = "e-1", url = "https://example.com/") }
    }

    private fun required(capability: String, sub: String? = null): JsonObject = buildJsonObject {
        put(capability, if (sub == null) empty else buildJsonObject { put(sub, empty) })
    }

    private fun callWith(tool: String, capabilities: JsonObject): ModernReply.Response =
        runBlocking { ModernBridge(server).handle(toolCall(tool, meta = meta(capabilities)), null).response }

    private fun assertMissing(tool: String, capabilities: JsonObject, expected: JsonObject) {
        val reply = callWith(tool, capabilities)
        assertEquals(ModernErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY, reply.code, reply.message.toString())
        assertEquals(400, reply.httpStatus)
        assertEquals(expected, reply.error["data"]!!.jsonObject["requiredCapabilities"])
    }

    @Test fun sampling() = assertMissing("sample", empty, required("sampling"))

    @Test fun `sampling with tools`() = assertMissing("sample_with_tools", required("sampling"), required("sampling", "tools"))

    @Test fun roots() = assertMissing("roots", empty, required("roots"))

    @Test fun `form elicitation`() = assertMissing("elicit_form", empty, required("elicitation"))

    @Test fun `URL elicitation`() = assertMissing("elicit_url", required("elicitation", "form"), required("elicitation", "url"))

    @Test
    fun `with the capability declared it is not -32021`() {
        // The request reaches the bridge, which passes it to the client as an input request.
        val reply = callWith("sample", required("sampling"))
        assertEquals(200, reply.httpStatus)
        assertEquals("input_required", reply.result["resultType"]!!.jsonPrimitive.content)
    }

    private fun io.modelcontextprotocol.kotlin.sdk.server.Server.tool(name: String, block: suspend ClientConnection.() -> Any) {
        addTool(Tool(name = name, inputSchema = ToolSchema())) {
            block()
            CallToolResult(content = listOf(TextContent("unreachable")))
        }
    }

    private fun sampling(withTools: Boolean) = CreateMessageRequest(
        CreateMessageRequestParams(
            maxTokens = 10,
            messages = listOf(SamplingMessage(Role.User, listOf(TextContent("hi")))),
            tools = if (withTools) listOf(Tool(name = "t", inputSchema = ToolSchema())) else null,
        ),
    )
}
