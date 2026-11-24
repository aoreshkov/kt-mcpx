package app.oreshkov.ktmcpx.internal

import app.oreshkov.ktmcpx.ModernErrorCodes
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal val EMPTY_OBJECT: JsonObject = JsonObject(emptyMap())

internal fun JSONRPCMessage.toJsonObject(): JsonObject =
    McpJson.encodeToJsonElement(JSONRPCMessage.serializer(), this).jsonObject

internal fun JsonObject.toRpcMessage(): JSONRPCMessage = McpJson.decodeFromJsonElement(JSONRPCMessage.serializer(), this)

/** The string value of [key], or `null` when it is absent or not a string. */
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))

internal fun JsonObject.without(vararg keys: String): JsonObject = JsonObject(this - keys.toSet())

internal fun rpcResult(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", result)
}

internal fun rpcError(id: JsonElement?, code: Int, message: String, data: JsonElement? = null): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id ?: JsonNull)
    put(
        "error",
        buildJsonObject {
            put("code", code)
            put("message", message)
            if (data != null) put("data", data)
        },
    )
}

/** The HTTP status the 2026-07-28 Streamable HTTP binding pairs with a JSON-RPC error [code]. */
internal fun httpStatusFor(code: Int?): Int = when (code) {
    null -> 200
    ModernErrorCodes.METHOD_NOT_FOUND -> 404
    ModernErrorCodes.PARSE_ERROR,
    ModernErrorCodes.INVALID_REQUEST,
    ModernErrorCodes.INVALID_PARAMS,
    ModernErrorCodes.HEADER_MISMATCH,
    ModernErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY,
    ModernErrorCodes.UNSUPPORTED_PROTOCOL_VERSION,
    -> 400
    else -> 200
}
