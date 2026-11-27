package app.oreshkov.ktmcpx.stdio

import app.oreshkov.ktmcpx.fixture.fixtureServer
import app.oreshkov.ktmcpx.testing.ModernContract
import app.oreshkov.ktmcpx.testing.ModernResponse
import app.oreshkov.ktmcpx.testing.ModernTestClient
import app.oreshkov.ktmcpx.testing.ModernTestTransport
import app.oreshkov.ktmcpx.testing.ToolProbe
import app.oreshkov.ktmcpx.testing.withModernStdioClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StdioContractTest {
    @Test
    fun `the fixture server passes the contract kit over stdio`() = runBlocking {
        withTimeout(30.seconds) {
            withModernStdioClient(fixtureServer()) { client ->
                assertFalse(client.transport.isHttp)
                val report = ModernContract.verify(client, ToolProbe("test_simple_text"))
                report.assertPassed()
                assertTrue(report.checks.none { "HTTP" in it.name || "Mcp-" in it.name }, report.toString())
            }
        }
    }

    @Test
    fun `a server that broadcasts to every session passes the notification checks over stdio`() = runBlocking {
        withTimeout(30.seconds) {
            withModernStdioClient(broadcastingServer()) { client ->
                ModernContract.verify(client, ToolProbe("broadcast")).assertPassed()
            }
        }
    }

    @Test
    fun `the notification checks fail when an answer carries what its request did not ask for`() = runBlocking {
        withTimeout(30.seconds) {
            withModernStdioClient(fixtureServer()) { client ->
                val leaking = ModernTestClient(LeakingTransport(client.transport))
                val failed = ModernContract.verify(leaking).failures.map { it.name }
                assertEquals(
                    listOf(
                        "a notification gets no answer",
                        "no answer carries a log message its request did not ask for",
                        "no answer carries a list_changed or resources/updated notification",
                    ),
                    failed,
                )
            }
        }
    }

    @Test
    fun `stdio answers keep notifications before the response`() = runBlocking {
        withTimeout(30.seconds) {
            withModernStdioClient(fixtureServer()) { client ->
                val meta = JsonObject(client.defaultMeta() + ("progressToken" to kotlinx.serialization.json.JsonPrimitive("p1")))
                val response = client.send("tools/call", buildJsonObject { put("name", "test_tool_with_progress") }, meta = meta)

                assertEquals(0, response.status)
                assertEquals(List(3) { "notifications/progress" }, response.notifications.map { it["method"].toString().trim('"') })
                assertEquals("complete", response.result!!["resultType"].toString().trim('"'))
            }
        }
    }
}

/** Puts a log message and a list change in front of every answer, as a leaking bridge would. */
private class LeakingTransport(private val inner: ModernTestTransport) : ModernTestTransport {
    override val isHttp: Boolean get() = inner.isHttp

    override suspend fun send(body: String, headers: Map<String, String?>): ModernResponse {
        val leaked = listOf(
            notification("notifications/message"),
            notification("notifications/tools/list_changed"),
        )
        return ModernResponse.ofLines(leaked + inner.send(body, headers).messages)
    }

    private fun notification(method: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
    }
}
