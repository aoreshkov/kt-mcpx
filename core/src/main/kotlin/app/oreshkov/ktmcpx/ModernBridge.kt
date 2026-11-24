package app.oreshkov.ktmcpx

import app.oreshkov.ktmcpx.internal.DISCOVER_METHOD
import app.oreshkov.ktmcpx.internal.EMPTY_OBJECT
import app.oreshkov.ktmcpx.internal.FORWARDED_ID
import app.oreshkov.ktmcpx.internal.LOG_LEVELS
import app.oreshkov.ktmcpx.internal.ServerRequestDecision
import app.oreshkov.ktmcpx.internal.SyntheticOutcome
import app.oreshkov.ktmcpx.internal.SyntheticSession
import app.oreshkov.ktmcpx.internal.ValidRequest
import app.oreshkov.ktmcpx.internal.Validation
import app.oreshkov.ktmcpx.internal.capabilityFor
import app.oreshkov.ktmcpx.internal.discoverResult
import app.oreshkov.ktmcpx.internal.httpStatusFor
import app.oreshkov.ktmcpx.internal.missingCapabilityIn
import app.oreshkov.ktmcpx.internal.modernError
import app.oreshkov.ktmcpx.internal.modernResult
import app.oreshkov.ktmcpx.internal.obj
import app.oreshkov.ktmcpx.internal.rpcError
import app.oreshkov.ktmcpx.internal.rpcResult
import app.oreshkov.ktmcpx.internal.serverInfo
import app.oreshkov.ktmcpx.internal.string
import app.oreshkov.ktmcpx.internal.validate
import app.oreshkov.ktmcpx.internal.with
import app.oreshkov.ktmcpx.internal.without
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Serves MCP 2026-07-28 requests with an SDK [Server] the adopter already has.
 *
 * `server/discover` is answered from the server's own `initialize` answer. Every other request runs
 * through a short-lived SDK session created for it and closed after it, so tools, resources, prompts and
 * per-session handlers work unchanged, and nothing outlives the request. Legacy traffic never reaches
 * the bridge.
 *
 * ```kotlin
 * val bridge = ModernBridge(server) {
 *     cache(CacheableMethod.ToolsList, ttl = 24.hours, scope = CacheScope.Private)
 * }
 * ```
 *
 * The bridge holds no per-client state and is safe to share between concurrent requests.
 */
public class ModernBridge(
    private val server: Server,
    configure: Config.() -> Unit = {},
) {
    private val config = Config().apply(configure)

    /** Settings of a [ModernBridge]. */
    public class Config internal constructor() {
        internal val cachePolicies = mutableMapOf<CacheableMethod, CachePolicy>()

        /**
         * How long one request may run before the bridge gives up on it with -32603. Unlimited by
         * default: on HTTP the client ends a request by disconnecting, which cancels it.
         */
        public var requestTimeout: Duration = Duration.INFINITE

        /** The caching hint for [method]'s results. Unconfigured methods get [CachePolicy.DEFAULT]. */
        public fun cache(method: CacheableMethod, ttl: Duration, scope: CacheScope = CacheScope.Private) {
            cachePolicies[method] = CachePolicy(ttl, scope)
        }

        internal fun cachePolicy(method: CacheableMethod): CachePolicy = cachePolicies[method] ?: CachePolicy.DEFAULT
    }

    /**
     * Handles one modern JSON-RPC message, given as the text the transport received.
     *
     * [headers] are the HTTP request's standard headers, or `null` on a transport without headers.
     * [onNotification] receives the notifications that belong on this request's response stream
     * (progress, and log messages at or above the request's `_meta` log level), in order, before this
     * function returns. It is never called after that.
     */
    public suspend fun handle(
        message: String,
        headers: ModernHeaders?,
        onNotification: suspend (JsonObject) -> Unit = {},
    ): ModernReply = when (val validation = validate(message, headers, ::toolInputSchema)) {
        is Validation.Notification -> ModernReply.Accepted
        is Validation.Rejected -> response(validation.message)
        is Validation.Valid -> serve(validation.request, onNotification)
    }

    /** The parts of a registered tool's input schema that can carry `x-mcp-header` annotations. */
    private fun toolInputSchema(name: String): JsonObject? {
        val schema = server.tools[name]?.tool?.inputSchema ?: return null
        return buildJsonObject {
            schema.properties?.let { put("properties", it) }
            schema.defs?.let { put("\$defs", it) }
        }
    }

    private suspend fun serve(request: ValidRequest, onNotification: suspend (JsonObject) -> Unit): ModernReply {
        val open = AtomicBoolean(true)
        val relay: suspend (JsonObject) -> Unit = { notification ->
            if (open.get() && belongsOnStream(notification, request)) onNotification(notification)
        }
        return try {
            withTimeout(config.requestTimeout) {
                val session = SyntheticSession(server, request.meta.clientCapabilities, request.meta.clientInfo)
                session.run(relay, { serverRequest -> decide(serverRequest, request) }) { initializeResult, forward ->
                    if (request.method == DISCOVER_METHOD) {
                        val result = discoverResult(initializeResult, config.cachePolicy(CacheableMethod.ServerDiscover))
                        response(rpcResult(request.id, result))
                    } else {
                        response(answer(request, forward(forwardedRequest(request)), serverInfo(initializeResult)))
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            response(rpcError(request.id, ModernErrorCodes.INTERNAL_ERROR, "The request did not finish within ${config.requestTimeout}"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            response(rpcError(request.id, ModernErrorCodes.INTERNAL_ERROR, "Internal error: ${e.message ?: e::class.simpleName}"))
        } finally {
            open.set(false)
        }
    }

    /** The request as the SDK session sees it: the bridge's id, and `_meta` without the modern keys. */
    private fun forwardedRequest(request: ValidRequest): JsonObject {
        val params = if (request.meta.passThrough.isEmpty()) {
            request.params.without("_meta")
        } else {
            request.params.with("_meta", request.meta.passThrough)
        }
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", FORWARDED_ID)
            put("method", request.method)
            put("params", params)
        }
    }

    private fun answer(request: ValidRequest, outcome: SyntheticOutcome, serverInfo: JsonObject?): JsonObject = when (outcome) {
        is SyntheticOutcome.Aborted -> errorResponse(request, outcome.error)
        is SyntheticOutcome.Answered -> {
            val message = outcome.message
            val missing = missingCapabilityIn(message, request.meta.clientCapabilities)
            val error = message.obj("error")
            when {
                missing != null -> errorResponse(request, missing.error())
                error != null -> errorResponse(request, modernError(error))
                else -> {
                    val cache = CacheableMethod.of(request.method)?.let(config::cachePolicy)
                    rpcResult(request.id, modernResult(request.method, message.obj("result") ?: EMPTY_OBJECT, serverInfo, cache))
                }
            }
        }
    }

    private fun errorResponse(request: ValidRequest, error: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", request.id)
        put("error", error)
    }

    /** What a request from the server gets while a modern request runs. */
    private fun decide(serverRequest: JsonObject, request: ValidRequest): ServerRequestDecision {
        if (serverRequest.string("method") == "ping") return ServerRequestDecision.Answer(EMPTY_OBJECT)
        val capability = capabilityFor(serverRequest) ?: return ServerRequestDecision.Refuse(
            ModernErrorCodes.METHOD_NOT_FOUND,
            "${serverRequest.string("method")} is not available to a 2026-07-28 client",
        )
        return if (capability.isMissingFrom(request.meta.clientCapabilities)) {
            ServerRequestDecision.Abort(capability.error())
        } else {
            ServerRequestDecision.Refuse(
                ModernErrorCodes.METHOD_NOT_FOUND,
                "${serverRequest.string("method")} cannot be relayed to a 2026-07-28 client by this kt-mcpx version",
            )
        }
    }

    /**
     * Only progress for this request's token, and log messages the request asked for, go on its stream.
     * Everything else (list_changed and resources/updated broadcasts that reach every SDK session) is
     * dropped; `subscriptions/listen` will carry those.
     */
    private fun belongsOnStream(notification: JsonObject, request: ValidRequest): Boolean {
        val params = notification.obj("params") ?: EMPTY_OBJECT
        return when (notification.string("method")) {
            "notifications/progress" -> {
                val token = request.meta.passThrough["progressToken"]
                token != null && params["progressToken"] == token
            }
            "notifications/message" -> {
                val threshold = request.meta.logLevel ?: return false
                LOG_LEVELS.indexOf(params.string("level")) >= LOG_LEVELS.indexOf(threshold)
            }
            else -> false
        }
    }

    private fun response(message: JsonObject): ModernReply.Response {
        val code = (message.obj("error")?.get("code") as? JsonPrimitive)?.intOrNull
        return ModernReply.Response(message, httpStatusFor(code))
    }
}
