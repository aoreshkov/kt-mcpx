package app.oreshkov.ktmcpx.testing

import app.oreshkov.ktmcpx.ModernErrorCodes
import app.oreshkov.ktmcpx.ModernHeaderNames
import app.oreshkov.ktmcpx.ModernMetaKeys
import app.oreshkov.ktmcpx.ModernParamHeaders
import app.oreshkov.ktmcpx.ModernProtocol
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A raw MCP 2026-07-28 client over a Ktor [HttpClient], for tests: it sends exactly what it is told,
 * including broken requests, and returns the HTTP status and every message of the answer.
 *
 * Works with any client, including `testApplication`'s. Its requests carry no `Host` header unless you
 * pass one in [extraHeaders]; the SDK's DNS-rebinding protection rejects such requests, so tests against
 * a localhost-protected route pass `mapOf("Host" to "localhost")`.
 */
public class ModernTestClient(
    private val http: HttpClient,
    private val url: String,
    public val clientCapabilities: JsonObject = JsonObject(emptyMap()),
    public val clientInfo: JsonObject? = DEFAULT_CLIENT_INFO,
    public val protocolVersion: String = ModernProtocol.VERSION,
    private val extraHeaders: Map<String, String> = emptyMap(),
) {
    private val ids = AtomicLong()
    private val toolSchemas = ConcurrentHashMap<String, JsonObject>()

    /** The `_meta` this client puts on every request unless told otherwise. */
    public fun defaultMeta(): JsonObject = buildJsonObject {
        put(ModernMetaKeys.PROTOCOL_VERSION, protocolVersion)
        put(ModernMetaKeys.CLIENT_CAPABILITIES, clientCapabilities)
        clientInfo?.let { put(ModernMetaKeys.CLIENT_INFO, it) }
    }

    /**
     * Sends one request and returns the raw answer.
     *
     * [params] go out as given, plus `_meta`: [meta] replaces the default `_meta` entirely, and
     * [withoutMeta] sends none. [headers] override the standard headers this client derives from the
     * body (`Mcp-Method`, `Mcp-Name`, `MCP-Protocol-Version`, `Accept`); a `null` value removes one.
     * [rawBody] sends that text instead of a built body.
     */
    public suspend fun send(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        id: JsonElement? = JsonPrimitive(ids.incrementAndGet()),
        meta: JsonObject? = null,
        withoutMeta: Boolean = false,
        headers: Map<String, String?> = emptyMap(),
        rawBody: String? = null,
    ): ModernResponse {
        val fullParams = when {
            withoutMeta -> params
            else -> JsonObject(params + ("_meta" to (meta ?: defaultMeta())))
        }
        val body = rawBody ?: buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("method", method)
            put("params", fullParams)
        }.toString()
        val standard = buildMap {
            put(ModernHeaderNames.METHOD, method)
            nameHeaderValue(method, params)?.let { put(ModernHeaderNames.NAME, it) }
            put(ModernHeaderNames.PROTOCOL_VERSION, (fullParams["_meta"] as? JsonObject)?.stringValue(ModernMetaKeys.PROTOCOL_VERSION) ?: protocolVersion)
            put(HttpHeaders.Accept, "application/json, text/event-stream")
        }
        val merged = (extraHeaders + standard).toMutableMap<String, String?>()
        headers.forEach { (name, value) ->
            merged.keys.filter { it.equals(name, ignoreCase = true) }.forEach(merged::remove)
            merged[name] = value
        }
        val response = http.post(url) {
            merged.forEach { (name, value) -> if (value != null) header(name, value) }
            setBody(TextContent(body, ContentType.Application.Json))
        }
        return ModernResponse(response.status.value, response.headers[HttpHeaders.ContentType], response.bodyAsText())
    }

    /** Sends [method] with the default `_meta` and returns its `result`, or throws [ModernRpcException]. */
    public suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
        send(method, params).resultOrThrow()

    /** `server/discover`. */
    public suspend fun discover(): JsonObject = request("server/discover")

    /** `tools/list`. The input schemas it returns are remembered for [paramHeadersFor]. */
    public suspend fun listTools(): JsonObject = request("tools/list").also(::rememberTools)

    /**
     * `tools/call` of [name] with [arguments], with the `Mcp-Param-*` headers the tool's input schema
     * asks for (SEP-2243). On -32020 it lists the tools again and retries once, as a client should when a
     * schema may have changed.
     */
    public suspend fun callTool(name: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject {
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        val first = send("tools/call", params, headers = paramHeadersFor(name, arguments))
        if (first.errorCode != ModernErrorCodes.HEADER_MISMATCH) return first.resultOrThrow()
        toolSchemas.remove(name)
        return send("tools/call", params, headers = paramHeadersFor(name, arguments)).resultOrThrow()
    }

    /**
     * The `Mcp-Param-*` headers a 2026-07-28 client sends for a `tools/call` of [name] with [arguments].
     * Lists the tools first (every page) when [name]'s input schema is not known yet; empty when the tool
     * is not listed or its annotations are invalid.
     */
    public suspend fun paramHeadersFor(name: String, arguments: JsonObject): Map<String, String> {
        if (!toolSchemas.containsKey(name)) refreshTools()
        val schema = toolSchemas[name] ?: return emptyMap()
        return ModernParamHeaders.headersFor(schema, arguments)
    }

    private suspend fun refreshTools() {
        var cursor: String? = null
        var pages = 0
        do {
            val params = cursor?.let { buildJsonObject { put("cursor", it) } } ?: JsonObject(emptyMap())
            val result = send("tools/list", params).result ?: return
            rememberTools(result)
            cursor = result.stringValue("nextCursor")
        } while (cursor != null && ++pages < MAX_TOOL_PAGES)
    }

    private fun rememberTools(result: JsonObject) {
        (result["tools"] as? JsonArray).orEmpty().forEach { tool ->
            val name = (tool as? JsonObject)?.stringValue("name") ?: return@forEach
            (tool["inputSchema"] as? JsonObject)?.let { toolSchemas[name] = it }
        }
    }

    public companion object {
        public val DEFAULT_CLIENT_INFO: JsonObject = buildJsonObject {
            put("name", "kt-mcpx-testing")
            put("version", "0")
        }

        /**
         * `Mcp-Name` for [method]: `params.name` or `params.uri`, Base64-wrapped when it is not plain
         * printable ASCII or looks like the sentinel itself.
         */
        public fun nameHeaderValue(method: String, params: JsonObject): String? {
            val field = when (method) {
                "tools/call", "prompts/get" -> "name"
                "resources/read" -> "uri"
                else -> return null
            }
            return params.stringValue(field)?.let(ModernParamHeaders::encode)
        }

        private const val MAX_TOOL_PAGES = 100
    }
}

/** A JSON-RPC error a [ModernTestClient] call got back. */
public class ModernRpcException(public val code: Int, message: String, public val data: JsonElement?) : Exception("$code: $message")

/**
 * One HTTP answer to a modern request: the [status], the [contentType], and the JSON-RPC [messages] in
 * it (one for a JSON answer, every event for an SSE stream).
 */
public class ModernResponse(public val status: Int, public val contentType: String?, public val text: String) {
    public val isEventStream: Boolean get() = contentType?.startsWith("text/event-stream") == true

    public val messages: List<JsonObject> by lazy {
        val lines = if (isEventStream) {
            text.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.toList()
        } else {
            listOf(text).filter { it.isNotBlank() }
        }
        lines.mapNotNull { line ->
            try {
                Json.parseToJsonElement(line) as? JsonObject
            } catch (_: SerializationException) {
                null
            }
        }
    }

    /** The response or error message: the last message with `result` or `error`. */
    public val final: JsonObject? get() = messages.lastOrNull { "result" in it || "error" in it }

    /** Notifications that came before the final message. */
    public val notifications: List<JsonObject> get() = messages.filter { "method" in it && "id" !in it }

    public val result: JsonObject? get() = final?.get("result") as? JsonObject

    public val error: JsonObject? get() = final?.get("error") as? JsonObject

    public val errorCode: Int? get() = (error?.get("code") as? JsonPrimitive)?.intOrNull

    public fun resultOrThrow(): JsonObject {
        result?.let { return it }
        val error = error ?: throw IllegalStateException("HTTP $status with no JSON-RPC result or error: $text")
        throw ModernRpcException(errorCode ?: 0, error.stringValue("message") ?: "", error["data"])
    }

    override fun toString(): String = "ModernResponse(status=$status, contentType=$contentType, text=$text)"
}

internal fun JsonObject.stringValue(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
