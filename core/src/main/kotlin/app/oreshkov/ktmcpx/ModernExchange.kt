package app.oreshkov.ktmcpx

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The standard headers of a modern HTTP POST, as the transport received them. `null` values mean
 * the header was absent. Transports without headers (stdio) pass no [ModernHeaders] at all, and
 * then no header rule applies.
 *
 * [params] are the `Mcp-Param-*` custom headers, keyed by the name after the prefix (`Region` for
 * `Mcp-Param-Region`). Keys are matched case-insensitively. A header sent on several lines is one entry,
 * its values joined with `", "`.
 */
public class ModernHeaders(
    public val method: String?,
    public val name: String?,
    public val protocolVersion: String?,
    public val params: Map<String, String> = emptyMap(),
) {
    internal val paramsByLowercaseName: Map<String, String> = params.mapKeys { it.key.lowercase() }

    override fun toString(): String =
        "ModernHeaders(method=$method, name=$name, protocolVersion=$protocolVersion, params=$params)"
}

/** What a transport sends back for one modern message. */
public sealed interface ModernReply {
    /**
     * A JSON-RPC response or error. [httpStatus] is the status the 2026-07-28 Streamable HTTP binding
     * requires for it (200, 400 or 404); other transports ignore it.
     */
    public class Response(public val message: JsonObject, public val httpStatus: Int) : ModernReply {
        /** The JSON-RPC error code, or `null` for a result. */
        public val errorCode: Int?
            get() = ((message["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull

        override fun toString(): String = "Response(httpStatus=$httpStatus, message=$message)"
    }

    /** The message was a notification: HTTP 202 with no body, nothing on stdio. */
    public data object Accepted : ModernReply
}
