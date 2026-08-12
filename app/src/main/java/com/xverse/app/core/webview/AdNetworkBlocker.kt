package com.xverse.app.core.webview

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.xverse.app.core.data.db.FilterRule
import com.xverse.app.core.data.db.RuleType
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * XVerse 内容过滤的原生网络防线。
 *
 * X 的推广帖与普通帖子混在同一 GraphQL 时间线响应中，不能按 URL 封锁整条响应；
 * 常规模式阻断可独立识别的广告/跟踪域名和用户网络规则；strip 模式再阻断 X 的
 * 曝光端点。广告条目本身仍由 GraphQL/DOM 层识别，不按媒体 CDN 粗暴拦截。
 */
internal object AdNetworkBlocker {

    private data class CompiledRule(
        val exception: Boolean,
        val regex: Regex,
    )

    private data class State(
        val enabled: Boolean = false,
        val stripMode: Boolean = false,
        val allowRules: List<CompiledRule> = emptyList(),
        val blockRules: List<CompiledRule> = emptyList(),
        val extensionAllowedHosts: Set<String> = emptySet(),
        val extensionBlockedHosts: Set<String> = emptySet(),
    )

    @Volatile private var state = State()

    private val logged = ConcurrentHashMap.newKeySet<String>()

    private val blockedHostSuffixes = setOf(
        "ads-twitter.com",
        "ads.twitter.com",
        "ads.x.com",
        "ads-api.twitter.com",
        "ads-api.x.com",
        "analytics.twitter.com",
        "analytics.x.com",
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
    )

    /** 总开关、过滤模式和自定义 NETWORK 规则一次性原子更新。 */
    fun configure(
        enabled: Boolean,
        stripMode: Boolean = false,
        rules: List<FilterRule> = emptyList(),
        extensionAllowedHosts: Set<String> = emptySet(),
        extensionBlockedHosts: Set<String> = emptySet(),
    ) {
        val compiled = if (enabled) {
            rules.asSequence()
                .filter { it.enabled && it.type == RuleType.NETWORK }
                .mapNotNull { compileRule(it.pattern) }
                .toList()
        } else {
            emptyList()
        }
        state = State(
            enabled = enabled,
            stripMode = enabled && stripMode,
            allowRules = compiled.filter { it.exception },
            blockRules = compiled.filterNot { it.exception },
            extensionAllowedHosts = if (enabled) extensionAllowedHosts else emptySet(),
            extensionBlockedHosts = if (enabled) extensionBlockedHosts else emptySet(),
        )
        logged.clear()
        LogStore.log(
            LogCategory.FILTER,
            "网页广告/跟踪拦截 ${if (enabled) "开启" else "关闭"}" +
                if (enabled) {
                    "（自定义 ${compiled.size}，扩展默认 ${extensionBlockedHosts.size + extensionAllowedHosts.size}，strip=$stripMode）"
                } else "",
        )
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val current = state
        if (!current.enabled || request.isForMainFrame) return null
        val uri = request.url
        val url = uri.toString()
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path?.lowercase().orEmpty()
        // 放行规则优先级最高，可覆盖内置域名/端点与自定义阻断规则。
        if (hostMatches(host, current.extensionAllowedHosts) ||
            current.allowRules.any { it.regex.containsMatchIn(url) }
        ) return null

        val blockedHost = blockedHostSuffixes.any { host == it || host.endsWith(".$it") }
        val blockedPath = current.stripMode && (
            path == "/i/api/1.1/jot/client_event.json" ||
                path.startsWith("/i/api/1.1/promoted_content/") ||
                path.startsWith("/i/api/2/promoted_content/") ||
                path == "/i/adsct"
            )
        val blockedByRule = hostMatches(host, current.extensionBlockedHosts) ||
            current.blockRules.any { it.regex.containsMatchIn(url) }
        if (!blockedHost && !blockedPath && !blockedByRule) return null

        val key = "$host$path"
        if (logged.size >= MAX_LOGGED_REQUESTS) logged.clear()
        if (logged.add(key)) {
            LogStore.log(LogCategory.FILTER, "原生阻断广告/追踪请求：$key")
        }
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            204,
            "No Content",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-store",
            ),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    /** 编译 AdGuard/ABP 常用网络写法；不支持的规则返回 null，避免误阻断。 */
    private fun compileRule(pattern: String): CompiledRule? {
        var raw = pattern.trim()
        if (raw.isEmpty() || raw.startsWith("!") || raw.contains("##")) return null
        val exception = raw.startsWith("@@")
        if (exception) raw = raw.removePrefix("@@")
        raw = raw.substringBefore('$').trim()
        if (raw.isEmpty()) return null

        val domainAnchored = raw.startsWith("||")
        if (domainAnchored) raw = raw.removePrefix("||")
        var startAnchored = raw.startsWith("|")
        if (startAnchored) raw = raw.removePrefix("|")
        val endAnchored = raw.endsWith("|")
        if (endAnchored) raw = raw.removeSuffix("|")
        if (raw.isEmpty()) return null

        val body = buildString {
            raw.forEach { ch ->
                when (ch) {
                    '*' -> append(".*")
                    '^' -> append("(?:[^A-Za-z0-9_.%-]|$)")
                    else -> append(Regex.escape(ch.toString()))
                }
            }
        }
        val expression = buildString {
            when {
                domainAnchored -> append("^https?://(?:[^/]+\\.)?")
                startAnchored || raw.startsWith("http://") || raw.startsWith("https://") -> append('^')
            }
            append(body)
            if (endAnchored) append('$')
        }
        return runCatching {
            CompiledRule(exception, Regex(expression, RegexOption.IGNORE_CASE))
        }.getOrNull()
    }

    /** `||host^` 语义：请求域名本身或任一父域命中即可。 */
    private fun hostMatches(host: String, rules: Set<String>): Boolean {
        if (host.isEmpty() || rules.isEmpty()) return false
        var candidate = host
        while (true) {
            if (candidate in rules) return true
            val dot = candidate.indexOf('.')
            if (dot < 0 || dot == candidate.lastIndex) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    private const val MAX_LOGGED_REQUESTS = 256
}
