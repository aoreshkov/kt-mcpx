package app.oreshkov.ktmcpx

import app.oreshkov.ktmcpx.internal.decodeHeaderValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Every request-level error row, with its HTTP status and the request id kept. */
class ValidationTest {
    private val bridge = ModernBridge(testServer())

    private fun handle(text: String, headers: ModernHeaders? = headersFor(text)): ModernReply.Response =
        runBlocking { bridge.handle(text, headers).response }

    private fun assertRejected(reply: ModernReply.Response, code: Int, status: Int) {
        assertEquals(code, reply.code, reply.message.toString())
        assertEquals(status, reply.httpStatus, reply.message.toString())
    }

    @Test
    fun `malformed JSON is -32700 with a null id`() {
        val reply = handle("{oops", ModernHeaders("tools/list", null, ModernProtocol.VERSION))
        assertRejected(reply, ModernErrorCodes.PARSE_ERROR, 400)
        assertEquals(JsonNull, reply.message["id"])
    }

    @Test
    fun `not JSON-RPC is -32600`() {
        val headers = ModernHeaders("tools/list", null, ModernProtocol.VERSION)
        assertRejected(handle("[]", headers), ModernErrorCodes.INVALID_REQUEST, 400)
        assertRejected(handle("""{"jsonrpc":"1.0","id":1,"method":"tools/list"}""", headers), ModernErrorCodes.INVALID_REQUEST, 400)
        assertRejected(handle("""{"jsonrpc":"2.0","id":1,"result":{}}""", headers), ModernErrorCodes.INVALID_REQUEST, 400)
        assertRejected(handle("""{"jsonrpc":"2.0","id":{"a":1},"method":"tools/list"}""", headers), ModernErrorCodes.INVALID_REQUEST, 400)
    }

    @Test
    fun `missing or broken _meta is -32602 and keeps the id`() {
        val noMeta = body("server/discover", meta = null, id = JsonPrimitive(101))
        val reply = handle(noMeta, ModernHeaders("server/discover", null, ModernProtocol.VERSION))
        assertRejected(reply, ModernErrorCodes.INVALID_PARAMS, 400)
        assertEquals(JsonPrimitive(101), reply.message["id"])

        val noVersion = JsonObject(meta() - ModernMetaKeys.PROTOCOL_VERSION)
        assertRejected(handle(body("server/discover", meta = noVersion), ModernHeaders("server/discover", null, ModernProtocol.VERSION)), ModernErrorCodes.INVALID_PARAMS, 400)

        val noCapabilities = JsonObject(meta() - ModernMetaKeys.CLIENT_CAPABILITIES)
        assertRejected(handle(body("server/discover", meta = noCapabilities)), ModernErrorCodes.INVALID_PARAMS, 400)

        val badLogLevel = meta(extra = mapOf(ModernMetaKeys.LOG_LEVEL to JsonPrimitive("loud")))
        assertRejected(handle(body("tools/list", meta = badLogLevel)), ModernErrorCodes.INVALID_PARAMS, 400)
    }

    @Test
    fun `client info is optional`() {
        val noInfo = JsonObject(meta() - ModernMetaKeys.CLIENT_INFO)
        assertEquals(200, handle(body("server/discover", meta = noInfo)).httpStatus)
    }

    @Test
    fun `an unsupported version is -32022 with supported and requested`() {
        val reply = handle(body("server/discover", meta = meta(version = "v999.0.0")))
        assertRejected(reply, ModernErrorCodes.UNSUPPORTED_PROTOCOL_VERSION, 400)
        val data = reply.error["data"]!!.jsonObject
        assertEquals(ModernProtocol.SUPPORTED_VERSIONS, data["supported"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("v999.0.0", data["requested"]!!.jsonPrimitive.content)
    }

    @Test
    fun `headers that disagree with the body are -32020`() {
        val text = toolCall("echo")
        val good = headersFor(text)
        assertRejected(handle(text, ModernHeaders(null, good.name, good.protocolVersion)), ModernErrorCodes.HEADER_MISMATCH, 400)
        assertRejected(handle(text, ModernHeaders("TOOLS/CALL", good.name, good.protocolVersion)), ModernErrorCodes.HEADER_MISMATCH, 400)
        assertRejected(handle(text, ModernHeaders(good.method, null, good.protocolVersion)), ModernErrorCodes.HEADER_MISMATCH, 400)
        assertRejected(handle(text, ModernHeaders(good.method, "other", good.protocolVersion)), ModernErrorCodes.HEADER_MISMATCH, 400)
        assertRejected(handle(text, ModernHeaders(good.method, good.name, null)), ModernErrorCodes.HEADER_MISMATCH, 400)
        // The version header is checked against _meta before the version itself.
        val wrongVersion = body("server/discover", meta = meta(version = "v999.0.0"))
        assertRejected(handle(wrongVersion, ModernHeaders("server/discover", null, ModernProtocol.VERSION)), ModernErrorCodes.HEADER_MISMATCH, 400)
    }

    @Test
    fun `a Base64 Mcp-Name is decoded before the comparison`() {
        val text = toolCall("echo")
        val encoded = "=?base64?" + Base64.getEncoder().encodeToString("echo".encodeToByteArray()) + "?="
        assertEquals(200, handle(text, ModernHeaders("tools/call", encoded, ModernProtocol.VERSION)).httpStatus)
        assertEquals("héllo wörld", decodeHeaderValue("=?base64?" + Base64.getEncoder().encodeToString("héllo wörld".encodeToByteArray()) + "?="))
        assertNull(decodeHeaderValue("=?base64?***?="))
        assertEquals("plain", decodeHeaderValue("plain"))
    }

    @Test
    fun `removed and unknown methods are -32601, HTTP 404`() {
        for (method in listOf("initialize", "ping", "logging/setLevel", "resources/subscribe", "resources/unsubscribe", "tasks/list", "tasks/result", "unknown/method")) {
            val reply = handle(body(method, id = JsonPrimitive("id-$method")))
            assertRejected(reply, ModernErrorCodes.METHOD_NOT_FOUND, 404)
            assertEquals(JsonPrimitive("id-$method"), reply.message["id"])
        }
    }
}
