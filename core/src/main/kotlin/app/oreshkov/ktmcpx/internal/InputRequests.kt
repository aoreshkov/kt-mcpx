package app.oreshkov.ktmcpx.internal

import app.oreshkov.ktmcpx.ModernInputRequests
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The methods on which a 2026-07-28 server may answer `input_required` (SEP-2322). */
internal val INPUT_REQUIRED_METHODS: Set<String> = setOf("tools/call", "prompts/get", "resources/read")

/** The params keys of an MRTR retry, which the SDK request never sees. */
internal const val INPUT_RESPONSES: String = "inputResponses"
internal const val REQUEST_STATE: String = "requestState"

/**
 * Names the server-to-client requests of one run, so that a replay of the same handler gets the same
 * keys. A request that carries [ModernInputRequests.KEY_META] in its `_meta` uses that key. Otherwise
 * the key is the method's short name and a digest of its params, with `-2`, `-3`, … for a second and
 * third identical request, so an answer can only ever reach the question it was given for.
 */
internal class InputKeys {
    private val seen = mutableMapOf<String, Int>()

    @Synchronized
    fun keyFor(serverRequest: JsonObject): String {
        val method = serverRequest.string("method").orEmpty()
        val params = serverRequest.obj("params") ?: EMPTY_OBJECT
        val base = params.obj("_meta")?.string(ModernInputRequests.KEY_META)
            ?: "${method.substringBefore('/')}-${digest(clientParams(params)).take(DEFAULT_KEY_DIGEST_LENGTH)}"
        val count = (seen[base] ?: 0) + 1
        seen[base] = count
        return if (count == 1) base else "$base-$count"
    }

    private companion object {
        const val DEFAULT_KEY_DIGEST_LENGTH = 12
    }
}

/**
 * A server-to-client request as a 2026-07-28 `InputRequest`: `{method, params}`, with the kt-mcpx key
 * hint taken out of `_meta`, and a URL elicitation's `elicitationId`, which 2026-07-28 removed.
 */
internal fun inputRequestOf(method: String, params: JsonObject): JsonObject = buildJsonObject {
    put("method", method)
    put("params", clientParams(params))
}

/** [params] as the client sees them: no `elicitationId`, no key hint, no empty `_meta`. */
private fun clientParams(params: JsonObject): JsonObject {
    var out = params.without("elicitationId")
    val meta = out.obj("_meta")?.without(ModernInputRequests.KEY_META)
    out = when {
        meta == null -> out
        meta.isEmpty() -> out.without("_meta")
        else -> out.with("_meta", meta)
    }
    return out
}

/** The answers a retry carries: `inputResponses`, each an object, or why they are malformed. */
internal sealed interface InputResponsesParse {
    class Ok(val responses: Map<String, JsonObject>) : InputResponsesParse

    class Invalid(val reason: String) : InputResponsesParse
}

internal fun parseInputResponses(params: JsonObject): InputResponsesParse {
    val raw = params[INPUT_RESPONSES] ?: return InputResponsesParse.Ok(emptyMap())
    val map = raw as? JsonObject
        ?: return InputResponsesParse.Invalid("Invalid params: $INPUT_RESPONSES must be an object keyed by input request id")
    val bad = map.entries.firstOrNull { it.value !is JsonObject }
    if (bad != null) return InputResponsesParse.Invalid("Invalid params: $INPUT_RESPONSES.${bad.key} must be an object")
    return InputResponsesParse.Ok(map.mapValues { it.value as JsonObject })
}

/**
 * Seals and opens `requestState`: the answers a handler has used so far, bound to the method, a digest
 * of the request's params and an expiry, and signed with HMAC-SHA256. `requestState` comes back from
 * the client, so nothing in it is trusted before the signature checks out.
 *
 * Format: `base64url(payload JSON) "." base64url(HMAC(payload))`. The payload is not secret: it holds
 * only what the client itself answered.
 */
internal class RequestStateCodec(secret: ByteArray?, private val ttlMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private val key = SecretKeySpec(secret ?: ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes), HMAC)

    fun seal(method: String, params: JsonObject, answers: Map<String, JsonObject>): String {
        val payload = buildJsonObject {
            put("v", 1)
            put("m", method)
            put("d", digest(requestParams(params)))
            put("e", clock() + ttlMs)
            put("a", JsonObject(answers))
        }.toString().encodeToByteArray()
        return "${B64.encodeToString(payload)}.${B64.encodeToString(mac(payload))}"
    }

    /** The answers sealed in [state], or why it is rejected. */
    fun open(state: JsonElement, method: String, params: JsonObject): Opened {
        val text = (state as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return Opened.Rejected("Invalid params: $REQUEST_STATE must be a string")
        val parts = text.split('.')
        val payload = parts.getOrNull(0)?.let(::decode)
        val signature = parts.getOrNull(1)?.let(::decode)
        if (parts.size != 2 || payload == null || signature == null || !MessageDigest.isEqual(mac(payload), signature)) {
            return Opened.Rejected("Invalid params: $REQUEST_STATE failed its integrity check")
        }
        val sealed = try {
            Json.parseToJsonElement(payload.decodeToString()) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return Opened.Rejected("Invalid params: $REQUEST_STATE failed its integrity check")
        val expires = (sealed["e"] as? JsonPrimitive)?.longOrNull ?: 0
        return when {
            expires < clock() -> Opened.Rejected("Invalid params: $REQUEST_STATE has expired; send the request again without it")
            sealed.string("m") != method || sealed.string("d") != digest(requestParams(params)) ->
                Opened.Rejected("Invalid params: $REQUEST_STATE belongs to a different request")
            else -> Opened.Ok((sealed.obj("a") ?: EMPTY_OBJECT).mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }.toMap())
        }
    }

    sealed interface Opened {
        class Ok(val answers: Map<String, JsonObject>) : Opened

        /** Answered with -32602 and [message]. */
        class Rejected(val message: String) : Opened
    }

    private fun mac(bytes: ByteArray): ByteArray = Mac.getInstance(HMAC).apply { init(key) }.doFinal(bytes)

    private fun decode(text: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val HMAC = "HmacSHA256"
        const val SECRET_BYTES = 32
        val B64: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}

/** The params that identify a request across MRTR rounds: everything but `_meta` and the retry fields. */
internal fun requestParams(params: JsonObject): JsonObject = params.without("_meta", INPUT_RESPONSES, REQUEST_STATE)

/** SHA-256 of [value]'s canonical JSON (object keys sorted), as lowercase hex. */
internal fun digest(value: JsonElement): String =
    MessageDigest.getInstance("SHA-256").digest(canonical(value).toString().encodeToByteArray())
        .joinToString("") { "%02x".format(it) }

private fun canonical(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
    is JsonArray -> JsonArray(value.map(::canonical))
    is JsonPrimitive -> value
}
