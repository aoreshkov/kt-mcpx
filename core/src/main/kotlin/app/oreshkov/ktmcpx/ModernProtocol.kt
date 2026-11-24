package app.oreshkov.ktmcpx

/**
 * Names and numbers of the MCP 2026-07-28 revision that kt-mcpx serves.
 *
 * The supported versions live here and nowhere else, so a spec release is a one-line change.
 */
public object ModernProtocol {
    /** The revision kt-mcpx speaks to modern clients. */
    public const val VERSION: String = "2026-07-28"

    /** Every revision kt-mcpx serves, newest first. Sent in `server/discover` and in -32022 errors. */
    public val SUPPORTED_VERSIONS: List<String> = listOf(VERSION)
}

/** The Streamable HTTP headers of a modern POST. Header names are case-insensitive. */
public object ModernHeaderNames {
    /** The JSON-RPC method, on every modern request. Its presence is what routes a POST to kt-mcpx. */
    public const val METHOD: String = "Mcp-Method"

    /** `params.name` or `params.uri`, on `tools/call`, `prompts/get` and `resources/read`. */
    public const val NAME: String = "Mcp-Name"

    /** Must equal `_meta["io.modelcontextprotocol/protocolVersion"]`. */
    public const val PROTOCOL_VERSION: String = "MCP-Protocol-Version"

    /**
     * The prefix of a custom header (SEP-2243): `Mcp-Param-Region` mirrors the `tools/call` argument whose
     * input-schema property carries `"x-mcp-header": "Region"`. See [ModernParamHeaders].
     */
    public const val PARAM_PREFIX: String = "Mcp-Param-"
}

/** The `_meta` keys a modern request or result carries. */
public object ModernMetaKeys {
    public const val PROTOCOL_VERSION: String = "io.modelcontextprotocol/protocolVersion"
    public const val CLIENT_CAPABILITIES: String = "io.modelcontextprotocol/clientCapabilities"
    public const val CLIENT_INFO: String = "io.modelcontextprotocol/clientInfo"
    public const val LOG_LEVEL: String = "io.modelcontextprotocol/logLevel"
    public const val SERVER_INFO: String = "io.modelcontextprotocol/serverInfo"
}

/** JSON-RPC error codes the 2026-07-28 revision adds, next to the standard ones. */
public object ModernErrorCodes {
    public const val PARSE_ERROR: Int = -32_700
    public const val INVALID_REQUEST: Int = -32_600
    public const val METHOD_NOT_FOUND: Int = -32_601
    public const val INVALID_PARAMS: Int = -32_602
    public const val INTERNAL_ERROR: Int = -32_603

    /** A required header is missing, or disagrees with the body. */
    public const val HEADER_MISMATCH: Int = -32_020

    /** The request needs a client capability the client did not declare. */
    public const val MISSING_REQUIRED_CLIENT_CAPABILITY: Int = -32_021

    /** The requested protocol version is not served. `data` carries `supported` and `requested`. */
    public const val UNSUPPORTED_PROTOCOL_VERSION: Int = -32_022
}
