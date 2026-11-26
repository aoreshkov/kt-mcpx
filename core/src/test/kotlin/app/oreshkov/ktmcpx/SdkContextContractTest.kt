package app.oreshkov.ktmcpx

import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.shared.currentRequestHandlerExtra
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the SDK behaviour request-scoped logs rely on: the SDK calls `Transport.send` for a handler's
 * notification in the handler's own coroutine, with its `RequestHandlerExtra` installed. If an SDK bump
 * breaks this, the bridge would silently drop every log; this test fails instead.
 */
class SdkContextContractTest {
    /** Records, for each notification sent, the request whose handler sent it. */
    private class RecordingTransport : AbstractTransport() {
        val origins = Channel<Pair<RequestId?, RequestId?>>(Channel.UNLIMITED)
        val replies = Channel<JSONRPCMessage>(Channel.UNLIMITED)

        override suspend fun start() = Unit

        override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
            if (message is JSONRPCNotification) {
                origins.send(currentRequestHandlerExtra()?.requestId to options?.relatedRequestId)
            } else {
                replies.send(message)
            }
        }

        override suspend fun close() = invokeOnCloseCallback()

        suspend fun deliver(message: JSONRPCMessage) = _onMessage(message)
    }

    @Test
    fun `a handler's notification is sent in its coroutine, with its request id in the context`() = runBlocking {
        val server = testServer {
            addTool("log", "Logs once.") {
                sendLoggingMessage(LoggingMessageNotification(LoggingMessageNotificationParams(LoggingLevel.Info, JsonPrimitive("x"))))
                CallToolResult(content = emptyList())
            }
        }
        val transport = RecordingTransport()
        val session = server.createSession(transport)
        try {
            val initialize = buildJsonObject {
                put("protocolVersion", "2025-11-25")
                put("capabilities", JsonObject(emptyMap()))
                put("clientInfo", buildJsonObject {
                    put("name", "contract")
                    put("version", "1")
                })
            }
            transport.deliver(JSONRPCRequest(RequestId("init"), Method.Defined.Initialize.value, initialize))
            withTimeout(5.seconds) { transport.replies.receive() }
            transport.deliver(JSONRPCNotification(Method.Defined.NotificationsInitialized.value))

            val callId = RequestId("call-1")
            transport.deliver(JSONRPCRequest(callId, Method.Defined.ToolsCall.value, buildJsonObject { put("name", "log") }))

            val (origin, related) = withTimeout(5.seconds) { transport.origins.receive() }
            assertEquals(callId, origin)
            assertNull(related, "the origin comes from the context alone")
            withTimeout(5.seconds) { transport.replies.receive() }
        } finally {
            session.close()
        }
    }
}
