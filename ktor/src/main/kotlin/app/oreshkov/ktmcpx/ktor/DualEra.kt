package app.oreshkov.ktmcpx.ktor

import app.oreshkov.ktmcpx.ModernBridge
import app.oreshkov.ktmcpx.ModernErrorCodes
import app.oreshkov.ktmcpx.ModernHeaderNames
import app.oreshkov.ktmcpx.ModernHeaders
import app.oreshkov.ktmcpx.ModernProtocol
import app.oreshkov.ktmcpx.ModernReply
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.writeStringUtf8
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.SUPPORTED_PROTOCOL_VERSIONS
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The request body limit of [dualEra], the same as the SDK's routes use. */
public const val DEFAULT_MAX_REQUEST_BODY_SIZE: Long = 4L * 1024 * 1024

/**
 * Serves MCP 2026-07-28 POSTs on [path] through the [ModernBridge] that [bridge] returns.
 *
 * Only a modern POST matches (see [ModernRequestSelector]), so every other request on [path] (legacy
 * POSTs, `initialize`, GET streams, DELETE) still reaches the SDK route registered there, in either
 * registration order. The match reads headers only, never the body.
 *
 * Host and Origin checks: an SDK route on the same [path] installs its `DnsRebindingProtection` on the
 * shared `route(path)` node, which covers this route too. For a path with no SDK route, install
 * `DnsRebindingProtection` on `route(path)` yourself.
 *
 * The answer is JSON, unless a notification for the request (progress, a log message) arrives before
 * the result and the client accepts `text/event-stream`: then the response switches to an SSE stream
 * that ends with the result. `subscriptions/listen` is always an SSE stream, open until the client
 * disconnects or [ModernBridge.close] ends it; a client that does not accept SSE gets 406 for it.
 */
public fun Route.dualEra(
    path: String,
    maxRequestBodySize: Long = DEFAULT_MAX_REQUEST_BODY_SIZE,
    bridge: suspend RoutingContext.() -> ModernBridge,
): Route = route(path) {
    createChild(ModernRequestSelector).post {
        serveModern(bridge(), maxRequestBodySize)
    }
}

/** [Route.dualEra] at the application's routing root. */
public fun Application.dualEra(
    path: String = "/mcp",
    maxRequestBodySize: Long = DEFAULT_MAX_REQUEST_BODY_SIZE,
    bridge: suspend RoutingContext.() -> ModernBridge,
) {
    routing { dualEra(path, maxRequestBodySize, bridge) }
}

/**
 * Matches a modern request from headers alone, first rule that applies:
 *
 * 1. `MCP-Protocol-Version` names a legacy revision the SDK serves: legacy.
 * 2. `MCP-Protocol-Version` names a revision kt-mcpx serves: modern, even without `Mcp-Method`, so the
 *    bridge answers -32020 instead of the SDK's "not initialized".
 * 3. `Mcp-Session-Id` is present: legacy (sessions do not exist in 2026-07-28).
 * 4. `Mcp-Method: initialize`: legacy. A legacy handshake carries no version header yet, and some
 *    clients send `Mcp-Method` on every POST.
 * 5. `Mcp-Method` is present with a version header kt-mcpx does not know: modern, so the bridge answers
 *    -32022.
 * 6. Otherwise legacy. That includes `Mcp-Method` without any version header: a 2026-07-28 client always
 *    sends the header, while the Kotlin SDK 0.16.0 client sends `Mcp-Method` on every POST but never the
 *    version, and has no session id on a stateless route.
 *
 * A constant-quality match, so the route under it outranks a sibling `post {}` (method selectors are
 * transparent). It never reads the body.
 */
internal object ModernRequestSelector : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        if (isModern(context.call.request.headers)) RouteSelectorEvaluation.Constant else RouteSelectorEvaluation.FailedParameter

    fun isModern(headers: Headers): Boolean {
        val version = headers[ModernHeaderNames.PROTOCOL_VERSION]
        val method = headers[ModernHeaderNames.METHOD]
        return when {
            version in SUPPORTED_PROTOCOL_VERSIONS -> false
            version in ModernProtocol.SUPPORTED_VERSIONS -> true
            MCP_SESSION_ID_HEADER in headers -> false
            method == Method.Defined.Initialize.value -> false
            else -> method != null && version != null
        }
    }

    override fun toString(): String = "(modern MCP request)"

    private const val MCP_SESSION_ID_HEADER = "Mcp-Session-Id"
}

private suspend fun RoutingContext.serveModern(bridge: ModernBridge, maxRequestBodySize: Long) {
    val body = readBody(maxRequestBodySize) ?: run {
        call.respondText(
            tooLarge(maxRequestBodySize).toString(),
            ContentType.Application.Json,
            HttpStatusCode.PayloadTooLarge,
        )
        return
    }
    val headers = ModernHeaders(
        method = call.request.header(ModernHeaderNames.METHOD),
        name = call.request.header(ModernHeaderNames.NAME),
        protocolVersion = call.request.header(ModernHeaderNames.PROTOCOL_VERSION),
        params = paramHeaders(call.request.headers),
    )
    if (!acceptsEventStream()) {
        if (headers.method == LISTEN_METHOD) {
            // A listen stream only ever answers on SSE; without it the request would never end.
            call.respondText(notAcceptable().toString(), ContentType.Application.Json, HttpStatusCode.NotAcceptable)
            return
        }
        respond(bridge.handle(body, headers))
        return
    }
    coroutineScope {
        // Notifications arrive before handle() returns; closing after it ends the stream loop.
        val events = Channel<JsonObject>(Channel.UNLIMITED)
        val reply = async {
            try {
                bridge.handle(body, headers) { events.trySend(it) }
            } finally {
                events.close()
            }
        }
        val first = events.receiveCatching().getOrNull()
        if (first == null) {
            respond(reply.await())
            return@coroutineScope
        }
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header("X-Accel-Buffering", "no")
        call.respondBytesWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
            writeEvent(first)
            for (event in events) writeEvent(event)
            when (val answer = reply.await()) {
                is ModernReply.Response -> writeEvent(answer.message)
                ModernReply.Accepted -> Unit
            }
        }
    }
}

private suspend fun RoutingContext.respond(reply: ModernReply) {
    when (reply) {
        is ModernReply.Response -> {
            // The bridge answers 503 only when it is at its concurrency limit, which clears quickly.
            if (reply.httpStatus == HttpStatusCode.ServiceUnavailable.value) call.response.header(HttpHeaders.RetryAfter, "1")
            call.respondText(reply.message.toString(), ContentType.Application.Json, HttpStatusCode.fromValue(reply.httpStatus))
        }
        ModernReply.Accepted -> call.respond(HttpStatusCode.Accepted)
    }
}

private suspend fun ByteWriteChannel.writeEvent(message: JsonObject) {
    writeStringUtf8("event: message\ndata: $message\n\n")
    flush()
}

private fun RoutingContext.acceptsEventStream(): Boolean =
    call.request.header(HttpHeaders.Accept)?.split(',')?.any { it.substringBefore(';').trim() == "text/event-stream" } == true

/** The `Mcp-Param-*` headers by the name after the prefix, each with its field lines joined. */
private fun paramHeaders(headers: Headers): Map<String, String> = buildMap {
    for (name in headers.names()) {
        if (name.length > ModernHeaderNames.PARAM_PREFIX.length && name.startsWith(ModernHeaderNames.PARAM_PREFIX, ignoreCase = true)) {
            put(name.substring(ModernHeaderNames.PARAM_PREFIX.length), headers.getAll(name).orEmpty().joinToString(", "))
        }
    }
}

/** The body as UTF-8 text, or `null` when it is larger than [limit]. */
private suspend fun RoutingContext.readBody(limit: Long): String? {
    val declared = call.request.contentLength()
    if (declared != null && declared > limit) return null
    val bytes = call.receiveChannel().readBuffer(limit + 1).readByteArray()
    return if (bytes.size > limit) null else bytes.decodeToString()
}

private const val LISTEN_METHOD = "subscriptions/listen"

private fun tooLarge(limit: Long): JsonObject = invalidRequest("Invalid request: the body exceeds $limit bytes")

private fun notAcceptable(): JsonObject =
    invalidRequest("Invalid request: $LISTEN_METHOD streams over SSE, so the Accept header must include text/event-stream")

private fun invalidRequest(message: String): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", JsonNull)
    put(
        "error",
        buildJsonObject {
            put("code", ModernErrorCodes.INVALID_REQUEST)
            put("message", message)
        },
    )
}
