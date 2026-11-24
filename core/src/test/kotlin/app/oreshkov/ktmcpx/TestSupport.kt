package app.oreshkov.ktmcpx

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmptyJsonObject
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun testServer(
    capabilities: ServerCapabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true), logging = EmptyJsonObject),
    enforceStrictCapabilities: Boolean = true,
    configure: Server.() -> Unit = {},
): Server = Server(
    serverInfo = Implementation(name = "test-server", version = "1.2.3"),
    options = ServerOptions(capabilities = capabilities, enforceStrictCapabilities = enforceStrictCapabilities),
    instructions = "Test instructions.",
).apply {
    addTool(name = "echo", description = "Echoes its text argument.") { request ->
        CallToolResult(content = listOf(TextContent(request.arguments?.get("text")?.jsonPrimitive?.content ?: "")))
    }
    configure()
}

internal fun meta(
    capabilities: JsonObject = JsonObject(emptyMap()),
    version: String = ModernProtocol.VERSION,
    extra: Map<String, JsonElement> = emptyMap(),
): JsonObject = buildJsonObject {
    put(ModernMetaKeys.PROTOCOL_VERSION, version)
    put(ModernMetaKeys.CLIENT_CAPABILITIES, capabilities)
    put(ModernMetaKeys.CLIENT_INFO, buildJsonObject {
        put("name", "test-client")
        put("version", "1")
    })
    extra.forEach { (key, value) -> put(key, value) }
}

/** A modern request body. */
internal fun body(method: String, params: JsonObject = JsonObject(emptyMap()), meta: JsonObject? = meta(), id: JsonElement = JsonPrimitive(7)): String =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", if (meta == null) params else JsonObject(params + ("_meta" to meta)))
    }.toString()

internal fun toolCall(name: String, arguments: JsonObject = JsonObject(emptyMap()), meta: JsonObject = meta()): String =
    body("tools/call", buildJsonObject {
        put("name", name)
        put("arguments", arguments)
    }, meta)

/** The headers a correct HTTP client would send for [text]. */
internal fun headersFor(text: String): ModernHeaders {
    val json = Json.parseToJsonElement(text).jsonObject
    val method = json["method"]!!.jsonPrimitive.content
    val params = json["params"]?.jsonObject
    val name = params?.get("name") ?: params?.get("uri")
    val version = params?.get("_meta")?.jsonObject?.get(ModernMetaKeys.PROTOCOL_VERSION)
    return ModernHeaders(
        method = method,
        name = name?.takeIf { method == "tools/call" || method == "prompts/get" || method == "resources/read" }?.jsonPrimitive?.content,
        protocolVersion = version?.jsonPrimitive?.content,
    )
}

internal val ModernReply.response: ModernReply.Response get() = this as ModernReply.Response

internal val ModernReply.Response.result: JsonObject get() = message["result"]!!.jsonObject

internal val ModernReply.Response.error: JsonObject get() = message["error"]!!.jsonObject

internal val ModernReply.Response.code: Int get() = error["code"]!!.jsonPrimitive.int
