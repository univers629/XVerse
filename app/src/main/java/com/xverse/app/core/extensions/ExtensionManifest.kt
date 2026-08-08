package com.xverse.app.core.extensions

import org.json.JSONArray
import org.json.JSONObject

/**
 * 单条内容脚本声明（manifest.content_scripts 元素）。
 * matches 仅做最小校验（见 ExtensionRuntime.shouldInjectForHost）。
 */
data class ContentScriptSpec(
    val matches: List<String> = emptyList(),
    val js: List<String> = emptyList(),
    val css: List<String> = emptyList(),
    val runAt: String = "document_idle",
    val allFrames: Boolean = false,
)

/**
 * 扩展 manifest.json 解析结果。
 * __MSG_xxx__ 占位符（name/description/options）由 [localize] 替换。
 */
data class ExtensionManifest(
    val name: String,
    val version: String,
    val manifestVersion: Int,
    val description: String,
    val contentScripts: List<ContentScriptSpec>,
    val permissions: List<String>,
    val optionsPage: String,
    val icons: Map<String, String>,
    val homepageUrl: String,
    val author: String,
    val background: List<String>,
    /** 声明了 background（后台脚本）但本运行时不会执行 */
    val hasBackground: Boolean,
)

/**
 * manifest.json 解析器（org.json）。
 * - 读取 manifest_version / name / version / description / content_scripts /
 *   options_page(MV2) / options_ui.page(MV3) / permissions / icons / author / homepage_url / background
 * - [localize]：把 __MSG_key__ 换成 _locales 里的消息文本（按语言优先级回退）
 */
object ManifestParser {

    private const val ID_PATTERN = "__MSG_(.+)__"

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }

    fun parse(json: String): Result<ExtensionManifest> = runCatching {
        val m = JSONObject(json)
        val mv = m.optInt("manifest_version", 3)
        if (mv != 2 && mv != 3) {
            throw IllegalArgumentException("不支持的 manifest_version: $mv")
        }
        val cs = JSONArray()
        m.optJSONArray("content_scripts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                cs.put(
                    JSONObject()
                        .put("matches", e.optJSONArray("matches") ?: JSONArray())
                        .put("js", e.optJSONArray("js") ?: JSONArray())
                        .put("css", e.optJSONArray("css") ?: JSONArray())
                        .put("run_at", e.optString("run_at", "document_idle"))
                        .put("all_frames", e.optBoolean("all_frames", false))
                )
            }
        }
        val perms = JSONArray()
        m.optJSONArray("permissions")?.let { arr ->
            for (i in 0 until arr.length()) perms.put(arr.optString(i))
        }
        val opts = if (mv >= 3) {
            // 主流商店扩展常直接声明顶层 options_page 而非 options_ui.page（immersive/AdGuard 均如此）
            m.optJSONObject("options_ui")?.optString("page", "")
                ?.takeIf { it.isNotBlank() }
                ?: m.optString("options_page", "")
        } else {
            m.optString("options_page", "")
        }
        val icons = JSONObject()
        m.optJSONObject("icons")?.let { ico ->
            ico.keys().forEach { k -> icons.put(k, ico.optString(k)) }
        }
        val bg = if (mv >= 3) {
            m.optJSONObject("background")?.optString("service_worker", "") ?: ""
        } else {
            m.optJSONObject("background")?.optJSONArray("scripts")
                ?.let { arr -> (0 until arr.length()).joinToString(",") { arr.optString(it) } } ?: ""
        }
        ExtensionManifest(
            name = m.optString("name", "未命名扩展"),
            version = m.optString("version", "0.0"),
            manifestVersion = mv,
            description = m.optString("description", ""),
            contentScripts = (0 until cs.length()).map { i ->
                val e = cs.optJSONObject(i)
                ContentScriptSpec(
                    matches = e.optJSONArray("matches")?.toStringList() ?: emptyList(),
                    js = e.optJSONArray("js")?.toStringList() ?: emptyList(),
                    css = e.optJSONArray("css")?.toStringList() ?: emptyList(),
                    runAt = e.optString("run_at", "document_idle"),
                    allFrames = e.optBoolean("all_frames", false),
                )
            },
            permissions = perms.toStringList(),
            optionsPage = opts,
            icons = buildMap {
                icons.keys().forEach { k -> put(k, icons.optString(k)) }
            },
            homepageUrl = m.optString("homepage_url", ""),
            author = m.optString("author", ""),
            background = if (bg.isBlank()) emptyList() else listOf(bg),
            hasBackground = bg.isNotBlank(),
        )
    }

    /**
     * 把 __MSG_key__ 占位符替换为 _locales 消息文本。
     * @param messages locales 合并后的 JSONObject（多语言回退后），可空
     */
    fun localize(s: String, messages: JSONObject?): String {
        if (s.isBlank() || !s.contains("__MSG_")) return s
        val re = Regex(ID_PATTERN)
        return re.replace(s) { mt ->
            val key = mt.groupValues[1]
            val obj = messages?.optJSONObject(key)
            // has() 判断避免把 null 传给 Java 的 optString(fallback, String) 造成类型警告；
            // 有 message 键才取用，否则保留原 __MSG_xxx__ 占位符
            val msg = if (obj != null && obj.has("message")) obj.optString("message") else null
            msg ?: mt.value
        }
    }

    /** 按语言优先级合并 _locales：设备语言 → default_locale → en；返回合并后 JSONObject 或 null */
    fun resolveMessages(
        localeDir: java.io.File?,
        defaultLocale: String,
        deviceLang: String,
    ): JSONObject? {
        if (localeDir == null || !localeDir.isDirectory) return null
        val candidates = listOf(deviceLang, defaultLocale, "en").distinct()
        val merged = JSONObject()
        for (lang in candidates) {
            val f = java.io.File(localeDir, "$lang/messages.json")
            if (!f.isFile) continue
            try {
                val obj = JSONObject(f.readText())
                obj.keys().forEach { key -> merged.put(key, obj.get(key)) }
            } catch (_: Exception) {
            }
        }
        return if (merged.length() > 0) merged else null
    }

    /** 从图标尺寸映射挑选：128 > 48 > 16 > 其余最大 */
    fun pickIcon(icons: Map<String, String>): String? {
        if (icons.isEmpty()) return null
        listOf("128", "48", "16").forEach { s -> icons[s]?.let { return it } }
        return icons.values.firstOrNull()
    }
}
