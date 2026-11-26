package app.oreshkov.ktmcpx.internal

import app.oreshkov.ktmcpx.ModernErrorCodes
import app.oreshkov.ktmcpx.ModernHeaderNames
import app.oreshkov.ktmcpx.ModernHeaders
import app.oreshkov.ktmcpx.ModernMetaKeys
import app.oreshkov.ktmcpx.ModernProtocol
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** The methods of the 2026-07-28 `ClientRequest` union that the bridge forwards to the SDK. */
internal val FORWARDED_METHODS: Set<String> = setOf(
    "tools/list",
    "tools/call",
    "prompts/list",
    "prompts/get",
    "resources/list",
    "resources/templates/list",
    "resources/read",
    "completion/complete",
)

internal const val DISCOVER_METHOD: String = "server/discover"

/** The methods whose `Mcp-Name` header mirrors a body field, and that field. */
private val NAME_FIELDS: Map<String, String> = mapOf(
    "tools/call" to "name",
    "prompts/get" to "name",
    "resources/read" to "uri",
)

/** The per-request client state a modern request carries in `params._meta`. */
internal class ModernMeta(
    val protocolVersion: String,
    val clientCapabilities: JsonObject,
    val clientInfo: JsonObject?,
    val logLevel: String?,
    /** Every other `_meta` key (progressToken, traceparent, …), forwarded to the SDK request. */
    val passThrough: JsonObject,
)

internal class ValidRequest(
    val id: JsonElement,
    val method: String,
    val params: JsonObject,
    val meta: ModernMeta,
    /** An MRTR retry's answers, by input request key; empty on a first round and on other methods. */
    val inputResponses: Map<String, JsonObject> = emptyMap(),
    /** An MRTR retry's `requestState`, unchecked; `null` on a first round and on other methods. */
    val requestState: JsonElement? = null,
) {
    /** Whether this is an MRTR retry, whose result must not be cached. */
    val isRetry: Boolean get() = inputResponses.isNotEmpty() || requestState != null
}

internal sealed interface Validation {
    class Valid(val request: ValidRequest) : Validation

    data object Notification : Validation

    class Rejected(val message: JsonObject) : Validation
}

private val STRICT_JSON = Json

/** The severities of `notifications/message`, lowest first. */
internal val LOG_LEVELS: List<String> = listOf("debug", "info", "notice", "warning", "error", "critical", "alert", "emergency")

/**
 * Checks one modern message, in the order the conformance suite pins down: JSON, JSON-RPC shape,
 * `Mcp-Method`, method, `_meta`, `MCP-Protocol-Version`, supported version, `Mcp-Name`, then the
 * `Mcp-Param-*` headers of a `tools/call` whose tool [toolInputSchema] knows.
 */
internal fun validate(
    text: String,
    headers: ModernHeaders?,
    toolInputSchema: (String) -> JsonObject? = { null },
): Validation {
    val element = try {
        STRICT_JSON.parseToJsonElement(text)
    } catch (_: SerializationException) {
        return reject(null, ModernErrorCodes.PARSE_ERROR, "Parse error: the body is not JSON")
    }
    if (element !is JsonObject) {
        return reject(null, ModernErrorCodes.INVALID_REQUEST, "Invalid request: expected a single JSON-RPC object")
    }
    val id = element["id"]
    val validId = id.takeIf { it.isValidId() }
    if (element.string("jsonrpc") != "2.0") {
        return reject(validId, ModernErrorCodes.INVALID_REQUEST, "Invalid request: jsonrpc must be \"2.0\"")
    }
    val method = element.string("method")
        ?: return reject(validId, ModernErrorCodes.INVALID_REQUEST, "Invalid request: a client may only send requests and notifications")
    if (id == null) return Validation.Notification
    if (validId == null) {
        return reject(null, ModernErrorCodes.INVALID_REQUEST, "Invalid request: id must be a string or a number")
    }
    val params = when (val raw = element["params"]) {
        null -> EMPTY_OBJECT
        is JsonObject -> raw
        else -> return reject(id, ModernErrorCodes.INVALID_PARAMS, "Invalid params: params must be an object")
    }

    if (headers != null && headers.method != method) {
        return reject(id, ModernErrorCodes.HEADER_MISMATCH, headerMismatch(ModernHeaderNames.METHOD,headers.method, method))
    }
    if (method != DISCOVER_METHOD && method != LISTEN_METHOD && method !in FORWARDED_METHODS) {
        return reject(id, ModernErrorCodes.METHOD_NOT_FOUND, "Method not found: $method")
    }

    val meta = when (val parsed = parseMeta(params)) {
        is MetaParse.Invalid -> return reject(id, ModernErrorCodes.INVALID_PARAMS, parsed.reason)
        is MetaParse.Ok -> parsed.meta
    }
    if (headers != null && headers.protocolVersion != meta.protocolVersion) {
        return reject(
            id,
            ModernErrorCodes.HEADER_MISMATCH,
            headerMismatch(ModernHeaderNames.PROTOCOL_VERSION,headers.protocolVersion, meta.protocolVersion),
        )
    }
    if (meta.protocolVersion !in ModernProtocol.SUPPORTED_VERSIONS) {
        return reject(
            id,
            ModernErrorCodes.UNSUPPORTED_PROTOCOL_VERSION,
            "Unsupported protocol version: ${meta.protocolVersion}",
            buildJsonObject {
                put("supported", buildJsonArray { ModernProtocol.SUPPORTED_VERSIONS.forEach { add(JsonPrimitive(it)) } })
                put("requested", meta.protocolVersion)
            },
        )
    }
    val nameField = NAME_FIELDS[method]
    if (headers != null && nameField != null) {
        val bodyValue = params.string(nameField)
        val headerValue = headers.name?.let(::decodeHeaderValue)
        if (headerValue == null || headerValue != bodyValue) {
            return reject(id, ModernErrorCodes.HEADER_MISMATCH, headerMismatch(ModernHeaderNames.NAME,headers.name, bodyValue))
        }
    }
    if (headers != null && method == "tools/call") {
        val schema = params.string("name")?.let(toolInputSchema)
        if (schema != null) {
            val arguments = params["arguments"] as? JsonObject ?: EMPTY_OBJECT
            paramHeaderMismatch(schema, arguments, headers)?.let { return reject(id, ModernErrorCodes.HEADER_MISMATCH, it) }
        }
    }
    if (method == LISTEN_METHOD && SubscriptionFilter.parse(params["notifications"]) == null) {
        return reject(
            id,
            ModernErrorCodes.INVALID_PARAMS,
            "Invalid params: notifications must be a subscription filter (boolean *ListChanged flags, resourceSubscriptions as an array of URIs)",
        )
    }
    if (method !in INPUT_REQUIRED_METHODS) return Validation.Valid(ValidRequest(id, method, params, meta))
    val responses = when (val parsed = parseInputResponses(params)) {
        is InputResponsesParse.Invalid -> return reject(id, ModernErrorCodes.INVALID_PARAMS, parsed.reason)
        is InputResponsesParse.Ok -> parsed.responses
    }
    return Validation.Valid(ValidRequest(id, method, params, meta, responses, params[REQUEST_STATE]))
}

private fun headerMismatch(header: String, headerValue: String?, bodyValue: String?): String =
    if (headerValue == null) {
        "Header mismatch: the $header header is required"
    } else {
        "Header mismatch: $header is '$headerValue' but the body says '${bodyValue ?: ""}'"
    }

private sealed interface MetaParse {
    class Ok(val meta: ModernMeta) : MetaParse

    class Invalid(val reason: String) : MetaParse
}

private fun parseMeta(params: JsonObject): MetaParse {
    val meta = params["_meta"] as? JsonObject
        ?: return MetaParse.Invalid("Invalid params: _meta with ${ModernMetaKeys.PROTOCOL_VERSION} and ${ModernMetaKeys.CLIENT_CAPABILITIES} is required")
    val version = meta.string(ModernMetaKeys.PROTOCOL_VERSION)
        ?: return MetaParse.Invalid("Invalid params: _meta must carry ${ModernMetaKeys.PROTOCOL_VERSION} as a string")
    val capabilities = meta[ModernMetaKeys.CLIENT_CAPABILITIES] as? JsonObject
        ?: return MetaParse.Invalid("Invalid params: _meta must carry ${ModernMetaKeys.CLIENT_CAPABILITIES} as an object")
    val clientInfo = when (val info = meta[ModernMetaKeys.CLIENT_INFO]) {
        null, JsonNull -> null
        is JsonObject -> info
        else -> return MetaParse.Invalid("Invalid params: ${ModernMetaKeys.CLIENT_INFO} must be an object")
    }
    val logLevel = when (val level = meta[ModernMetaKeys.LOG_LEVEL]) {
        null, JsonNull -> null
        else -> (level as? JsonPrimitive)?.takeIf { it.isString && it.content in LOG_LEVELS }?.content
            ?: return MetaParse.Invalid("Invalid params: ${ModernMetaKeys.LOG_LEVEL} must be one of ${LOG_LEVELS.joinToString()}")
    }
    val passThrough = JsonObject(
        meta.filterKeys {
            it != ModernMetaKeys.PROTOCOL_VERSION && it != ModernMetaKeys.CLIENT_CAPABILITIES &&
                it != ModernMetaKeys.CLIENT_INFO && it != ModernMetaKeys.LOG_LEVEL
        },
    )
    return MetaParse.Ok(ModernMeta(version, capabilities, clientInfo, logLevel, passThrough))
}

private val BASE64_SENTINEL = Regex("""^=\?base64\?(.*)\?=$""")

/** Padded standard Base64. `java.util.Base64` alone would accept a missing padding. */
private val STRICT_BASE64 = Regex("""^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$""")

/**
 * A header value with the `=?base64?…?=` sentinel decoded; `null` when the Base64 is broken (bad
 * characters or padding) or is not UTF-8. A value without both markers is literal.
 */
internal fun decodeHeaderValue(value: String): String? {
    val encoded = BASE64_SENTINEL.matchEntire(value)?.groupValues?.get(1) ?: return value
    if (!STRICT_BASE64.matches(encoded)) return null
    return try {
        Base64.getDecoder().decode(encoded).decodeToString(throwOnInvalidSequence = true)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: CharacterCodingException) {
        null
    }
}

private fun JsonElement?.isValidId(): Boolean =
    this is JsonPrimitive && this !is JsonNull && (isString || content.toDoubleOrNull() != null)

private fun reject(id: JsonElement?, code: Int, message: String, data: JsonElement? = null): Validation.Rejected =
    Validation.Rejected(rpcError(id, code, message, data))
