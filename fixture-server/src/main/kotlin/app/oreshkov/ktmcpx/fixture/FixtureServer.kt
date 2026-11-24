package app.oreshkov.ktmcpx.fixture

import app.oreshkov.ktmcpx.ModernBridge
import app.oreshkov.ktmcpx.ktor.dualEra
import app.oreshkov.ktmcpx.stdio.serveDualEraStdio
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.AudioContent
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.CompleteRequest
import io.modelcontextprotocol.kotlin.sdk.types.CompleteResult
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.EmptyJsonObject
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.PromptReference
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplateReference
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.SamplingMessage
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.StringSchema
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/** A 1×1 red PNG. */
private const val RED_PIXEL_PNG =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg=="

/** A WAV header with no samples: 8 kHz, mono, 8 bit. */
private const val SILENT_WAV = "UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA="

/**
 * The tools, prompts, resources and completions the official conformance suite calls on a server,
 * registered with plain SDK APIs, as an adopter would. The completion handler is installed per session,
 * the way kotlin-lib-mcp installs its own, so the bridge is checked against that pattern too.
 */
fun fixtureServer(): Server {
    val server = Server(
        serverInfo = Implementation(name = "kt-mcpx-fixture", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
                prompts = ServerCapabilities.Prompts(listChanged = false),
                resources = ServerCapabilities.Resources(listChanged = false, subscribe = true),
                logging = EmptyJsonObject,
                completions = EmptyJsonObject,
            ),
        ),
        instructions = "Conformance fixture server for kt-mcpx.",
    )
    server.registerTools()
    server.registerResources()
    server.registerPrompts()
    server.registerCompletions()
    return server
}

private fun Server.registerTools() {
    val server = this
    server.tool("test_simple_text", "Returns a simple text response.") {
        CallToolResult(content = listOf(TextContent("This is a simple text response for testing.")))
    }
    server.tool("test_image_content", "Returns an image.") {
        CallToolResult(content = listOf(ImageContent(data = RED_PIXEL_PNG, mimeType = "image/png")))
    }
    server.tool("test_audio_content", "Returns audio.") {
        CallToolResult(content = listOf(AudioContent(data = SILENT_WAV, mimeType = "audio/wav")))
    }
    server.tool("test_embedded_resource", "Returns an embedded resource.") {
        CallToolResult(
            content = listOf(
                EmbeddedResource(
                    TextResourceContents(
                        text = "This is an embedded resource content.",
                        uri = "test://embedded-resource",
                        mimeType = "text/plain",
                    ),
                ),
            ),
        )
    }
    server.tool("test_multiple_content_types", "Returns text, an image and a resource.") {
        CallToolResult(
            content = listOf(
                TextContent("Multiple content types test:"),
                ImageContent(data = RED_PIXEL_PNG, mimeType = "image/png"),
                EmbeddedResource(
                    TextResourceContents(
                        text = """{"test":"data","value":123}""",
                        uri = "test://mixed-content-resource",
                        mimeType = "application/json",
                    ),
                ),
            ),
        )
    }
    server.tool("test_error_handling", "Always fails.") {
        CallToolResult(content = listOf(TextContent("This tool intentionally returns an error for testing")), isError = true)
    }
    server.tool("test_tool_with_progress", "Reports progress 0, 50 and 100 of 100.") { request ->
        val token = request.params.meta?.progressToken
        for ((index, progress) in listOf(0.0, 50.0, 100.0).withIndex()) {
            if (index > 0) delay(50.milliseconds)
            if (token != null) notification(ProgressNotification(ProgressNotificationParams(token, progress, 100.0)))
        }
        CallToolResult(content = listOf(TextContent("Progress tool completed")))
    }
    server.tool("test_tool_with_logging", "Logs three messages at info level.") {
        for ((index, message) in listOf("Tool execution started", "Tool processing data", "Tool execution completed").withIndex()) {
            if (index > 0) delay(50.milliseconds)
            log(message)
        }
        CallToolResult(content = listOf(TextContent("Logging tool completed")))
    }
    server.tool("test_logging_tool", "Logs one message at info level.") {
        log("test_logging_tool ran")
        CallToolResult(content = listOf(TextContent("Logged")))
    }
    server.tool("test_missing_capability", "Asks the client for sampling, which it must have declared.") {
        sample("Say hello")
        CallToolResult(content = listOf(TextContent("Sampling answered")))
    }
    server.tool(
        "test_sampling",
        "Asks the client to sample a completion for the prompt.",
        stringArgument("prompt", "The prompt to send to the client's model"),
    ) { request ->
        val prompt = request.arguments?.get("prompt")?.jsonPrimitive?.content ?: "Hello"
        val answer = sample(prompt)
        CallToolResult(content = listOf(TextContent("LLM response: ${(answer.content.firstOrNull() as? TextContent)?.text}")))
    }
    server.tool(
        "test_elicitation",
        "Asks the client for input.",
        stringArgument("message", "The message to show the user"),
    ) { request ->
        val answer = elicitName(request.arguments?.get("message")?.jsonPrimitive?.content ?: "Your name?")
        CallToolResult(content = listOf(TextContent("User response: action=${answer.action}, content=${answer.content}")))
    }
    server.tool("test_streaming_elicitation", "Asks the client for input while the request runs.") {
        val answer = elicitName("Your name?")
        CallToolResult(content = listOf(TextContent("User response: action=${answer.action}")))
    }
    // SEP-2243: the annotations are plain schema keywords; the bridge checks the Mcp-Param-* headers.
    server.tool(
        "test_custom_headers",
        "Echoes arguments that clients mirror into Mcp-Param-* headers.",
        ToolSchema(
            properties = buildJsonObject {
                put("region", headerProperty("string", "Region", "A string mirrored into Mcp-Param-Region"))
                put("priority", headerProperty("integer", "Priority", "An integer mirrored into Mcp-Param-Priority"))
                put("verbose", headerProperty("boolean", "Verbose", "A boolean mirrored into Mcp-Param-Verbose"))
            },
        ),
    ) { request ->
        CallToolResult(content = listOf(TextContent("Arguments: ${request.arguments ?: buildJsonObject {}}")))
    }
}

private fun headerProperty(type: String, header: String, description: String) = buildJsonObject {
    put("type", type)
    put("description", description)
    put("x-mcp-header", header)
}

private fun Server.registerResources() {
    addResource("test://static-text", "static-text", "A static text resource.", "text/plain") { request ->
        ReadResourceResult(
            listOf(TextResourceContents("This is the content of the static text resource.", request.params.uri, "text/plain")),
        )
    }
    addResource("test://static-binary", "static-binary", "A static binary resource (a PNG).", "image/png") { request ->
        ReadResourceResult(listOf(BlobResourceContents(RED_PIXEL_PNG, request.params.uri, "image/png")))
    }
    addResource("test://watched-resource", "watched-resource", "A resource clients may subscribe to.", "text/plain") { request ->
        ReadResourceResult(listOf(TextResourceContents("Watched resource content.", request.params.uri, "text/plain")))
    }
    addResourceTemplate(
        uriTemplate = "test://template/{id}/data",
        name = "template-data",
        description = "Data for an id, substituted from the URI.",
        mimeType = "application/json",
    ) { request, variables ->
        val id = variables["id"].orEmpty()
        val text = buildJsonObject {
            put("id", id)
            put("templateTest", true)
            put("data", "Data for ID: $id")
        }.toString()
        ReadResourceResult(listOf(TextResourceContents(text, request.params.uri, "application/json")))
    }
}

private fun Server.registerPrompts() {
    addPrompt("test_simple_prompt", "A prompt without arguments.") {
        GetPromptResult(listOf(PromptMessage(Role.User, TextContent("This is a simple prompt for testing."))))
    }
    addPrompt(
        "test_prompt_with_arguments",
        "A prompt that substitutes two arguments.",
        listOf(
            PromptArgument("arg1", "First test argument", required = true),
            PromptArgument("arg2", "Second test argument", required = true),
        ),
    ) { request ->
        val args = request.params.arguments.orEmpty()
        GetPromptResult(
            listOf(PromptMessage(Role.User, TextContent("Prompt with arguments: arg1='${args["arg1"]}', arg2='${args["arg2"]}'"))),
        )
    }
    addPrompt(
        "test_prompt_with_embedded_resource",
        "A prompt that embeds a resource.",
        listOf(PromptArgument("resourceUri", "URI of the resource to embed", required = true)),
    ) { request ->
        val uri = request.params.arguments?.get("resourceUri") ?: "test://example-resource"
        GetPromptResult(
            listOf(
                PromptMessage(Role.User, EmbeddedResource(TextResourceContents("Embedded resource content for testing.", uri, "text/plain"))),
                PromptMessage(Role.User, TextContent("Please process the embedded resource above.")),
            ),
        )
    }
    addPrompt("test_prompt_with_image", "A prompt with an image.") {
        GetPromptResult(
            listOf(
                PromptMessage(Role.User, ImageContent(data = RED_PIXEL_PNG, mimeType = "image/png")),
                PromptMessage(Role.User, TextContent("Please analyze the image above.")),
            ),
        )
    }
}

/** Candidate values for `test_prompt_with_arguments`' arguments. */
private val COMPLETION_CANDIDATES = listOf("paris", "park", "party", "test", "testValue1", "testValue2")

/**
 * `completion/complete`, installed per session: the SDK `Server` has no completion registry, so an
 * adopter sets the handler on each session from `onConnect`, as kotlin-lib-mcp does. A synthetic session
 * fires `onConnect` too.
 */
private fun Server.registerCompletions() {
    val configured = ConcurrentHashMap.newKeySet<String>()
    onConnect {
        for (session in sessions.values) {
            if (!configured.add(session.sessionId)) continue
            session.onClose { configured.remove(session.sessionId) }
            session.setRequestHandler<CompleteRequest>(Method.Defined.CompletionComplete) { request, _ ->
                val values = when (val ref = request.ref) {
                    is PromptReference ->
                        if (ref.name == "test_prompt_with_arguments") {
                            COMPLETION_CANDIDATES.filter { it.startsWith(request.argument.value) }
                        } else {
                            emptyList()
                        }
                    is ResourceTemplateReference -> emptyList()
                }
                CompleteResult(CompleteResult.Completion(values, total = values.size, hasMore = false))
            }
        }
    }
}

/** The legacy SDK route and the modern bridge on one endpoint, as an adopter wires them. */
fun Application.fixtureModule(server: Server = fixtureServer(), path: String = "/mcp") {
    val bridge = ModernBridge(server)
    mcpStreamableHttp(path = path) { server }
    dualEra(path) { bridge }
}

/**
 * `fixture-server [port]`: serves [fixtureModule] on 127.0.0.1, port 3000 by default.
 *
 * `fixture-server --stdio [trace]`: serves both eras on stdin/stdout instead. With a trace file, every
 * line read from stdin is appended to it, to see which era a real client chose.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--stdio") {
        // stdout is the protocol stream: the SDK's logging facade would print its banner there.
        KotlinLoggingConfiguration.logStartupMessage = false
        val input = args.getOrNull(1)?.let { TeeInputStream(System.`in`, FileOutputStream(it, true)) } ?: System.`in`
        val server = fixtureServer()
        runBlocking { serveDualEraStdio(server, ModernBridge(server), input.asSource().buffered()) }
        return
    }
    val port = args.firstOrNull()?.toInt() ?: 3000
    embeddedServer(CIO, host = "127.0.0.1", port = port) { fixtureModule() }.start(wait = true)
}

/** Copies every byte read from [source] to [copy]. */
private class TeeInputStream(private val source: InputStream, private val copy: OutputStream) : InputStream() {
    override fun read(): Int = source.read().also {
        if (it >= 0) {
            copy.write(it)
            copy.flush()
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len).also {
        if (it > 0) {
            copy.write(b, off, it)
            copy.flush()
        }
    }

    override fun close() {
        source.close()
        copy.close()
    }
}

private fun Server.tool(
    name: String,
    description: String,
    inputSchema: ToolSchema = ToolSchema(),
    handler: suspend ClientConnection.(io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest) -> CallToolResult,
) = addTool(name = name, description = description, inputSchema = inputSchema, handler = handler)

private fun stringArgument(name: String, description: String) = ToolSchema(
    properties = buildJsonObject {
        put(name, buildJsonObject {
            put("type", "string")
            put("description", description)
        })
    },
    required = listOf(name),
)

private suspend fun ClientConnection.log(message: String) =
    sendLoggingMessage(LoggingMessageNotification(LoggingMessageNotificationParams(LoggingLevel.Info, JsonPrimitive(message))))

private suspend fun ClientConnection.sample(prompt: String) = createMessage(
    CreateMessageRequest(
        CreateMessageRequestParams(maxTokens = 100, messages = listOf(SamplingMessage(Role.User, listOf(TextContent(prompt))))),
    ),
)

private suspend fun ClientConnection.elicitName(message: String) = createElicitation(
    message = message,
    requestedSchema = ElicitRequestParams.RequestedSchema(properties = mapOf("response" to StringSchema())),
)
