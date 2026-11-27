package app.oreshkov.ktmcpx.testing

import app.oreshkov.ktmcpx.ModernErrorCodes
import app.oreshkov.ktmcpx.ModernHeaderNames
import app.oreshkov.ktmcpx.ModernMetaKeys
import app.oreshkov.ktmcpx.ModernParamHeaders
import app.oreshkov.ktmcpx.ModernProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/** A tool the contract kit calls, with arguments that make it succeed. */
public class ToolProbe(public val name: String, public val arguments: JsonObject = JsonObject(emptyMap()))

/** One contract check and how it went. */
public class ContractCheck(public val name: String, public val passed: Boolean, public val detail: String) {
    override fun toString(): String = (if (passed) "PASS " else "FAIL ") + "$name: $detail"
}

/** Every check of one [ModernContract.verify] run. */
public class ContractReport(public val checks: List<ContractCheck>) {
    public val failures: List<ContractCheck> get() = checks.filterNot { it.passed }

    /** Throws an [AssertionError] listing every failed check. */
    public fun assertPassed() {
        if (failures.isNotEmpty()) {
            throw AssertionError("${failures.size} of ${checks.size} contract checks failed:\n" + failures.joinToString("\n"))
        }
    }

    override fun toString(): String = checks.joinToString("\n")
}

/**
 * The 2026-07-28 contract of one modern endpoint, as the bridge implements it: `server/discover`,
 * `tools/list` and `tools/call` in the modern envelope, every request-level error row of the 2026-07-28 HTTP binding
 * with its HTTP status, the SEP-2243 `x-mcp-header` rules when a listed tool uses them, and no
 * notification on any answer that its request did not ask for. Run it against your own routes:
 *
 * ```kotlin
 * ModernContract.verify(ModernTestClient(client, "/mcp", extraHeaders = mapOf("Host" to "localhost")),
 *     ToolProbe("echo", buildJsonObject { put("text", "hi") })).assertPassed()
 * ```
 *
 * On stdio ([withModernStdioClient]) the same checks run, except those about headers and HTTP statuses.
 */
public object ModernContract {
    /** Runs every check against [client]'s endpoint; [probe] adds a successful `tools/call`. */
    public suspend fun verify(client: ModernTestClient, probe: ToolProbe? = null): ContractReport {
        val answers = mutableListOf<ModernResponse>()
        val target = ModernTestClient(RecordingTransport(client.transport, answers), client.clientCapabilities, client.clientInfo, client.protocolVersion)
        val isHttp = client.transport.isHttp
        fun http(status: Int) = if (isHttp) ", HTTP $status" else ""
        val checks = mutableListOf<ContractCheck>()
        suspend fun check(name: String, block: suspend () -> String?) {
            val failure = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "threw ${e::class.simpleName}: ${e.message}"
            }
            checks += ContractCheck(name, failure == null, failure ?: "ok")
        }

        var capabilities: JsonObject? = null
        check("server/discover answers in the modern envelope") {
            val response = target.send("server/discover")
            val result = response.result ?: return@check "expected a result, got HTTP ${response.status}: ${response.text}"
            capabilities = result["capabilities"] as? JsonObject
            listOfNotNull(
                expectStatus(response, 200),
                expectComplete(result),
                expectCacheHints(result),
                expectServerInfo(result),
                "capabilities is not an object".takeIf { capabilities == null },
                "supportedVersions does not list ${ModernProtocol.VERSION}"
                    .takeIf { (result["supportedVersions"] as? JsonArray)?.contains(JsonPrimitive(ModernProtocol.VERSION)) != true },
                "the legacy tasks capability is still advertised".takeIf { capabilities?.containsKey("tasks") == true },
            ).firstOrNull()
        }

        val advertisesTools = capabilities?.get("tools") is JsonObject
        var firstTool: String? = null
        var listedTools: List<JsonObject> = emptyList()
        check("tools/list matches the tools capability") {
            val response = target.send("tools/list")
            if (!advertisesTools) return@check expectError(response, ModernErrorCodes.METHOD_NOT_FOUND, 404)
            val result = response.result ?: return@check "expected a result, got HTTP ${response.status}: ${response.text}"
            val tools = result["tools"] as? JsonArray ?: return@check "tools is not an array"
            listedTools = tools.filterIsInstance<JsonObject>()
            firstTool = listedTools.firstOrNull()?.stringValue("name")
            listOfNotNull(
                expectStatus(response, 200),
                expectComplete(result),
                expectCacheHints(result),
                expectServerInfo(result),
                "a tool still carries the removed execution field".takeIf { tools.any { (it as? JsonObject)?.containsKey("execution") == true } },
            ).firstOrNull()
        }
        if (advertisesTools) {
            check("tools/list keeps its order across requests") {
                val first = toolNames(target.send("tools/list"))
                val second = toolNames(target.send("tools/list"))
                "the order changed: $first then $second".takeIf { first != second }
            }
        }
        if (listedTools.isNotEmpty()) {
            check("every x-mcp-header annotation is valid, or clients drop the tool") {
                listedTools.flatMap { tool ->
                    val schema = tool["inputSchema"] as? JsonObject ?: return@flatMap emptyList()
                    ModernParamHeaders.problems(schema).map { "${tool.stringValue("name")}: $it" }
                }.takeIf { it.isNotEmpty() }?.joinToString("; ")
            }
        }
        if (probe != null) {
            check("tools/call of ${probe.name} answers in the modern envelope") {
                val headers = target.paramHeadersFor(probe.name, probe.arguments)
                val response = target.send("tools/call", toolCall(probe.name, probe.arguments), headers = headers)
                val result = response.result ?: return@check "expected a result, got HTTP ${response.status}: ${response.text}"
                listOfNotNull(expectStatus(response, 200), expectComplete(result), expectServerInfo(result)).firstOrNull()
            }
        }

        check("a request without _meta is -32602${http(400)}") {
            expectError(target.send("server/discover", withoutMeta = true, id = JsonPrimitive(101)), ModernErrorCodes.INVALID_PARAMS, 400, JsonPrimitive(101))
        }
        check("_meta without the protocol version is -32602${http(400)}") {
            val meta = JsonObject(target.defaultMeta() - ModernMetaKeys.PROTOCOL_VERSION)
            expectError(target.send("server/discover", meta = meta), ModernErrorCodes.INVALID_PARAMS, 400)
        }
        check("_meta without client capabilities is -32602${http(400)}") {
            val meta = JsonObject(target.defaultMeta() - ModernMetaKeys.CLIENT_CAPABILITIES)
            expectError(target.send("server/discover", meta = meta), ModernErrorCodes.INVALID_PARAMS, 400)
        }
        check("_meta without client info is served") {
            val meta = JsonObject(target.defaultMeta() - ModernMetaKeys.CLIENT_INFO)
            val response = target.send("server/discover", meta = meta)
            expectStatus(response, 200) ?: "expected a result: ${response.text}".takeIf { response.result == null }
        }
        check("an unsupported protocol version is -32022 with supported and requested${http(400)}") {
            val meta = JsonObject(target.defaultMeta() + (ModernMetaKeys.PROTOCOL_VERSION to JsonPrimitive("v999.0.0")))
            val response = target.send("server/discover", meta = meta)
            expectError(response, ModernErrorCodes.UNSUPPORTED_PROTOCOL_VERSION, 400) ?: run {
                val data = response.error?.get("data") as? JsonObject
                when {
                    data?.stringValue("requested") != "v999.0.0" -> "data.requested does not echo the version: $data"
                    (data["supported"] as? JsonArray)?.contains(JsonPrimitive(ModernProtocol.VERSION)) != true -> "data.supported is wrong: $data"
                    else -> null
                }
            }
        }
        if (isHttp) {
            headerChecks(target, probe?.name ?: firstTool ?: "kt-mcpx-contract-probe", listedTools, ::check)
        }
        for (removed in listOf("ping", "logging/setLevel", "resources/subscribe", "resources/unsubscribe", "tasks/list", "tasks/result")) {
            check("the removed method $removed is -32601${http(404)}") {
                expectError(target.send(removed), ModernErrorCodes.METHOD_NOT_FOUND, 404)
            }
        }
        check("an unknown method is -32601${http(404)}") {
            expectError(target.send("unknown/method", id = JsonPrimitive("unknown-1")), ModernErrorCodes.METHOD_NOT_FOUND, 404, JsonPrimitive("unknown-1"))
        }
        if (isHttp) {
            check("a notification is HTTP 202 with no body") {
                val response = target.send("notifications/cancelled", id = null)
                listOfNotNull(expectStatus(response, 202), "the body is not empty: ${response.text}".takeIf { response.text.isNotBlank() }).firstOrNull()
            }
        } else {
            check("a notification gets no answer") {
                target.send("notifications/cancelled", id = null)
                val next = target.send("server/discover")
                "something came before the next response: ${next.text}".takeIf { next.messages.size != 1 }
            }
        }
        check("a body that is not JSON is -32700${http(400)}") {
            expectError(target.send("tools/list", rawBody = "{not json"), ModernErrorCodes.PARSE_ERROR, 400)
        }
        check("a body that is not JSON-RPC is -32600${http(400)}") {
            val body = buildJsonObject {
                put("jsonrpc", "1.0")
                put("id", 1)
                put("method", "tools/list")
                put("params", buildJsonObject { put("_meta", target.defaultMeta()) })
            }
            expectError(target.send("tools/list", rawBody = body.toString()), ModernErrorCodes.INVALID_REQUEST, 400)
        }

        val notifications = answers.flatMap { it.notifications }
        check("no answer carries a log message its request did not ask for") {
            // The contract never sets a log level, so a request-scoped log message must not reach any answer.
            notifications.filter { it.stringValue("method") == "notifications/message" }
                .takeIf { it.isNotEmpty() }?.let { "got $it" }
        }
        check("no answer carries a list_changed or resources/updated notification") {
            notifications.filter { it.stringValue("method")?.let { m -> m.endsWith("/list_changed") || m == "notifications/resources/updated" } == true }
                .takeIf { it.isNotEmpty() }?.let { "got $it; only subscriptions/listen streams carry those" }
        }
        return ContractReport(checks)
    }

    /** The checks of the `Mcp-*` request headers, which exist only over HTTP. */
    private suspend fun headerChecks(
        target: ModernTestClient,
        toolName: String,
        listedTools: List<JsonObject>,
        check: suspend (String, suspend () -> String?) -> Unit,
    ) {
        check("a protocol version header that disagrees with _meta is -32020, HTTP 400") {
            val meta = JsonObject(target.defaultMeta() + (ModernMetaKeys.PROTOCOL_VERSION to JsonPrimitive("v999.0.0")))
            val response = target.send("server/discover", meta = meta, headers = mapOf(ModernHeaderNames.PROTOCOL_VERSION to ModernProtocol.VERSION))
            expectError(response, ModernErrorCodes.HEADER_MISMATCH, 400)
        }
        check("an Mcp-Method header that disagrees with the body is -32020, HTTP 400") {
            expectError(target.send("tools/list", headers = mapOf(ModernHeaderNames.METHOD to "TOOLS/LIST")), ModernErrorCodes.HEADER_MISMATCH, 400)
        }
        check("tools/call without Mcp-Name is -32020, HTTP 400") {
            val response = target.send("tools/call", toolCall(toolName), headers = mapOf(ModernHeaderNames.NAME to null))
            expectError(response, ModernErrorCodes.HEADER_MISMATCH, 400)
        }
        check("an Mcp-Name header that disagrees with the body is -32020, HTTP 400") {
            val response = target.send("tools/call", toolCall(toolName), headers = mapOf(ModernHeaderNames.NAME to "$toolName-other"))
            expectError(response, ModernErrorCodes.HEADER_MISMATCH, 400)
        }
        paramHeaderTarget(listedTools)?.let { (tool, property, header) ->
            val arguments = buildJsonObject { put(property, PARAM_PROBE_VALUE) }
            val call = toolCall(tool, arguments)
            check("tools/call of $tool without $header is -32020, HTTP 400") {
                expectError(target.send("tools/call", call), ModernErrorCodes.HEADER_MISMATCH, 400)
            }
            check("a $header header that disagrees with the arguments is -32020, HTTP 400") {
                expectError(target.send("tools/call", call, headers = mapOf(header to "$PARAM_PROBE_VALUE-other")), ModernErrorCodes.HEADER_MISMATCH, 400)
            }
            check("a $header header with broken Base64 is -32020, HTTP 400") {
                expectError(target.send("tools/call", call, headers = mapOf(header to "=?base64?a3Q?=")), ModernErrorCodes.HEADER_MISMATCH, 400)
            }
        }
    }

    private fun toolCall(name: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject = buildJsonObject {
        put("name", name)
        put("arguments", arguments)
    }

    private const val PARAM_PROBE_VALUE = "kt-mcpx-probe"

    /**
     * A listed tool with valid annotations and a top-level string property mirrored into a header: its
     * name, the property and the header. The checks that use it are all rejected before the tool runs.
     */
    private fun paramHeaderTarget(tools: List<JsonObject>): Triple<String, String, String>? {
        for (tool in tools) {
            val name = tool.stringValue("name") ?: continue
            val schema = tool["inputSchema"] as? JsonObject ?: continue
            if (ModernParamHeaders.problems(schema).isNotEmpty()) continue
            val properties = schema["properties"] as? JsonObject ?: continue
            for ((property, propertySchema) in properties) {
                val definition = propertySchema as? JsonObject ?: continue
                val header = definition.stringValue(ModernParamHeaders.ANNOTATION) ?: continue
                if (definition.stringValue("type") == "string") return Triple(name, property, ModernHeaderNames.PARAM_PREFIX + header)
            }
        }
        return null
    }

    private fun toolNames(response: ModernResponse): List<String?> =
        (response.result?.get("tools") as? JsonArray).orEmpty().map { (it as? JsonObject)?.stringValue("name") }

    // A status of 0 is an answer with no HTTP status (stdio): only its messages are checked.
    private fun expectStatus(response: ModernResponse, status: Int): String? =
        "expected HTTP $status, got ${response.status}: ${response.text}".takeIf { response.status != 0 && response.status != status }

    private fun expectComplete(result: JsonObject): String? =
        "resultType is not \"complete\": ${result["resultType"]}".takeIf { result.stringValue("resultType") != "complete" }

    private fun expectCacheHints(result: JsonObject): String? {
        val ttl = (result["ttlMs"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        return when {
            ttl == null || ttl < 0 -> "ttlMs is not a non-negative integer: ${result["ttlMs"]}"
            result.stringValue("cacheScope") !in setOf("public", "private") -> "cacheScope is not public or private: ${result["cacheScope"]}"
            else -> null
        }
    }

    private fun expectServerInfo(result: JsonObject): String? {
        val info = (result["_meta"] as? JsonObject)?.get(ModernMetaKeys.SERVER_INFO) as? JsonObject
        return "no name and version in _meta[\"${ModernMetaKeys.SERVER_INFO}\"]"
            .takeIf { info?.stringValue("name") == null || info.stringValue("version") == null }
    }

    private fun expectError(response: ModernResponse, code: Int, status: Int, id: JsonElement? = null): String? = when {
        response.errorCode != code -> "expected error $code, got HTTP ${response.status}: ${response.text}"
        response.status != 0 && response.status != status -> "expected HTTP $status for error $code, got ${response.status}"
        id != null && response.final?.get("id") != id -> "the error does not carry the request id $id: ${response.text}"
        else -> null
    }

    /** Keeps every answer, so the contract can check what came with them. */
    private class RecordingTransport(private val inner: ModernTestTransport, private val answers: MutableList<ModernResponse>) : ModernTestTransport {
        override val isHttp: Boolean get() = inner.isHttp

        override suspend fun send(body: String, headers: Map<String, String?>): ModernResponse =
            inner.send(body, headers).also { synchronized(answers) { answers += it } }
    }
}
