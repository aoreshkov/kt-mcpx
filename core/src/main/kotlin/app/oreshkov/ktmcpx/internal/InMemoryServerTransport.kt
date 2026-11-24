package app.oreshkov.ktmcpx.internal

import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCEmptyMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * The client end of one SDK [io.modelcontextprotocol.kotlin.sdk.server.ServerSession], in memory.
 *
 * [exchange] hands a request to the session and suspends until the session answers it. Everything
 * else the session sends goes to a callback: notifications to [onNotification], and requests from the
 * server to [onServerRequest]. When that returns a message, it is fed back to the session as the
 * answer; when it returns `null`, the request stays unanswered (the bridge has aborted the call, and
 * closing the session cancels the handler that is waiting).
 */
internal class InMemoryServerTransport(
    private val onNotification: suspend (JSONRPCNotification) -> Unit,
    private val onServerRequest: suspend (JSONRPCRequest) -> JSONRPCMessage?,
) : AbstractTransport() {
    private val pending = ConcurrentHashMap<RequestId, CompletableDeferred<JSONRPCMessage>>()

    override suspend fun start() = Unit

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        when (message) {
            is JSONRPCResponse -> pending.remove(message.id)?.complete(message)
            is JSONRPCError -> message.id?.let { pending.remove(it) }?.complete(message)
            is JSONRPCNotification -> onNotification(message)
            is JSONRPCRequest -> onServerRequest(message)?.let { _onMessage(it) }
            is JSONRPCEmptyMessage -> Unit
        }
    }

    override suspend fun close() {
        pending.values.forEach { it.cancel() }
        pending.clear()
        invokeOnCloseCallback()
    }

    /** Sends [request] to the session and returns its response or error. */
    suspend fun exchange(request: JSONRPCRequest): JSONRPCMessage {
        val reply = CompletableDeferred<JSONRPCMessage>()
        pending[request.id] = reply
        _onMessage(request)
        return reply.await()
    }

    suspend fun notify(notification: JSONRPCNotification) {
        _onMessage(notification)
    }
}
