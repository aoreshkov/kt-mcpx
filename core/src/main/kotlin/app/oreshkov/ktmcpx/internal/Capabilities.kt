package app.oreshkov.ktmcpx.internal

import app.oreshkov.ktmcpx.ModernErrorCodes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A client capability a request needed, as the `requiredCapabilities` object of a -32021 error:
 * `{"sampling": {}}`, `{"elicitation": {"url": {}}}`, ….
 */
internal class RequiredCapability(val required: JsonObject, private val label: String) {
    fun error(): JsonObject = errorObject(
        ModernErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY,
        "Missing required client capability: $label",
        buildJsonObject { put("requiredCapabilities", required) },
    )

    /** Whether the client's declared capabilities lack this one. */
    fun isMissingFrom(clientCapabilities: JsonObject): Boolean = !covers(clientCapabilities, required)

    companion object {
        val SAMPLING = of("sampling")
        val SAMPLING_TOOLS = of("sampling", "tools")
        val ROOTS = of("roots")
        val ELICITATION = of("elicitation")
        val ELICITATION_URL = of("elicitation", "url")

        private fun of(capability: String, sub: String? = null): RequiredCapability {
            val inner = if (sub == null) EMPTY_OBJECT else buildJsonObject { put(sub, EMPTY_OBJECT) }
            return RequiredCapability(buildJsonObject { put(capability, inner) }, if (sub == null) capability else "$capability.$sub")
        }

        /** Whether [declared] has every key of [required], recursively. */
        private fun covers(declared: JsonObject, required: JsonObject): Boolean = required.all { (key, value) ->
            val have = declared[key] as? JsonObject ?: return@all false
            value !is JsonObject || covers(have, value)
        }
    }
}

/**
 * The client capability a server-to-client request needs. `elicitation: {}` with no mode keys means
 * form mode, for compatibility with clients older than 2025-11-25.
 */
internal fun capabilityFor(serverRequest: JsonObject): RequiredCapability? {
    val params = serverRequest.obj("params") ?: EMPTY_OBJECT
    return when (serverRequest.string("method")) {
        "sampling/createMessage" ->
            if (params["tools"] != null || params["toolChoice"] != null) RequiredCapability.SAMPLING_TOOLS else RequiredCapability.SAMPLING
        "roots/list" -> RequiredCapability.ROOTS
        "elicitation/create" -> if (params.string("mode") == "url") RequiredCapability.ELICITATION_URL else RequiredCapability.ELICITATION
        else -> null
    }
}

/**
 * The messages SDK 0.16.0 produces when a handler asks the client for something it did not declare,
 * with strict capabilities on (the `ServerOptions` default). The SDK raises them before anything
 * reaches the transport, and its tool wrapper turns them into an `isError` result, so the text is all
 * the bridge can see. `CapabilityMessagesTest` pins them; an SDK bump that rewords them fails there.
 */
private val SDK_CAPABILITY_MESSAGES: List<Pair<String, RequiredCapability>> = listOf(
    "Client does not support sampling (required for sampling/createMessage)" to RequiredCapability.SAMPLING,
    "Client does not support listing roots (required for roots/list)" to RequiredCapability.ROOTS,
    "Client does not support elicitation (required for elicitation/create)" to RequiredCapability.ELICITATION,
    "Client did not advertise elicitation.url capability; cannot send a URL-mode elicitation." to RequiredCapability.ELICITATION_URL,
    "Client did not advertise sampling.tools capability; cannot send tools/toolChoice in sampling/createMessage request." to
        RequiredCapability.SAMPLING_TOOLS,
)

/** The SDK's own message for a missing [capability]; for tests. */
internal fun sdkCapabilityMessage(capability: RequiredCapability): String =
    SDK_CAPABILITY_MESSAGES.first { it.second === capability }.first

/**
 * The capability an SDK answer says was missing, if the answer failed only because of that: a
 * `tools/call` `isError` result whose single text is the SDK wrapper's "Error executing tool …: <message>",
 * or a JSON-RPC error with that message (prompts, resources). `null` when nothing matches, or when the
 * client did declare the capability, so a handler's own error that happens to quote the text stays.
 */
internal fun missingCapabilityIn(answer: JsonObject, clientCapabilities: JsonObject): RequiredCapability? {
    val text = answer.obj("error")?.string("message")
        ?: answer.obj("result")?.takeIf { (it["isError"] as? JsonPrimitive)?.content == "true" }?.let(::singleText)
        ?: return null
    return SDK_CAPABILITY_MESSAGES.firstOrNull { (message, _) -> text == message || text.endsWith(": $message") }
        ?.second
        ?.takeIf { it.isMissingFrom(clientCapabilities) }
}

private fun singleText(result: JsonObject): String? {
    val content = result["content"] as? JsonArray ?: return null
    val only = content.singleOrNull() as? JsonObject ?: return null
    return only.takeIf { it.string("type") == "text" }?.string("text")
}
