package com.xverse.app.core.extensions

/**
 * 用户脚本头部解析结果（// ==UserScript== 块）。
 * 仅提取 XVerse 注入需要的字段，其余忽略。
 */
data class UserScriptMeta(
    val name: String,
    val version: String,
    val description: String,
    /** @match 数组；@include/@exclude 仅记录，注入按 @match 最小匹配（见 shouldInjectForHost） */
    val matches: List<String>,
    val runAt: String,
    /** @grant 数组；决定注入时是否附加 GM_* shim */
    val grants: List<String>,
    /** @require 数组；外部库 URL（如 JSZip），导入时下载并随脚本内联注入 */
    val requires: List<String>,
    val homepageUrl: String,
    val author: String,
    val noframes: Boolean,
)

/**
 * 用户脚本元数据块解析器。
 *
 * 识别两类头：
 *  - `// ==UserScript==` … `// ==/UserScript==`（TM/Violentmonkey 风格）
 *  - `// @name ...`（无包裹块时逐行扫描）
 *
 * 与 manifest 扩展不同，用户脚本是单文件内容脚本，无后台、无 manifest。
 */
object UserScriptParser {

    private const val BLOCK_START = "==UserScript=="
    private const val BLOCK_END = "==/UserScript=="

    /** 解析脚本头部；找不到 @name 时以文件名兜底。返回 null 表示无法识别为脚本 */
    fun parse(text: String, fallbackName: String): Result<UserScriptMeta> = runCatching {
        val lines = text.split('\n')
        // 定位块
        val startIdx = lines.indexOfFirst { it.trim().contains(BLOCK_START) }
        val endIdx = if (startIdx >= 0) {
            lines.indexOfFirst { it.trim().contains(BLOCK_END) }
        } else -1
        val blockLines = if (startIdx >= 0 && endIdx > startIdx) {
            lines.subList(startIdx, endIdx)
        } else {
            // 无包裹块：取文件头部（前 80 行内）的 @ 指令
            lines.take(80)
        }

        // 收集 @key value
        val directives = LinkedHashMap<String, MutableList<String>>()
        blockLines.forEach { line ->
            val t = line.trim()
            if (!t.startsWith("//")) return@forEach
            val at = t.indexOf('@')
            if (at < 0) return@forEach
            val rest = t.substring(at + 1).trim()
            if (rest.isEmpty()) return@forEach
            val sp = rest.indexOf(' ')
            val key = if (sp > 0) rest.substring(0, sp).trim() else rest
            val value = if (sp > 0) rest.substring(sp + 1).trim() else ""
            if (key.isBlank()) return@forEach
            directives.getOrPut(key) { mutableListOf() }.add(value)
        }

        val name = directives["name"]?.firstOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: fallbackName
        val version = directives["version"]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: "0.0"
        val description = directives["description"]?.firstOrNull()?.trim() ?: ""
        val matches = directives["match"] ?: emptyList()
        val runAt = runAtOf(directives["run-at"]?.firstOrNull())
        val grants = directives["grant"] ?: emptyList()
        val requires = directives["require"] ?: emptyList()
        val homepageUrl = directives["homepageURL"]?.firstOrNull()?.trim()
            ?: directives["namespace"]?.firstOrNull()?.trim() ?: ""
        val author = directives["author"]?.firstOrNull()?.trim() ?: ""
        val noframes = directives["noframes"]?.firstOrNull()?.trim() == "true"

        UserScriptMeta(
            name = name,
            version = version,
            description = description,
            matches = matches.filter { it.isNotBlank() },
            runAt = runAt,
            grants = grants.filter { it.isNotBlank() },
            requires = requires.filter { it.isNotBlank() },
            homepageUrl = homepageUrl,
            author = author,
            noframes = noframes,
        )
    }

    private fun runAtOf(s: String?): String = when (s?.trim()?.lowercase()) {
        "document-start" -> "document_start"
        "document-body" -> "document_end"
        "document-end" -> "document_end"
        else -> "document_idle" // 默认 document_idle（DOM 就绪后）
    }

    // @match 是否匹配 x.com 主机（最小校验，不实现完整 pattern 语法）。
    // 匹配：<all_urls>、*://*/*、*.x.com、x.com/*、https://x.com 等。
    fun matchesXCom(match: String): Boolean {
        val m = match.trim()
        if (m.isEmpty()) return false
        if (m == "<all_urls>") return true
        if (m == "*://*/*") return true
        val host = m.substringAfter("://", m)
            .substringBefore('/')
        // *.x.com / x.com / www.x.com
        if (host == "x.com" || host == "www.x.com" || host == "*.x.com") return true
        if (host.endsWith(".x.com")) return true
        return false
    }
}
