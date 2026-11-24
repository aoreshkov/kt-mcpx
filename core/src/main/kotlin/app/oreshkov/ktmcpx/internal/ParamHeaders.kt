package app.oreshkov.ktmcpx.internal

import app.oreshkov.ktmcpx.ModernHeaderNames
import app.oreshkov.ktmcpx.ModernHeaders
import app.oreshkov.ktmcpx.ModernParamHeaders
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class ParamType(val schemaType: String) {
    STRING("string"),
    INTEGER("integer"),
    BOOLEAN("boolean"),
}

/** One valid `x-mcp-header` annotation: the argument at [path] goes into `Mcp-Param-[header]`. */
internal class ParamAnnotation(val path: List<String>, val header: String, val type: ParamType) {
    fun valueIn(arguments: JsonObject): JsonElement? {
        var current: JsonElement = arguments
        for (key in path) current = (current as? JsonObject)?.get(key) ?: return null
        return current
    }

    /** The header value of [value] before encoding: integers in plain decimal, the rest as is. */
    fun render(value: JsonPrimitive): String {
        if (type != ParamType.INTEGER || value.isString) return value.content
        return value.content.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: value.content
    }

    /** Whether the decoded header value equals [body]; integers compare numerically. */
    fun matches(decoded: String, body: JsonElement): Boolean {
        val primitive = body as? JsonPrimitive ?: return false
        return when (type) {
            ParamType.STRING -> decoded == primitive.content
            ParamType.BOOLEAN -> (decoded == "true" || decoded == "false") && decoded == primitive.content
            ParamType.INTEGER -> {
                val header = decoded.toBigDecimalOrNull()
                val argument = primitive.content.toBigDecimalOrNull()
                header != null && argument != null && header.compareTo(argument) == 0
            }
        }
    }
}

internal class ParamScan(val annotations: List<ParamAnnotation>, val problems: List<String>)

/** Keywords whose object value maps names to subschemas. Only `properties` keeps a path reachable. */
private val NAME_MAPS = setOf("properties", "patternProperties", "\$defs", "definitions", "dependentSchemas")

/** Keywords whose values are instance data, not schemas. */
private val DATA_KEYWORDS = setOf("const", "enum", "default", "examples")

private const val TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~"

/** Every `x-mcp-header` annotation in [inputSchema], and what makes any of them invalid. */
internal fun scanParamHeaders(inputSchema: JsonObject): ParamScan {
    val annotations = mutableListOf<ParamAnnotation>()
    val problems = mutableListOf<String>()

    fun checkAnnotation(value: JsonElement, schema: JsonObject, path: List<String>?, pointer: String) {
        val at = pointer.ifEmpty { "/" }
        if (path.isNullOrEmpty()) {
            problems += "$at: ${ModernParamHeaders.ANNOTATION} is only allowed on a property reached through properties alone"
            return
        }
        val header = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (header.isNullOrEmpty() || !header.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in TOKEN_SYMBOLS }) {
            problems += "$at: ${ModernParamHeaders.ANNOTATION} must be a non-empty HTTP token, got $value"
            return
        }
        val typeName = (schema["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val type = ParamType.entries.firstOrNull { it.schemaType == typeName }
        if (type == null) {
            problems += "$at: ${ModernParamHeaders.ANNOTATION} is only allowed on string, integer or boolean properties, got type ${schema["type"]}"
            return
        }
        annotations += ParamAnnotation(path, header, type)
    }

    fun walk(element: JsonElement, path: List<String>?, pointer: String) {
        when (element) {
            is JsonArray -> element.forEachIndexed { index, item -> walk(item, null, "$pointer/$index") }
            is JsonObject -> {
                element[ModernParamHeaders.ANNOTATION]?.let { checkAnnotation(it, element, path, pointer) }
                for ((key, value) in element) {
                    when {
                        key == ModernParamHeaders.ANNOTATION || key in DATA_KEYWORDS -> Unit
                        key in NAME_MAPS && value is JsonObject -> for ((name, child) in value) {
                            val childPath = if (key == "properties" && path != null) path + name else null
                            walk(child, childPath, "$pointer/$key/${name.replace("~", "~0").replace("/", "~1")}")
                        }
                        else -> walk(value, null, "$pointer/$key")
                    }
                }
            }
            else -> Unit
        }
    }

    walk(inputSchema, emptyList(), "")
    annotations.groupBy { it.header.lowercase() }.values.filter { it.size > 1 }.forEach { same ->
        problems += "${ModernParamHeaders.ANNOTATION} \"${same.first().header}\" is used ${same.size} times (names are case-insensitive)"
    }
    return ParamScan(annotations, problems)
}

/**
 * Why [headers] disagree with the `tools/call` [arguments] under [inputSchema]'s annotations, or `null`
 * when they agree. A schema with invalid annotations is not enforced: clients drop such a tool.
 */
internal fun paramHeaderMismatch(inputSchema: JsonObject, arguments: JsonObject, headers: ModernHeaders): String? {
    val scan = scanParamHeaders(inputSchema)
    if (scan.problems.isNotEmpty()) return null
    for (annotation in scan.annotations) {
        val name = ModernHeaderNames.PARAM_PREFIX + annotation.header
        val raw = headers.paramsByLowercaseName[annotation.header.lowercase()]?.trim(' ', '\t')
        val body = annotation.valueIn(arguments)?.takeIf { it !is JsonNull }
        val argument = annotation.path.joinToString(".")
        when {
            body == null -> if (raw != null) return "Header mismatch: $name is set but the arguments have no $argument"
            raw == null -> return "Header mismatch: the $name header is required when the arguments carry $argument"
            !raw.all { it == '\t' || it in ' '..'~' } -> return "Header mismatch: $name contains characters a header value may not carry"
            else -> {
                val decoded = decodeHeaderValue(raw) ?: return "Header mismatch: $name is not valid Base64"
                if (!annotation.matches(decoded, body)) {
                    return "Header mismatch: $name is '$raw' but the arguments say $argument = $body"
                }
            }
        }
    }
    return null
}
