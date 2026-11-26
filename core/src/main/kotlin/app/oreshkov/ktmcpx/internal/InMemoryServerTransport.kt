package app.oreshkov.ktmcpx.internal

import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestHandlerExtra
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.shared.currentRequestHandlerExtra
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCEmptyMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.Method
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
 *
 * A `notifications/message` reaches [onNotification] only when it comes from the handler of
 * [ownRequestId]: 2026-07-28 allows a log message only on the stream of the request that set the log
 * level, so a broadcast an adopter sends to every session in `server.sessions` stops here. The origin is
 * the send's `relatedRequestId`, or else the [RequestHandlerExtra] of the coroutine that sends, since the
 * SDK calls [send] in the caller's coroutine (pinned by `SdkContextContractTest`).
 *
 * Every session kt-mcpx creates uses this transport, and no other session does: `isKtMcpxSession` relies
 * on that, so it must stay internal.
 */
internal class InMemoryServerTransport(
    /** The forwarded request whose handler may log on this session; `null` when no handler may (the listener). */
    private val ownRequestId: RequestId?,
    private val onNotification: suspend (JSONRPCNotification) -> Unit,
    private val onServerRequest: suspend (JSONRPCRequest) -> JSONRPCMessage?,
) : AbstractTransport() {
    private val pending = ConcurrentHashMap<RequestId, CompletableDeferred<JSONRPCMessage>>()

    override suspend fun start() = Unit

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        when (message) {
            is JSONRPCResponse -> pending.remove(message.id)?.complete(message)
            is JSONRPCError -> message.id?.let { pending.remove(it) }?.complete(message)
            is JSONRPCNotification -> if (isOwn(message, options)) onNotification(message)
            is JSONRPCRequest -> onServerRequest(message)?.let { _onMessage(it) }
            is JSONRPCEmptyMessage -> Unit
        }
    }

    /** Whether [notification] may go on: any notification but a log message, or a log from [ownRequestId]'s handler. */
    private suspend fun isOwn(notification: JSONRPCNotification, options: TransportSendOptions?): Boolean {
        if (notification.method != Method.Defined.NotificationsMessage.value) return true
        val origin = options?.relatedRequestId ?: currentRequestHandlerExtra()?.requestId
        return origin != null && origin == ownRequestId
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
