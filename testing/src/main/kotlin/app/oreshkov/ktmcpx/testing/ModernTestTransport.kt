package app.oreshkov.ktmcpx.testing

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readLine
import kotlinx.io.writeString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How a [ModernTestClient] reaches the server under test: it sends one JSON-RPC body and returns every
 * message of the answer.
 */
public interface ModernTestTransport {
    /**
     * `true` when answers carry an HTTP status and requests carry headers. On stdio it is `false`, and
     * [ModernContract] skips its header and status checks.
     */
    public val isHttp: Boolean

    /**
     * Sends [body] as one message. [headers] are the HTTP headers for it, and a `null` value removes a
     * header the transport would otherwise add; a transport that is not HTTP ignores them.
     */
    public suspend fun send(body: String, headers: Map<String, String?>): ModernResponse
}

/**
 * Posts each body to [url] with [http]. [extraHeaders] go on every request, under the ones a call
 * passes. The SDK's DNS-rebinding protection rejects requests without a `Host` header, so tests against a
 * localhost-protected route pass `mapOf("Host" to "localhost")`.
 */
public class ModernHttpTestTransport(
    private val http: HttpClient,
    private val url: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : ModernTestTransport {
    override val isHttp: Boolean get() = true

    override suspend fun send(body: String, headers: Map<String, String?>): ModernResponse {
        val merged = extraHeaders.toMutableMap<String, String?>()
        headers.forEach { (name, value) ->
            merged.keys.filter { it.equals(name, ignoreCase = true) }.forEach(merged::remove)
            merged[name] = value
        }
        val response = http.post(url) {
            merged.forEach { (name, value) -> if (value != null) header(name, value) }
            setBody(TextContent(body, ContentType.Application.Json))
        }
        return ModernResponse(response.status.value, response.headers[HttpHeaders.ContentType], response.bodyAsText())
    }
}

/**
 * Talks to a newline-delimited JSON-RPC server: it writes each body as a line to [toServer] (the server's
 * stdin) and reads [fromServer] (its stdout) in a coroutine launched in [scope], which ends when
 * [fromServer] does. [withModernStdioClient] wires one to `serveDualEraStdio` in memory; pass a process's
 * streams to test a real server binary.
 *
 * Calls run one at a time, so every line that arrives between a request and its response belongs to that
 * answer, as do lines that arrived while no call was running (a notification the server sent late shows
 * up in the next answer). A request waits up to [timeout] for its response; a notification returns at
 * once with no messages.
 */
public class ModernStdioTestTransport(
    scope: CoroutineScope,
    fromServer: Source,
    private val toServer: Sink,
    private val timeout: Duration = 10.seconds,
) : ModernTestTransport {
    private val lines = Channel<JsonObject>(Channel.UNLIMITED)
    private val mutex = Mutex()

    init {
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val line = fromServer.readLine() ?: break
                    if (line.isBlank()) continue
                    val message = try {
                        Json.parseToJsonElement(line) as? JsonObject
                    } catch (_: SerializationException) {
                        null
                    }
                    lines.send(message ?: throw IllegalStateException("The server wrote a line that is not a JSON-RPC object: $line"))
                }
                lines.close()
            } catch (e: Throwable) {
                lines.close(e)
            }
        }
    }

    override val isHttp: Boolean get() = false

    override suspend fun send(body: String, headers: Map<String, String?>): ModernResponse = mutex.withLock {
        require('\n' !in body) { "A stdio message must be one line" }
        withContext(Dispatchers.IO) {
            toServer.writeString(body)
            toServer.writeString("\n")
            toServer.flush()
        }
        val awaited = responseIdFor(body) ?: return@withLock ModernResponse.ofLines(drain())
        val received = drain().toMutableList()
        if (received.none { it.isResponseTo(awaited) }) {
            val answered = try {
                withTimeoutOrNull(timeout) {
                    while (true) {
                        val message = lines.receive()
                        received += message
                        if (message.isResponseTo(awaited)) break
                    }
                }
            } catch (e: ClosedReceiveChannelException) {
                throw IllegalStateException("The server's output ended before the response to $awaited; got $received", e)
            }
            // Not a CancellationException, so a contract check reports it instead of stopping the run.
            checkNotNull(answered) { "No response to $awaited within $timeout; got $received" }
        }
        ModernResponse.ofLines(received)
    }

    private fun drain(): List<JsonObject> = buildList {
        while (true) add(lines.tryReceive().getOrNull() ?: break)
    }

    /**
     * The id of the response [body] gets: its own id, or `null` (JSON) when the server cannot read one; a
     * Kotlin `null` for a notification, which gets nothing.
     */
    private fun responseIdFor(body: String): JsonElement? {
        val message = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return JsonNull
        val id = message["id"]
        return when {
            id != null -> id
            "method" in message -> null
            else -> JsonNull
        }
    }

    private fun JsonObject.isResponseTo(id: JsonElement): Boolean = "method" !in this && this["id"] == id
}
