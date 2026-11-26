package app.oreshkov.ktmcpx.internal

import app.oreshkov.ktmcpx.ModernErrorCodes
import app.oreshkov.ktmcpx.ModernMetaKeys
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal const val LISTEN_METHOD: String = "subscriptions/listen"

/** The `_meta` key that ties a notification to the `subscriptions/listen` stream it travels on. */
internal const val SUBSCRIPTION_ID: String = "io.modelcontextprotocol/subscriptionId"

/**
 * A `SubscriptionFilter` (SEP-2575): the notification types one `subscriptions/listen` stream opted
 * into. Each type is opt-in; anything not named is never sent on the stream.
 */
internal class SubscriptionFilter(
    val toolsListChanged: Boolean = false,
    val promptsListChanged: Boolean = false,
    val resourcesListChanged: Boolean = false,
    /** `null` when the client did not ask for resource updates at all. */
    val resourceSubscriptions: Set<String>? = null,
) {
    /** What the server can honour of this filter, given its legacy capabilities. */
    fun honoredBy(serverCapabilities: JsonObject): SubscriptionFilter {
        fun flag(capability: String, key: String) =
            (serverCapabilities.obj(capability)?.get(key) as? JsonPrimitive)?.content == "true"
        return SubscriptionFilter(
            toolsListChanged = toolsListChanged && flag("tools", "listChanged"),
            promptsListChanged = promptsListChanged && flag("prompts", "listChanged"),
            resourcesListChanged = resourcesListChanged && flag("resources", "listChanged"),
            resourceSubscriptions = resourceSubscriptions?.takeIf { flag("resources", "subscribe") },
        )
    }

    fun accepts(notification: JsonObject): Boolean = when (notification.string("method")) {
        "notifications/tools/list_changed" -> toolsListChanged
        "notifications/prompts/list_changed" -> promptsListChanged
        "notifications/resources/list_changed" -> resourcesListChanged
        "notifications/resources/updated" ->
            resourceSubscriptions?.contains(notification.obj("params")?.string("uri")) == true
        else -> false
    }

    /** The filter as the `notifications` object of an acknowledgement. Types left out are not honoured. */
    fun toJson(): JsonObject = buildJsonObject {
        if (toolsListChanged) put("toolsListChanged", true)
        if (promptsListChanged) put("promptsListChanged", true)
        if (resourcesListChanged) put("resourcesListChanged", true)
        resourceSubscriptions?.let { uris -> put("resourceSubscriptions", buildJsonArray { uris.forEach { add(JsonPrimitive(it)) } }) }
    }

    companion object {
        /** Parses `params.notifications`; `null` when it is not a valid filter. */
        fun parse(element: JsonElement?): SubscriptionFilter? {
            val json = element as? JsonObject ?: return null
            fun flag(key: String): Boolean? = when (val value = json[key]) {
                null -> false
                is JsonPrimitive -> value.content.toBooleanStrictOrNull()?.takeIf { !value.isString }
                else -> null
            }
            val uris = when (val value = json["resourceSubscriptions"]) {
                null -> null
                is JsonArray -> value.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }.toSet()
                else -> return null
            }
            return SubscriptionFilter(
                toolsListChanged = flag("toolsListChanged") ?: return null,
                promptsListChanged = flag("promptsListChanged") ?: return null,
                resourcesListChanged = flag("resourcesListChanged") ?: return null,
                resourceSubscriptions = uris,
            )
        }
    }
}

/**
 * The one long-lived SDK session of a bridge that receives the server's `list_changed` and
 * `resources/updated` notifications, and the `subscriptions/listen` streams they fan out to.
 *
 * The session opens with the first stream and closes after the last one. Every SDK session gets
 * `list_changed` broadcasts; `resources/updated` reaches only sessions that sent `resources/subscribe`,
 * so the hub subscribes its session to each URI some open stream asks for, and unsubscribes when none
 * does. An adopter that sends notifications to every entry of `server.sessions` reaches it too.
 */
internal class SubscriptionHub(private val server: Server) {
    private val mutex = Mutex()
    private val streams = ConcurrentHashMap.newKeySet<Stream>()
    private val resourceRefs = mutableMapOf<String, Int>()
    private val requestIds = AtomicLong()
    private var open: Open? = null
    private var closed = false

    private class Open(val transport: InMemoryServerTransport, val session: ServerSession, val initializeResult: JsonObject) {
        val capabilities: JsonObject get() = initializeResult.obj("capabilities") ?: EMPTY_OBJECT
    }

    /** One open `subscriptions/listen` stream. [events] closes when the bridge shuts the stream down. */
    class Stream(val filter: SubscriptionFilter, val id: JsonElement, val serverInfo: JsonObject?) {
        val events: Channel<JsonObject> = Channel(Channel.UNLIMITED)
    }

    /**
     * Opens a stream for [requested], narrowed to what the server can honour, runs [block] with it,
     * and closes it again however [block] ends. `null` once the hub is closed.
     */
    suspend fun <T> listen(requested: SubscriptionFilter, id: JsonElement, block: suspend (Stream) -> T): T? {
        val stream = register(requested, id) ?: return null
        try {
            return block(stream)
        } finally {
            withContext(NonCancellable) { unregister(stream) }
        }
    }

    /** Ends every open stream gracefully and closes the session. Later [listen] calls return `null`. */
    suspend fun close() {
        mutex.withLock {
            closed = true
            streams.forEach { it.events.close() }
        }
    }

    private suspend fun register(requested: SubscriptionFilter, id: JsonElement): Stream? = mutex.withLock {
        if (closed) return null
        val hub = open ?: start().also { open = it }
        val stream = Stream(requested.honoredBy(hub.capabilities), id, serverInfo(hub.initializeResult))
        stream.filter.resourceSubscriptions.orEmpty().forEach { uri ->
            val count = resourceRefs[uri] ?: 0
            if (count == 0) hub.transport.request(Method.Defined.ResourcesSubscribe.value, uri)
            resourceRefs[uri] = count + 1
        }
        streams += stream
        stream
    }

    private suspend fun unregister(stream: Stream) = mutex.withLock {
        if (!streams.remove(stream)) return@withLock
        val hub = open ?: return@withLock
        stream.filter.resourceSubscriptions.orEmpty().forEach { uri ->
            val count = (resourceRefs[uri] ?: 1) - 1
            if (count == 0) {
                resourceRefs -= uri
                hub.transport.request(Method.Defined.ResourcesUnsubscribe.value, uri)
            } else {
                resourceRefs[uri] = count
            }
        }
        if (streams.isEmpty()) {
            open = null
            hub.session.close()
        }
    }

    private suspend fun start(): Open {
        val transport = InMemoryServerTransport(
            onNotification = { dispatch(it.toJsonObject()) },
            onServerRequest = { request ->
                rpcError(McpIdJson.encode(request.id), ModernErrorCodes.METHOD_NOT_FOUND, "The kt-mcpx listener session answers no requests")
                    .toRpcMessage()
            },
        )
        val session = server.createSession(transport)
        return Open(transport, session, transport.initializeSession(EMPTY_OBJECT, LISTENER_CLIENT_INFO))
    }

    private fun dispatch(notification: JsonObject) {
        for (stream in streams) {
            if (stream.filter.accepts(notification)) stream.events.trySend(tagged(notification, stream.id))
        }
    }

    private suspend fun InMemoryServerTransport.request(method: String, uri: String) {
        val id = RequestId("kt-mcpx-listener-${requestIds.incrementAndGet()}")
        exchange(JSONRPCRequest(id, method, buildJsonObject { put("uri", uri) }))
    }

    private companion object {
        val LISTENER_CLIENT_INFO: JsonObject = buildJsonObject {
            put("name", "kt-mcpx-listener")
            put("version", "0")
        }
    }
}

/** [notification] with `params._meta[subscriptionId]` set to [subscriptionId]. */
internal fun tagged(notification: JsonObject, subscriptionId: JsonElement): JsonObject {
    val params = notification.obj("params") ?: EMPTY_OBJECT
    val meta = (params.obj("_meta") ?: EMPTY_OBJECT).with(SUBSCRIPTION_ID, subscriptionId)
    return notification.with("params", params.with("_meta", meta))
}

/** The `SubscriptionsListenResult` that ends a stream gracefully. */
internal fun listenResult(subscriptionId: JsonElement, serverInfo: JsonObject?): JsonObject = buildJsonObject {
    put("resultType", "complete")
    put("_meta", buildJsonObject {
        put(SUBSCRIPTION_ID, subscriptionId)
        if (serverInfo != null) put(ModernMetaKeys.SERVER_INFO, serverInfo)
    })
}

/** `notifications/subscriptions/acknowledged`, the first message on a stream. */
internal fun acknowledgement(filter: SubscriptionFilter, subscriptionId: JsonElement): JsonObject = tagged(
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "notifications/subscriptions/acknowledged")
        put("params", buildJsonObject { put("notifications", filter.toJson()) })
    },
    subscriptionId,
)
