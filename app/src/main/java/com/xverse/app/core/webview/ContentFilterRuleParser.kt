package com.xverse.app.core.webview

import com.xverse.app.core.data.db.FilterRule
import com.xverse.app.core.data.db.RuleType

/**
 * XVerse 自定义内容过滤规则解析器。
 *
 * 兼容常用的 AdGuard/ABP 基础写法，但不复制其实现：
 *  - `||host^` / `@@||host^`：阻断/放行子资源请求；
 *  - `domain##selector` / `##selector`：元素隐藏；
 *  - 其它文本：推文正文和用户名关键词。
 */
object ContentFilterRuleParser {

    fun toRule(input: String): FilterRule? {
        val raw = input.trim()
        if (raw.isEmpty() || raw.startsWith("!") || raw.startsWith("[Adblock", ignoreCase = true)) {
            return null
        }
        return when {
            raw.contains("#@#") -> null // 元素隐藏例外暂不支持，拒绝而不是产生错误的反向效果。
            raw.contains("##") -> FilterRule(
                type = RuleType.CSS,
                pattern = raw,
                source = "user",
                description = "CSS: ${raw.take(52)}",
            )
            isNetworkRule(raw) -> FilterRule(
                type = RuleType.NETWORK,
                pattern = raw,
                source = "user",
                description = if (raw.startsWith("@@")) {
                    "@@ ${networkLabel(raw)}"
                } else {
                    "|| ${networkLabel(raw)}"
                },
            )
            else -> FilterRule(
                type = RuleType.REGEX,
                pattern = raw,
                source = "user",
                description = raw,
            )
        }
    }

    /** 返回适用于当前页面域名的 CSS 选择器；null 表示此规则不适用。 */
    fun cosmeticSelector(pattern: String, pageHost: String = "x.com"): String? {
        val marker = pattern.indexOf("##")
        if (marker < 0) return pattern.trim().takeIf { it.isNotEmpty() }
        val domainPart = pattern.substring(0, marker).trim()
        val selector = pattern.substring(marker + 2).trim()
        if (selector.isEmpty()) return null
        if (domainPart.isEmpty()) return selector

        val host = pageHost.lowercase()
        var included = false
        var hasPositive = false
        domainPart.split(',').map(String::trim).filter(String::isNotEmpty).forEach { token ->
            val excluded = token.startsWith("~")
            val domain = token.removePrefix("~").lowercase()
            val matches = host == domain || host.endsWith(".$domain") || domain.endsWith(".$host")
            if (excluded && matches) return null
            if (!excluded) {
                hasPositive = true
                if (matches) included = true
            }
        }
        return selector.takeIf { !hasPositive || included }
    }

    private fun isNetworkRule(raw: String): Boolean {
        val rule = raw.removePrefix("@@")
        return rule.startsWith("||") || rule.startsWith("|http://") ||
            rule.startsWith("|https://") || rule.startsWith("http://") ||
            rule.startsWith("https://")
    }

    private fun networkLabel(raw: String): String = raw
        .removePrefix("@@")
        .removePrefix("||")
        .removePrefix("|")
        .substringBefore('$')
        .removeSuffix("^")
        .removeSuffix("|")
        .take(52)
}
