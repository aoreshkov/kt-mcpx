package app.oreshkov.ktmcpx

import app.oreshkov.ktmcpx.internal.DISCOVER_METHOD
import app.oreshkov.ktmcpx.internal.ELICITATION_METHOD
import app.oreshkov.ktmcpx.internal.EMPTY_OBJECT
import app.oreshkov.ktmcpx.internal.FORWARDED_ID
import app.oreshkov.ktmcpx.internal.INPUT_REQUIRED_METHODS
import app.oreshkov.ktmcpx.internal.INPUT_RESPONSES
import app.oreshkov.ktmcpx.internal.InputKeys
import app.oreshkov.ktmcpx.internal.LISTEN_METHOD
import app.oreshkov.ktmcpx.internal.LOG_LEVELS
import app.oreshkov.ktmcpx.internal.REQUEST_STATE
import app.oreshkov.ktmcpx.internal.RequestStateCodec
import app.oreshkov.ktmcpx.internal.RequiredCapability
import app.oreshkov.ktmcpx.internal.ServerRequestDecision
import app.oreshkov.ktmcpx.internal.SubscriptionFilter
import app.oreshkov.ktmcpx.internal.SubscriptionHub
import app.oreshkov.ktmcpx.internal.SyntheticOutcome
import app.oreshkov.ktmcpx.internal.acknowledgement
import app.oreshkov.ktmcpx.internal.listenResult
import app.oreshkov.ktmcpx.internal.SyntheticSession
import app.oreshkov.ktmcpx.internal.ValidRequest
import app.oreshkov.ktmcpx.internal.Validation
import app.oreshkov.ktmcpx.internal.capabilityFor
import app.oreshkov.ktmcpx.internal.discoverResult
import app.oreshkov.ktmcpx.internal.httpStatusFor
import app.oreshkov.ktmcpx.internal.inputRequestOf
import app.oreshkov.ktmcpx.internal.inputRequiredResult
import app.oreshkov.ktmcpx.internal.missingCapabilityIn
import app.oreshkov.ktmcpx.internal.modernError
import app.oreshkov.ktmcpx.internal.modernResult
import app.oreshkov.ktmcpx.internal.obj
import app.oreshkov.ktmcpx.internal.rpcError
import app.oreshkov.ktmcpx.internal.rpcResult
import app.oreshkov.ktmcpx.internal.serverInfo
import app.oreshkov.ktmcpx.internal.string
import app.oreshkov.ktmcpx.internal.urlElicitationsIn
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

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
    private val requestStates = RequestStateCodec(config.requestStateSecret, config.requestStateTtl.inWholeMilliseconds)
    private val subscriptions = SubscriptionHub(server)

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

        /**
         * How long the bridge waits for further questions after a handler asks the client something,
         * before it answers `input_required` (see [ModernInputRequests]). Questions asked concurrently
         * within this window share one result.
         */
        public var inputRequestWindow: Duration = 50.milliseconds
            set(value) {
                require(!value.isNegative() && value.isFinite()) { "The input request window must be finite and not negative: $value" }
                field = value
            }

        /** How long a client may take to retry with `inputResponses` before its `requestState` expires. */
        public var requestStateTtl: Duration = 30.minutes
            set(value) {
                require(value.isPositive() && value.isFinite()) { "The requestState TTL must be finite and positive: $value" }
                field = value
            }

        internal var requestStateSecret: ByteArray? = null

        /**
         * The HMAC-SHA256 key that signs `requestState`, at least 32 bytes. By default each bridge makes
         * a random one, so a retry must reach the same bridge instance. Set the same key on every
         * instance behind a load balancer.
         */
        public fun requestStateSecret(secret: ByteArray) {
            require(secret.size >= MIN_SECRET_BYTES) { "The requestState secret needs at least $MIN_SECRET_BYTES bytes, got ${secret.size}" }
            requestStateSecret = secret.copyOf()
        }

        private companion object {
            const val MIN_SECRET_BYTES = 32
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
     *
     * `subscriptions/listen` is long-lived: its acknowledgement and every notification it subscribed to
     * go to [onNotification], and the call returns only when [close] ends the stream. The transport ends
     * it early by cancelling the call (a client disconnect on HTTP, `notifications/cancelled` on stdio).
     */
    public suspend fun handle(
        message: String,
        headers: ModernHeaders?,
        onNotification: suspend (JsonObject) -> Unit = {},
    ): ModernReply = when (val validation = validate(message, headers, ::toolInputSchema)) {
        is Validation.Notification -> ModernReply.Accepted
        is Validation.Rejected -> response(validation.message)
        is Validation.Valid ->
            if (validation.request.method == LISTEN_METHOD) listen(validation.request, onNotification) else serve(validation.request, onNotification)
    }

    /**
     * Ends every open `subscriptions/listen` stream with its final result, so each [handle] call serving
     * one returns, and refuses new ones the same way. Call it before the server shuts down. Other
     * requests are not affected.
     */
    public suspend fun close() {
        subscriptions.close()
    }

    /**
     * `subscriptions/listen`: the acknowledgement first, then every notification the filter lets through,
     * until the client goes away (the call is cancelled) or [close] ends the stream with its result.
     */
    private suspend fun listen(request: ValidRequest, onNotification: suspend (JsonObject) -> Unit): ModernReply {
        val requested = checkNotNull(SubscriptionFilter.parse(request.params["notifications"])) { "validated" }
        val serverInfo = subscriptions.listen(requested, request.id) { stream ->
            onNotification(acknowledgement(stream.filter, request.id))
            for (event in stream.events) onNotification(event)
            stream.serverInfo
        }
        return response(rpcResult(request.id, listenResult(request.id, serverInfo)))
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
        val round = when (val known = knownAnswers(request)) {
            is RequestStateCodec.Opened.Rejected -> return response(rpcError(request.id, ModernErrorCodes.INVALID_PARAMS, known.message))
            is RequestStateCodec.Opened.Ok -> InputRound(known.answers)
        }
        val open = AtomicBoolean(true)
        val relay: suspend (JsonObject) -> Unit = { notification ->
            if (open.get() && belongsOnStream(notification, request)) onNotification(notification)
        }
        return try {
            withTimeout(config.requestTimeout) {
                val session = SyntheticSession(server, request.meta.clientCapabilities, request.meta.clientInfo, config.inputRequestWindow)
                session.run(relay, { serverRequest -> decide(serverRequest, request, round) }) { initializeResult, forward ->
                    if (request.method == DISCOVER_METHOD) {
                        val result = discoverResult(initializeResult, config.cachePolicy(CacheableMethod.ServerDiscover))
                        response(rpcResult(request.id, result))
                    } else {
                        response(answer(request, forward(forwardedRequest(request)), serverInfo(initializeResult), round))
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

    /**
     * The answers an MRTR retry brings: those sealed in its `requestState` from earlier rounds, then its
     * `inputResponses`. Keys no question asks for are ignored later, as SEP-2322 recommends.
     */
    private fun knownAnswers(request: ValidRequest): RequestStateCodec.Opened {
        val sealed = when (val state = request.requestState) {
            null -> emptyMap()
            else -> when (val opened = requestStates.open(state, request.method, request.params)) {
                is RequestStateCodec.Opened.Rejected -> return opened
                is RequestStateCodec.Opened.Ok -> opened.answers
            }
        }
        return RequestStateCodec.Opened.Ok(sealed + request.inputResponses)
    }

    /** The request as the SDK session sees it: the bridge's id, `_meta` without the modern keys, no MRTR fields. */
    private fun forwardedRequest(request: ValidRequest): JsonObject {
        val plain = request.params.without(INPUT_RESPONSES, REQUEST_STATE)
        val params = if (request.meta.passThrough.isEmpty()) {
            plain.without("_meta")
        } else {
            plain.with("_meta", request.meta.passThrough)
        }
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", FORWARDED_ID)
            put("method", request.method)
            put("params", params)
        }
    }

    private fun answer(request: ValidRequest, outcome: SyntheticOutcome, serverInfo: JsonObject?, round: InputRound): JsonObject =
        when (outcome) {
            is SyntheticOutcome.Aborted -> errorResponse(request, outcome.error)
            is SyntheticOutcome.InputRequired -> inputRequired(request, outcome.inputRequests, serverInfo, round)
            is SyntheticOutcome.Answered -> {
                val message = outcome.message
                val missing = missingCapabilityIn(message, request.meta.clientCapabilities)
                val error = message.obj("error")
                val urlElicitations = error?.let(::urlElicitationsIn)?.takeIf { request.method in INPUT_REQUIRED_METHODS }
                when {
                    missing != null -> errorResponse(request, missing.error())
                    urlElicitations != null -> urlElicitationRequired(request, urlElicitations, serverInfo, round)
                    error != null -> errorResponse(request, modernError(error))
                    else -> {
                        val cache = CacheableMethod.of(request.method)?.let(config::cachePolicy)
                            // SEP-2549: the result of an MRTR retry must not be cached.
                            ?.let { if (request.isRetry) CachePolicy(Duration.ZERO, it.scope) else it }
                        rpcResult(request.id, modernResult(request.method, message.obj("result") ?: EMPTY_OBJECT, serverInfo, cache))
                    }
                }
            }
        }

    /** `resultType: "input_required"`, with the answers used so far sealed in `requestState`. */
    private fun inputRequired(
        request: ValidRequest,
        inputRequests: Map<String, JsonObject>,
        serverInfo: JsonObject?,
        round: InputRound,
    ): JsonObject = rpcResult(
        request.id,
        inputRequiredResult(inputRequests, requestStates.seal(request.method, request.params, round.used()), serverInfo),
    )

    /**
     * The SDK's -32042 (a handler threw `UrlElicitationRequiredException`), which 2026-07-28 retired: its
     * URL elicitations become input requests. The handler decides on the retry whether it still needs them.
     */
    private fun urlElicitationRequired(
        request: ValidRequest,
        elicitations: List<JsonObject>,
        serverInfo: JsonObject?,
        round: InputRound,
    ): JsonObject {
        if (RequiredCapability.ELICITATION_URL.isMissingFrom(request.meta.clientCapabilities)) {
            return errorResponse(request, RequiredCapability.ELICITATION_URL.error())
        }
        val inputRequests = elicitations.associate { params ->
            round.keys.keyFor(buildJsonObject {
                put("method", ELICITATION_METHOD)
                put("params", params)
            }) to inputRequestOf(ELICITATION_METHOD, params)
        }
        return inputRequired(request, inputRequests, serverInfo, round)
    }

    private fun errorResponse(request: ValidRequest, error: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", request.id)
        put("error", error)
    }

    /**
     * What a request from the server gets while a modern request runs: `ping` is answered here; a
     * question for a capability the client lacks ends the call with -32021; on the MRTR methods a
     * question is answered from the retry's answers, or else becomes an input request; elsewhere it is
     * refused, since only `tools/call`, `prompts/get` and `resources/read` may answer `input_required`.
     */
    private fun decide(serverRequest: JsonObject, request: ValidRequest, round: InputRound): ServerRequestDecision {
        val method = serverRequest.string("method")
        if (method == "ping") return ServerRequestDecision.Answer(EMPTY_OBJECT)
        val capability = capabilityFor(serverRequest) ?: return ServerRequestDecision.Refuse(
            ModernErrorCodes.METHOD_NOT_FOUND,
            "$method is not available to a 2026-07-28 client",
        )
        if (capability.isMissingFrom(request.meta.clientCapabilities)) return ServerRequestDecision.Abort(capability.error())
        if (request.method !in INPUT_REQUIRED_METHODS) {
            return ServerRequestDecision.Refuse(
                ModernErrorCodes.METHOD_NOT_FOUND,
                "$method cannot reach a 2026-07-28 client during ${request.method}; only tools/call, prompts/get and resources/read may ask for input",
            )
        }
        val key = round.keys.keyFor(serverRequest)
        val answer = round.answer(key) ?: return ServerRequestDecision.Ask(
            key,
            inputRequestOf(method.orEmpty(), serverRequest.obj("params") ?: EMPTY_OBJECT),
        )
        return ServerRequestDecision.Answer(answer)
    }

    /** One MRTR round: the answers a retry brings, the keys of this run's questions, and the answers used. */
    private class InputRound(private val answers: Map<String, JsonObject>) {
        val keys = InputKeys()
        private val used = ConcurrentHashMap<String, JsonObject>()

        fun answer(key: String): JsonObject? = answers[key]?.also { used[key] = it }

        /** The answers this run consumed, in a stable order; only these travel on in `requestState`. */
        fun used(): Map<String, JsonObject> = used.toSortedMap()
    }

    /**
     * Only progress for this request's token, and log messages the request asked for, go on its stream.
     * Everything else (list_changed and resources/updated broadcasts that reach every SDK session) is
     * dropped here; `subscriptions/listen` carries those.
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
