package app.oreshkov.ktmcpx.internal

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration

/** The revision the synthetic `initialize` asks for: the newest one SDK 0.16.0 serves. */
internal const val LEGACY_PROTOCOL_VERSION: String = "2025-11-25"

private val INITIALIZE_ID = RequestId("kt-mcpx-initialize")

/** The id the forwarded request carries inside the synthetic session; the client's id is restored after. */
internal val FORWARDED_ID: JsonElement = JsonPrimitive("kt-mcpx-request")

/** How a forwarded request ended. */
internal sealed interface SyntheticOutcome {
    /** The session answered: a JSON-RPC response or error, still carrying [FORWARDED_ID]. */
    class Answered(val message: JsonObject) : SyntheticOutcome

    /** The bridge ended the call early with this error (code, message, data), e.g. -32021. */
    class Aborted(val error: JsonObject) : SyntheticOutcome

    /**
     * The handler is waiting on questions only the client can answer: the 2026-07-28 input requests,
     * by key, in the order they were asked.
     */
    class InputRequired(val inputRequests: Map<String, JsonObject>) : SyntheticOutcome
}

/** What the bridge does with a request the server sends while a call runs. */
internal sealed interface ServerRequestDecision {
    /** Feed this result back to the session. */
    class Answer(val result: JsonObject) : ServerRequestDecision

    /** Feed this JSON-RPC error back to the session; the handler sees an exception. */
    class Refuse(val code: Int, val message: String) : ServerRequestDecision

    /** End the whole call with this error object. */
    class Abort(val error: JsonObject) : ServerRequestDecision

    /**
     * Leave the request unanswered and pass it to the client as the input request [inputRequest] under
     * [key]. The call ends with [SyntheticOutcome.InputRequired] once no further question has arrived
     * for the session's input window.
     */
    class Ask(val key: String, val inputRequest: JsonObject) : ServerRequestDecision
}

/**
 * One short-lived [io.modelcontextprotocol.kotlin.sdk.server.ServerSession] of [server] per
 * modern request.
 *
 * The session gets a synthetic legacy `initialize` built from the modern `_meta` (client capabilities
 * and client info), then `notifications/initialized`. `createSession` fires `onConnect`, so the adopter's
 * per-session handlers are installed. The session is closed before [run] returns, whatever happens.
 */
internal class SyntheticSession(
    private val server: Server,
    private val clientCapabilities: JsonObject,
    private val clientInfo: JsonObject?,
    /** How long to wait for further questions after one the client must answer (see [ServerRequestDecision.Ask]). */
    private val inputWindow: Duration = Duration.ZERO,
) {
    private val abort = CompletableDeferred<JsonObject>()
    private val asked = LinkedHashMap<String, JsonObject>()
    private val askedSignal = Channel<Unit>(Channel.CONFLATED)

    /**
     * Opens the session, initializes it, and passes the `initialize` result to [block] together with a
     * function that forwards one request through it.
     */
    suspend fun <T> run(
        onNotification: suspend (JsonObject) -> Unit,
        onServerRequest: suspend (JsonObject) -> ServerRequestDecision,
        block: suspend (initializeResult: JsonObject, forward: suspend (JsonObject) -> SyntheticOutcome) -> T,
    ): T {
        val transport = InMemoryServerTransport(
            onNotification = { onNotification(it.toJsonObject()) },
            onServerRequest = { request -> decide(request, onServerRequest(request.toJsonObject())) },
        )
        val session = server.createSession(transport)
        try {
            val initializeResult = transport.initialize()
            return block(initializeResult) { request -> transport.forward(request) }
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }

    private fun decide(request: JSONRPCRequest, decision: ServerRequestDecision): JSONRPCMessage? = when (decision) {
        is ServerRequestDecision.Answer ->
            rpcResult(McpIdJson.encode(request.id), decision.result).toRpcMessage()
        is ServerRequestDecision.Refuse ->
            rpcError(McpIdJson.encode(request.id), decision.code, decision.message).toRpcMessage()
        is ServerRequestDecision.Abort -> {
            abort.complete(decision.error)
            null
        }
        is ServerRequestDecision.Ask -> {
            synchronized(asked) { asked[decision.key] = decision.inputRequest }
            askedSignal.trySend(Unit)
            null
        }
    }

    private suspend fun InMemoryServerTransport.initialize(): JsonObject =
        initializeSession(clientCapabilities, clientInfo ?: SYNTHETIC_CLIENT_INFO)

    private suspend fun InMemoryServerTransport.forward(request: JsonObject): SyntheticOutcome = coroutineScope {
        val rpcRequest = request.toRpcMessage() as JSONRPCRequest
        val reply = async { exchange(rpcRequest) }
        // Settles once a question has arrived and no other one followed within the window.
        val questionsSettled = async {
            askedSignal.receive()
            do {
                val another = withTimeoutOrNull(inputWindow) { askedSignal.receive() }
            } while (another != null)
        }
        val outcome = select {
            reply.onAwait { message ->
                SyntheticOutcome.Answered(message.toJsonObject())
            }
            abort.onAwait { error -> SyntheticOutcome.Aborted(error) }
            questionsSettled.onAwait { SyntheticOutcome.InputRequired(synchronized(asked) { LinkedHashMap(asked) }) }
        }
        reply.cancel()
        questionsSettled.cancel()
        outcome
    }

    private companion object {
        val SYNTHETIC_CLIENT_INFO: JsonObject = buildJsonObject {
            put("name", "kt-mcpx-synthetic")
            put("version", "0")
        }
    }
}

/**
 * Runs the legacy handshake on a fresh session: `initialize` at [LEGACY_PROTOCOL_VERSION] with
 * [capabilities] and [clientInfo], then `notifications/initialized`. Returns the `initialize` result.
 */
internal suspend fun InMemoryServerTransport.initializeSession(capabilities: JsonObject, clientInfo: JsonObject): JsonObject {
    val params = buildJsonObject {
        put("protocolVersion", LEGACY_PROTOCOL_VERSION)
        put("capabilities", capabilities)
        put("clientInfo", clientInfo)
    }
    val answer = exchange(JSONRPCRequest(INITIALIZE_ID, Method.Defined.Initialize.value, params))
    check(answer is JSONRPCResponse) { "The SDK refused the synthetic initialize: ${answer.toJsonObject()}" }
    notify(JSONRPCNotification(Method.Defined.NotificationsInitialized.value))
    return answer.toJsonObject().obj("result") ?: EMPTY_OBJECT
}

/** Encodes an SDK [RequestId] back to its JSON form. */
internal object McpIdJson {
    fun encode(id: RequestId): JsonElement = when (id) {
        is RequestId.StringId -> JsonPrimitive(id.value)
        is RequestId.NumberId -> JsonPrimitive(id.value)
    }
}
