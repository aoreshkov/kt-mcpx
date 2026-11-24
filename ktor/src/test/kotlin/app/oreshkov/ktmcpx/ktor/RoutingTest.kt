package app.oreshkov.ktmcpx.ktor

import app.oreshkov.ktmcpx.ModernBridge
import app.oreshkov.ktmcpx.ModernErrorCodes
import app.oreshkov.ktmcpx.fixture.fixtureServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Modern POSTs reach the bridge and everything else reaches the SDK route on the same path,
 * for both SDK route kinds and in both registration orders.
 */
class RoutingTest {
    private enum class SdkRoute { Stateful, Stateless }

    private val initialize =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}"""

    @Test fun `stateful SDK route registered first`() = routingCase(SdkRoute.Stateful, modernFirst = false)

    @Test fun `stateful SDK route registered second`() = routingCase(SdkRoute.Stateful, modernFirst = true)

    @Test fun `stateless SDK route registered first`() = routingCase(SdkRoute.Stateless, modernFirst = false)

    @Test fun `stateless SDK route registered second`() = routingCase(SdkRoute.Stateless, modernFirst = true)

    private fun routingCase(kind: SdkRoute, modernFirst: Boolean) = testApplication {
        val server = fixtureServer()
        val bridge = ModernBridge(server)
        val sdk: Application.() -> Unit = {
            when (kind) {
                SdkRoute.Stateful -> mcpStreamableHttp(path = "/mcp") { server }
                SdkRoute.Stateless -> mcpStatelessStreamableHttp(path = "/mcp") { server }
            }
        }
        val modern: Application.() -> Unit = { routing { dualEra("/mcp") { bridge } } }
        application {
            if (modernFirst) {
                modern()
                sdk()
            } else {
                sdk()
                modern()
            }
        }
        val modernClient = modernClient()

        val discover = modernClient.send("server/discover")
        assertEquals(200, discover.status, discover.text)
        assertEquals("complete", discover.result!!["resultType"].toString().trim('"'))

        // A legacy initialize reaches the SDK, also when the client sends Mcp-Method on every POST.
        for (extra in listOf(emptyMap(), mapOf("Mcp-Method" to "initialize"))) {
            val legacy = client.post("/mcp") {
                header(HttpHeaders.Host, "localhost")
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                extra.forEach { (name, value) -> header(name, value) }
                contentType(ContentType.Application.Json)
                setBody(initialize)
            }
            val body = legacy.bodyAsText()
            assertEquals(HttpStatusCode.OK, legacy.status, body)
            assertTrue("\"protocolVersion\":\"2025-11-25\"" in body, body)
        }

        // What the Kotlin SDK 0.16.0 client sends after initialize (Mcp-Method, no version header) stays legacy.
        // The stateless route serves it; the stateful one wants a session id.
        val sdkClient = client.post("/mcp") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            header("Mcp-Method", "tools/list")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        }
        val sdkBody = sdkClient.bodyAsText()
        assertFalse("io.modelcontextprotocol/protocolVersion" in sdkBody, "reached the bridge: $sdkBody")
        if (kind == SdkRoute.Stateless) {
            assertEquals(HttpStatusCode.OK, sdkClient.status, sdkBody)
            assertTrue("\"test_simple_text\"" in sdkBody, sdkBody)
        }

        // A modern request missing Mcp-Method still reaches the bridge through its version header.
        val noMethod = modernClient.send("tools/list", headers = mapOf("Mcp-Method" to null))
        assertEquals(400, noMethod.status)
        assertEquals(ModernErrorCodes.HEADER_MISMATCH, noMethod.errorCode)

        // GET stays with the SDK: the stateful route wants a session id, the stateless one refuses GET.
        val get = client.get("/mcp") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Accept, "text/event-stream")
        }
        assertEquals(if (kind == SdkRoute.Stateful) HttpStatusCode.BadRequest else HttpStatusCode.MethodNotAllowed, get.status)

        // The SDK's DNS-rebinding protection sits on the shared route(path) node and covers the bridge.
        assertEquals(403, modernClient.send("tools/list", headers = mapOf(HttpHeaders.Host to "evil.example")).status)
        assertEquals(403, modernClient.send("tools/list", headers = mapOf(HttpHeaders.Origin to "https://evil.example")).status)
    }

    @Test
    fun `the selector's rules`() {
        fun modern(vararg pairs: Pair<String, String>) = ModernRequestSelector.isModern(headersOf(*pairs.map { it.first to listOf(it.second) }.toTypedArray()))

        assertTrue(modern("Mcp-Method" to "tools/list", "MCP-Protocol-Version" to "2026-07-28"))
        assertTrue(modern("MCP-Protocol-Version" to "2026-07-28"))
        assertTrue(modern("Mcp-Method" to "initialize", "MCP-Protocol-Version" to "2026-07-28"))
        assertTrue(modern("Mcp-Method" to "server/discover", "MCP-Protocol-Version" to "v999.0.0"))
        assertFalse(modern())
        assertFalse(modern("MCP-Protocol-Version" to "2025-11-25"))
        assertFalse(modern("Mcp-Method" to "tools/list", "MCP-Protocol-Version" to "2025-06-18"))
        assertFalse(modern("Mcp-Method" to "initialize"))
        assertFalse(modern("Mcp-Method" to "tools/list", "Mcp-Session-Id" to "s-1"))
        assertFalse(modern("MCP-Protocol-Version" to "v999.0.0"))
        // The Kotlin SDK 0.16.0 client after initialize: Mcp-Method, no version, no session on a stateless route.
        assertFalse(modern("Mcp-Method" to "tools/list"))
        assertFalse(modern("Mcp-Method" to "tools/call", "Mcp-Name" to "test_simple_text"))
    }
}
