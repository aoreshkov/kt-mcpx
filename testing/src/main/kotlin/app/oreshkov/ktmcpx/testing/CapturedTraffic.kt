package app.oreshkov.ktmcpx.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** One HTTP request a real client sent, as captured (credential headers removed, header names lowercase). */
public class CapturedRequest(
    public val method: String,
    public val path: String,
    public val headers: Map<String, String>,
    public val body: JsonElement,
)

/** The answer the captured server gave. */
public class CapturedResponse(public val status: Int, public val headers: Map<String, String>, public val body: JsonElement)

/** One request and its answer, in capture order ([seq] from 1). */
public class CapturedExchange(public val seq: Int, public val request: CapturedRequest, public val response: CapturedResponse) {
    override fun toString(): String = "#$seq ${request.method} ${request.path} ${request.headers["mcp-method"] ?: ""} -> ${response.status}"
}

/**
 * Real client traffic, recorded through a logging reverse proxy and scrubbed, for routing and
 * compatibility tests. The responses are from the server that was captured (kt-mcp before kt-mcpx), so
 * tests replay the requests and judge the answers themselves.
 */
public object CapturedTraffic {
    /**
     * Claude Code 2.1.331 with its default negotiation: a modern `server/discover` probe, then the legacy
     * handshake after the server refused it (the server had no bridge yet).
     */
    public val CLAUDE_CODE_2_1_331_DEFAULT: List<CapturedExchange> by lazy { load("claude-code-2.1.331/default.json") }

    /**
     * Claude Code 2.1.331 with its default negotiation against kt-mcpx's fixture server: `server/discover`,
     * `tools/list` and `tools/call` (with `Mcp-Name`), all on the modern path. Its `tools/call` `_meta`
     * carries `progressToken` and `claudecode/toolUseId`, and no `extensions` in the client capabilities.
     */
    public val CLAUDE_CODE_2_1_331_MODERN: List<CapturedExchange> by lazy { load("claude-code-2.1.331/modern.json") }

    /** Claude Code 2.1.331 with `MCP_PROTOCOL_NEGOTIATION=legacy`: the legacy handshake only. */
    public val CLAUDE_CODE_2_1_331_LEGACY: List<CapturedExchange> by lazy { load("claude-code-2.1.331/legacy.json") }

    /** A capture under this module's `fixtures/` resources, by relative path. */
    public fun load(name: String): List<CapturedExchange> {
        val resource = "fixtures/$name"
        val text = CapturedTraffic::class.java.getResourceAsStream(resource)?.use { it.readBytes().decodeToString() }
            ?: throw IllegalArgumentException("No captured traffic named $name")
        return (Json.parseToJsonElement(text) as JsonArray).map { exchange(it as JsonObject) }
    }

    private fun exchange(json: JsonObject): CapturedExchange {
        val request = json["request"] as JsonObject
        val response = json["response"] as JsonObject
        return CapturedExchange(
            seq = (json["seq"] as JsonPrimitive).intOrNull ?: 0,
            request = CapturedRequest(
                method = request.stringValue("method") ?: "",
                path = request.stringValue("path") ?: "",
                headers = headers(request["headers"]),
                body = request["body"] ?: JsonNull,
            ),
            response = CapturedResponse(
                status = (response["status"] as JsonPrimitive).intOrNull ?: 0,
                headers = headers(response["headers"]),
                body = response["body"] ?: JsonNull,
            ),
        )
    }

    private fun headers(json: JsonElement?): Map<String, String> =
        (json as? JsonObject).orEmpty().mapValues { (_, value) -> (value as? JsonPrimitive)?.content ?: value.toString() }
}
