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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
}

/** What the bridge does with a request the server sends while a call runs. */
internal sealed interface ServerRequestDecision {
    /** Feed this result back to the session. */
    class Answer(val result: JsonObject) : ServerRequestDecision

    /** Feed this JSON-RPC error back to the session; the handler sees an exception. */
    class Refuse(val code: Int, val message: String) : ServerRequestDecision

    /** End the whole call with this error object. */
    class Abort(val error: JsonObject) : ServerRequestDecision
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
) {
    /**
     * Opens the session, initializes it, and passes the `initialize` result to [block] together with a
     * function that forwards one request through it.
     */
    suspend fun <T> run(
        onNotification: suspend (JsonObject) -> Unit,
        onServerRequest: suspend (JsonObject) -> ServerRequestDecision,
        block: suspend (initializeResult: JsonObject, forward: suspend (JsonObject) -> SyntheticOutcome) -> T,
    ): T {
        val abort = CompletableDeferred<JsonObject>()
        val transport = InMemoryServerTransport(
            onNotification = { onNotification(it.toJsonObject()) },
            onServerRequest = { request -> decide(request, onServerRequest(request.toJsonObject()), abort) },
        )
        val session = server.createSession(transport)
        try {
            val initializeResult = transport.initialize()
            return block(initializeResult) { request -> transport.forward(request, abort) }
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }

    private fun decide(
        request: JSONRPCRequest,
        decision: ServerRequestDecision,
        abort: CompletableDeferred<JsonObject>,
    ): JSONRPCMessage? = when (decision) {
        is ServerRequestDecision.Answer ->
            rpcResult(McpIdJson.encode(request.id), decision.result).toRpcMessage()
        is ServerRequestDecision.Refuse ->
            rpcError(McpIdJson.encode(request.id), decision.code, decision.message).toRpcMessage()
        is ServerRequestDecision.Abort -> {
            abort.complete(decision.error)
            null
        }
    }

    private suspend fun InMemoryServerTransport.initialize(): JsonObject {
        val params = buildJsonObject {
            put("protocolVersion", LEGACY_PROTOCOL_VERSION)
            put("capabilities", clientCapabilities)
            put("clientInfo", clientInfo ?: SYNTHETIC_CLIENT_INFO)
        }
        val answer = exchange(JSONRPCRequest(INITIALIZE_ID, Method.Defined.Initialize.value, params))
        check(answer is JSONRPCResponse) { "The SDK refused the synthetic initialize: ${answer.toJsonObject()}" }
        notify(JSONRPCNotification(Method.Defined.NotificationsInitialized.value))
        return answer.toJsonObject().obj("result") ?: EMPTY_OBJECT
    }

    private suspend fun InMemoryServerTransport.forward(
        request: JsonObject,
        abort: CompletableDeferred<JsonObject>,
    ): SyntheticOutcome = coroutineScope {
        val rpcRequest = request.toRpcMessage() as JSONRPCRequest
        val reply = async { exchange(rpcRequest) }
        val outcome = select {
            reply.onAwait { message ->
                SyntheticOutcome.Answered(message.toJsonObject())
            }
            abort.onAwait { error -> SyntheticOutcome.Aborted(error) }
        }
        reply.cancel()
        outcome
    }

    private companion object {
        val SYNTHETIC_CLIENT_INFO: JsonObject = buildJsonObject {
            put("name", "kt-mcpx-synthetic")
            put("version", "0")
        }
    }
}

/** Encodes an SDK [RequestId] back to its JSON form. */
internal object McpIdJson {
    fun encode(id: RequestId): JsonElement = when (id) {
        is RequestId.StringId -> JsonPrimitive(id.value)
        is RequestId.NumberId -> JsonPrimitive(id.value)
    }
}
