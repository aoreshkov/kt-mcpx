package app.oreshkov.ktmcpx.internal

import app.oreshkov.ktmcpx.CachePolicy
import app.oreshkov.ktmcpx.ModernErrorCodes
import app.oreshkov.ktmcpx.ModernMetaKeys
import app.oreshkov.ktmcpx.ModernProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The SDK's resource-not-found code; 2026-07-28 retired it in favour of -32602 (SEP-2164). */
private const val LEGACY_RESOURCE_NOT_FOUND = -32_002

/** The SDK's URL-elicitation-required code; 2026-07-28 retired it (MRTR replaces it). */
private const val LEGACY_URL_ELICITATION_REQUIRED = -32_042

/**
 * The `server/discover` result, built from the synthetic `initialize` result.
 *
 * The capabilities say only what the bridge serves: the legacy `tasks` key is gone from the
 * 2026-07-28 schema. `listChanged` and `subscribe` stay as the server declares them, because
 * `subscriptions/listen` delivers those notifications.
 */
internal fun discoverResult(initializeResult: JsonObject, cache: CachePolicy): JsonObject {
    val capabilities = (initializeResult.obj("capabilities") ?: EMPTY_OBJECT).without("tasks")
    return buildJsonObject {
        put("resultType", "complete")
        put("supportedVersions", buildJsonArray { ModernProtocol.SUPPORTED_VERSIONS.forEach { add(JsonPrimitive(it)) } })
        put("capabilities", capabilities)
        initializeResult["instructions"]?.let { put("instructions", it) }
        put("ttlMs", cache.ttlMs)
        put("cacheScope", cache.scope.wireName)
        put("_meta", buildJsonObject { serverInfo(initializeResult)?.let { put(ModernMetaKeys.SERVER_INFO, it) } })
    }
}

internal fun serverInfo(initializeResult: JsonObject): JsonObject? = initializeResult.obj("serverInfo")

/**
 * A forwarded method's SDK result in the modern envelope: `resultType`, the caching hints when the
 * method is cacheable, server info in `_meta`, and the fields the 2026-07-28 schema removed taken out.
 */
internal fun modernResult(method: String, result: JsonObject, serverInfo: JsonObject?, cache: CachePolicy?): JsonObject {
    var out = result
    if (method == "tools/list") out = out.withoutToolExecution()
    out = out.with("resultType", JsonPrimitive("complete"))
    if (cache != null) {
        out = out.with("ttlMs", JsonPrimitive(cache.ttlMs)).with("cacheScope", JsonPrimitive(cache.scope.wireName))
    }
    if (serverInfo != null) {
        val meta = (out.obj("_meta") ?: EMPTY_OBJECT).with(ModernMetaKeys.SERVER_INFO, serverInfo)
        out = out.with("_meta", meta)
    }
    return out
}

/** `Tool.execution` (`taskSupport`) is gone from 2026-07-28; task support moves to the Tasks extension. */
private fun JsonObject.withoutToolExecution(): JsonObject {
    val tools = this["tools"] as? JsonArray ?: return this
    return with("tools", JsonArray(tools.map { tool -> (tool as? JsonObject)?.without("execution") ?: tool }))
}

internal const val ELICITATION_METHOD: String = "elicitation/create"

/** An `InputRequiredResult` (SEP-2322): the questions by key, and the state the client must echo. */
internal fun inputRequiredResult(inputRequests: Map<String, JsonObject>, requestState: String, serverInfo: JsonObject?): JsonObject =
    buildJsonObject {
        put("resultType", "input_required")
        put("inputRequests", JsonObject(inputRequests))
        put("requestState", requestState)
        if (serverInfo != null) put("_meta", buildJsonObject { put(ModernMetaKeys.SERVER_INFO, serverInfo) })
    }

/**
 * The URL elicitations of the SDK's -32042 error (`data.elicitations`, each an `ElicitRequestURLParams`),
 * or `null` when [error] is something else or carries none.
 */
internal fun urlElicitationsIn(error: JsonObject): List<JsonObject>? {
    if ((error["code"] as? JsonPrimitive)?.intOrNull != LEGACY_URL_ELICITATION_REQUIRED) return null
    val elicitations = (error.obj("data")?.get("elicitations") as? JsonArray)?.filterIsInstance<JsonObject>()
    return elicitations?.takeIf { it.isNotEmpty() }
}

/** An SDK error object with the codes 2026-07-28 retired replaced. */
internal fun modernError(error: JsonObject): JsonObject {
    val code = (error["code"] as? JsonPrimitive)?.intOrNull
    return when (code) {
        LEGACY_RESOURCE_NOT_FOUND -> error.with("code", JsonPrimitive(ModernErrorCodes.INVALID_PARAMS))
        LEGACY_URL_ELICITATION_REQUIRED -> errorObject(
            ModernErrorCodes.INTERNAL_ERROR,
            "The handler needs a URL elicitation, which a 2026-07-28 client can only be given on tools/call, prompts/get or resources/read",
        )
        else -> error
    }
}

internal fun errorObject(code: Int, message: String, data: JsonElement? = null): JsonObject = buildJsonObject {
    put("code", code)
    put("message", message)
    if (data != null) put("data", data)
}
