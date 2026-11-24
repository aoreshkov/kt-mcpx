package app.oreshkov.ktmcpx.stdio

import app.oreshkov.ktmcpx.ModernBridge
import app.oreshkov.ktmcpx.ModernErrorCodes
import app.oreshkov.ktmcpx.ModernMetaKeys
import app.oreshkov.ktmcpx.ModernReply
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.io.readLine
import kotlinx.io.writeString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves both MCP eras for [server] over one newline-delimited JSON-RPC stream, in place of the SDK's
 * `StdioServerTransport`. Suspends until [input] ends (the client closed stdin).
 *
 * Each line is routed on its own:
 * - a modern request (`server/discover`, or any request whose `params._meta` carries
 *   `io.modelcontextprotocol/protocolVersion`) goes to [bridge], concurrently with the rest. Its
 *   progress and log notifications, then its response, are written as they come;
 * - `notifications/cancelled` for a modern request in flight cancels it, and nothing is written for
 *   that request; for any other request id the notification goes to the SDK session;
 * - another modern notification is dropped;
 * - everything else (`initialize`, legacy requests, the client's answers to server requests) goes to
 *   one long-lived SDK session of [server], created when the function starts. [configureLegacySession]
 *   runs on it before the first line is read, for handlers an adopter installs per session.
 *
 * A line that is not JSON gets a -32700 error, and JSON that is not a JSON-RPC message gets -32600; the
 * SDK transport drops both silently. Blank lines are skipped. Writes are serialized, one message per
 * line. Nothing else may write to [output]: send logs to stderr, and turn off kotlin-logging's startup
 * banner (`KotlinLoggingConfiguration.logStartupMessage = false`), which the SDK's logging would
 * otherwise print to stdout.
 *
 * When [input] ends, modern requests still running are cancelled and the SDK session is closed, as the
 * SDK transport does at EOF. A failure to write to [output] ends the function with that exception.
 */
public suspend fun serveDualEraStdio(
    server: Server,
    bridge: ModernBridge,
    input: Source = System.`in`.asSource().buffered(),
    output: Sink = System.out.asSink().buffered(),
    configureLegacySession: suspend (ServerSession) -> Unit = {},
): Unit = coroutineScope {
    val writer = LineWriter(output)
    val legacy = LegacyStdioTransport(writer)
    val session = server.createSession(legacy)
    val inFlight = ConcurrentHashMap<JsonElement, Job>()
    try {
        configureLegacySession(session)
        while (true) {
            val line = runInterruptible(Dispatchers.IO) { input.readLine() } ?: break
            if (line.isBlank()) continue
            when (val frame = classify(line)) {
                is Frame.Invalid -> writer.write(frame.error.toString())
                is Frame.ModernRequest -> {
                    val job = launch(start = CoroutineStart.LAZY) {
                        try {
                            when (val reply = bridge.handle(line, headers = null) { writer.write(it.toString()) }) {
                                is ModernReply.Response -> writer.write(reply.message.toString())
                                ModernReply.Accepted -> Unit
                            }
                        } finally {
                            inFlight.remove(frame.id, coroutineContext.job)
                        }
                    }
                    inFlight[frame.id] = job
                    job.start()
                }
                is Frame.Cancel -> {
                    val modern = frame.requestId?.let { inFlight.remove(it) }
                    if (modern != null) {
                        modern.cancel()
                    } else {
                        frame.message?.let { legacy.deliver(it) }
                    }
                }
                Frame.ModernNotification -> Unit
                is Frame.Legacy -> legacy.deliver(frame.message)
            }
        }
    } finally {
        inFlight.values.forEach { it.cancel() }
        withContext(NonCancellable) { session.close() }
    }
}

private const val DISCOVER_METHOD = "server/discover"
private const val CANCELLED_METHOD = "notifications/cancelled"

private sealed interface Frame {
    class Invalid(val error: JsonObject) : Frame

    class ModernRequest(val id: JsonElement) : Frame

    data object ModernNotification : Frame

    /** [message] is `null` when the notification does not decode for the SDK. */
    class Cancel(val requestId: JsonElement?, val message: JSONRPCMessage?) : Frame

    class Legacy(val message: JSONRPCMessage) : Frame
}

private fun classify(line: String): Frame {
    val element = try {
        Json.parseToJsonElement(line)
    } catch (_: SerializationException) {
        return Frame.Invalid(rpcError(null, ModernErrorCodes.PARSE_ERROR, "Parse error: the line is not JSON"))
    }
    val message = element as? JsonObject
        ?: return Frame.Invalid(rpcError(null, ModernErrorCodes.INVALID_REQUEST, "Invalid request: expected a single JSON-RPC object"))
    val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val params = message["params"] as? JsonObject
    val id = message["id"]
    val modern = method == DISCOVER_METHOD ||
        (method != null && (params?.get("_meta") as? JsonObject)?.containsKey(ModernMetaKeys.PROTOCOL_VERSION) == true)
    return when {
        modern && id != null -> Frame.ModernRequest(id)
        method == CANCELLED_METHOD -> Frame.Cancel(params?.get("requestId"), decode(message))
        modern -> Frame.ModernNotification
        else -> decode(message)?.let(Frame::Legacy)
            ?: Frame.Invalid(rpcError(id?.takeIf { it is JsonPrimitive }, ModernErrorCodes.INVALID_REQUEST, "Invalid request: not a JSON-RPC message"))
    }
}

private fun decode(message: JsonObject): JSONRPCMessage? = try {
    McpJson.decodeFromJsonElement(JSONRPCMessage.serializer(), message)
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun rpcError(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id ?: JsonNull)
    put(
        "error",
        buildJsonObject {
            put("code", code)
            put("message", message)
        },
    )
}

/** Writes one message per line; concurrent writers never interleave. */
private class LineWriter(private val sink: Sink) {
    private val mutex = Mutex()

    suspend fun write(line: String) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                sink.writeString(line)
                sink.writeString("\n")
                sink.flush()
            }
        }
    }
}

/** The SDK session's end of the shared stream: lines the router hands it in, its messages out. */
private class LegacyStdioTransport(private val writer: LineWriter) : AbstractTransport() {
    @Volatile
    private var closed = false

    override suspend fun start() = Unit

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        check(!closed) { "The stdio transport is closed" }
        writer.write(McpJson.encodeToString(JSONRPCMessage.serializer(), message))
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        invokeOnCloseCallback()
    }

    suspend fun deliver(message: JSONRPCMessage) {
        if (!closed) _onMessage(message)
    }
}
