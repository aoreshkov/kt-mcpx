package app.oreshkov.ktmcpx

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Who may reuse a cached result: any client (`public`) or only the one that asked (`private`). */
public enum class CacheScope(public val wireName: String) {
    Public("public"),
    Private("private"),
}

/** The results that carry `ttlMs` and `cacheScope` (SEP-2549, plus `server/discover`). */
public enum class CacheableMethod(public val method: String) {
    ServerDiscover("server/discover"),
    ToolsList("tools/list"),
    PromptsList("prompts/list"),
    ResourcesList("resources/list"),
    ResourcesTemplatesList("resources/templates/list"),
    ResourcesRead("resources/read"),
    ;

    public companion object {
        /** The entry for a JSON-RPC [method], or `null` when its result is not cacheable. */
        public fun of(method: String): CacheableMethod? = entries.firstOrNull { it.method == method }
    }
}

/**
 * A caching hint: [ttl] becomes `ttlMs` (whole milliseconds, at least 0) and [scope] becomes `cacheScope`.
 */
public class CachePolicy(public val ttl: Duration, public val scope: CacheScope) {
    init {
        require(!ttl.isNegative()) { "A cache TTL cannot be negative: $ttl" }
        require(ttl.isFinite()) { "A cache TTL must be finite: $ttl" }
    }

    internal val ttlMs: Long get() = ttl.inWholeMilliseconds

    override fun toString(): String = "CachePolicy(ttl=$ttl, scope=${scope.wireName})"

    public companion object {
        /** What a method gets unless configured: a short TTL, for this client only. */
        public val DEFAULT: CachePolicy = CachePolicy(60.seconds, CacheScope.Private)
    }
}
