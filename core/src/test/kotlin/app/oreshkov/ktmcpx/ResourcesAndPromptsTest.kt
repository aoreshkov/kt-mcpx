package app.oreshkov.ktmcpx

import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class ResourcesAndPromptsTest {
    private val server = testServer(
        capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(),
            resources = ServerCapabilities.Resources(listChanged = true, subscribe = true),
            prompts = ServerCapabilities.Prompts(listChanged = true),
        ),
    ) {
        addResource("test://a", "a", "Resource a.", "text/plain") { request ->
            ReadResourceResult(listOf(TextResourceContents("content of a", request.params.uri, "text/plain")))
        }
        addResourceTemplate("test://item/{id}", "item", "An item.", "text/plain") { request, variables ->
            ReadResourceResult(listOf(TextResourceContents("item ${variables["id"]}", request.params.uri, "text/plain")))
        }
        addPrompt("greet", "Greets someone.", listOf(PromptArgument("who", required = true))) { request ->
            GetPromptResult(listOf(PromptMessage(Role.User, TextContent("Hello, ${request.params.arguments?.get("who")}"))))
        }
    }

    private val bridge = ModernBridge(server) {
        cache(CacheableMethod.ResourcesRead, ttl = 5.minutes, scope = CacheScope.Public)
    }

    private suspend fun send(text: String) = bridge.handle(text, headersFor(text)).response

    @Test
    fun `resources and templates are listed with cache hints`() = runBlocking {
        val list = send(body("resources/list")).result
        assertEquals(listOf("test://a"), list["resources"]!!.jsonArray.map { it.jsonObject["uri"]!!.jsonPrimitive.content })
        assertEquals("complete", list["resultType"]!!.jsonPrimitive.content)
        assertEquals(CachePolicy.DEFAULT.ttl.inWholeMilliseconds, list["ttlMs"]!!.jsonPrimitive.long)

        val templates = send(body("resources/templates/list")).result
        assertEquals(
            listOf("test://item/{id}"),
            templates["resourceTemplates"]!!.jsonArray.map { it.jsonObject["uriTemplate"]!!.jsonPrimitive.content },
        )
        assertEquals("private", templates["cacheScope"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a template read reaches the template handler with the configured hint`() = runBlocking {
        val read = send(body("resources/read", buildJsonObject { put("uri", "test://item/42") })).result
        assertEquals("item 42", read["contents"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(5.minutes.inWholeMilliseconds, read["ttlMs"]!!.jsonPrimitive.long)
        assertEquals("public", read["cacheScope"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unknown resource is -32602 with the uri, HTTP 400`() = runBlocking {
        val reply = send(body("resources/read", buildJsonObject { put("uri", "test://missing") }))
        assertEquals(ModernErrorCodes.INVALID_PARAMS, reply.code)
        assertEquals(400, reply.httpStatus)
        assertEquals("test://missing", reply.error["data"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
    }

    @Test
    fun `prompts are listed and rendered`() = runBlocking {
        val list = send(body("prompts/list")).result
        assertEquals("greet", list["prompts"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("complete", list["resultType"]!!.jsonPrimitive.content)

        val get = send(
            body("prompts/get", buildJsonObject {
                put("name", "greet")
                put("arguments", buildJsonObject { put("who", "Ada") })
            }),
        ).result
        val message = get["messages"]!!.jsonArray.single().jsonObject
        assertEquals("Hello, Ada", message["content"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertNull(get["ttlMs"], "prompts/get is not cacheable")
    }

    @Test
    fun `discover drops listChanged and subscribe until subscriptions exist`() = runBlocking {
        val capabilities = send(body("server/discover")).result["capabilities"]!!.jsonObject
        assertEquals(emptySet(), capabilities["resources"]!!.jsonObject.keys)
        assertEquals(emptySet(), capabilities["prompts"]!!.jsonObject.keys)
    }
}
