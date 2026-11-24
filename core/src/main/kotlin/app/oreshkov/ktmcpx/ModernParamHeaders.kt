package app.oreshkov.ktmcpx

import app.oreshkov.ktmcpx.internal.ParamScan
import app.oreshkov.ktmcpx.internal.scanParamHeaders
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/**
 * SEP-2243 custom headers. A tool input-schema property with `"x-mcp-header": "Region"` asks a
 * 2026-07-28 client to mirror that `tools/call` argument into an `Mcp-Param-Region` header. The bridge
 * rejects a call whose headers disagree with its arguments, with -32020 and HTTP 400.
 *
 * An annotation is valid on a `string`, `integer` or `boolean` property reached from the schema root
 * through `properties` alone. Its value must be an HTTP token, unique in the schema regardless of case.
 * Clients drop a tool with any invalid annotation from `tools/list`, so the bridge does not enforce the
 * headers of such a tool; [problems] says what is wrong with one.
 *
 * Adopters only add the annotation to their SDK schemas:
 *
 * ```kotlin
 * server.addTool(name = "deploy", description = "…", inputSchema = ToolSchema(
 *     properties = buildJsonObject {
 *         put("region", buildJsonObject { put("type", "string"); put("x-mcp-header", "Region") })
 *     },
 * )) { request -> … }
 * ```
 */
public object ModernParamHeaders {
    /** The schema keyword that names the header. */
    public const val ANNOTATION: String = "x-mcp-header"

    /** Why [inputSchema]'s annotations make the tool invalid for a client. Empty when they are fine. */
    public fun problems(inputSchema: JsonObject): List<String> = scanParamHeaders(inputSchema).problems

    /**
     * The `Mcp-Param-*` headers a client sends for a `tools/call` with [arguments], with values encoded
     * by [encode]. An argument that is absent or `null` gets no header. Empty when [inputSchema] has
     * invalid annotations.
     */
    public fun headersFor(inputSchema: JsonObject, arguments: JsonObject): Map<String, String> {
        val scan: ParamScan = scanParamHeaders(inputSchema)
        if (scan.problems.isNotEmpty()) return emptyMap()
        return buildMap {
            for (annotation in scan.annotations) {
                val value = annotation.valueIn(arguments) as? JsonPrimitive ?: continue
                if (value is JsonNull) continue
                put(ModernHeaderNames.PARAM_PREFIX + annotation.header, encode(annotation.render(value)))
            }
        }
    }

    /**
     * A header value as a client sends it: as is when it is printable ASCII without leading or trailing
     * spaces, and `=?base64?…?=` (UTF-8) otherwise, or when it looks like that wrapping itself.
     */
    public fun encode(value: String): String {
        val plain = value.all { it in ' '..'~' } &&
            !value.startsWith(' ') && !value.endsWith(' ') &&
            !(value.startsWith(BASE64_PREFIX) && value.endsWith(BASE64_SUFFIX))
        return if (plain) value else BASE64_PREFIX + Base64.getEncoder().encodeToString(value.encodeToByteArray()) + BASE64_SUFFIX
    }

    internal const val BASE64_PREFIX: String = "=?base64?"
    internal const val BASE64_SUFFIX: String = "?="
}
