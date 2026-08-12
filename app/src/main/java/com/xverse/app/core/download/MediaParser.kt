package com.xverse.app.core.download

import android.content.Context
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 媒体条目：解析结果。
 */
data class MediaItem(
    val url: String,
    val quality: String = "",   // 清晰度标签，如 1080 / 720 / 原图 / gif
    val size: Long = 0,
    val extension: String = "mp4",
    val mediaType: String = "video", // 媒体类型：photo / video / gif（下载列表徽标用）
    val fileName: String? = null,
    val thumbnailUrl: String = "",  // 缩略图（图片=自身小图 / 视频=封面帧），用于下载中心列表
)

/**
 * 推文媒体解析器。
 *
 * 双通道策略：
 *  1.（首选）页面自身 GraphQL 响应缓存 —— 注入 JS 拦截 `TweetDetail` 响应，
 *    经 Bridge `mediaResponse` 上报原生，从 `tweetResult` 的 `extended_entities` 直取直链。
 *    数据来自用户自己的登录会话，无需另行抓取。
 *  2.（兜底）直接解析推文页面内嵌 JSON 的 `extended_entities` / `video_info`
 *    （带登录 Cookie），提取直链；无 yt-dlp 二进制依赖，自包含。
 */
class MediaParser(private val context: Context) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 最近 GraphQL 媒体的有界 LRU；时间线长期浏览时不会按推文数无限增长。 */
    private val cachedMedia = object : LinkedHashMap<String, List<MediaItem>>(MEDIA_CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MediaItem>>): Boolean =
            size > MEDIA_CACHE_LIMIT
    }

    /** 供历史写入复用：返回该推文缓存里第一项媒体的缩略图 URL（无则空串）。
     *  GraphQL 缓存的视频项 thumbnailUrl 是海报帧（ext_tw_video_thumb…:small）。 */
    fun cachedThumbnail(tweetId: String): String =
        synchronized(cachedMedia) { cachedMedia[tweetId]?.firstOrNull()?.thumbnailUrl ?: "" }

    /** 供历史记录写入复用；同一推文不会混合图片与视频，取第一项即可代表整帖类型。 */
    fun cachedMediaType(tweetId: String): String =
        synchronized(cachedMedia) { cachedMedia[tweetId]?.firstOrNull()?.mediaType ?: "" }

    /** 原生侧接收页面 GraphQL 响应，解析并缓存直链（按 tweetId 归属） */
    fun cacheFromGraphQL(tweetId: String, json: String) {
        val items = parseGraphQLMedia(json)
        if (items.isNotEmpty()) {
            synchronized(cachedMedia) { cachedMedia[tweetId] = items }
            LogStore.log(LogCategory.DOWNLOAD, "GraphQL 缓存 ${items.size} 个媒体直链（tweet $tweetId）")
        }
    }

    /** 解析推文 URL 的全部媒体直链（优先按 tweetId 命中的 GraphQL 缓存，兜底页面 HTML） */
    suspend fun parse(tweetUrl: String): List<MediaItem> = withContext(Dispatchers.IO) {
        LogStore.log(LogCategory.DOWNLOAD, "解析推文媒体: $tweetUrl")
        // mediaViewer 里真正要下载的推文在 currentTweet 参数（滑动浏览），路径 id 只是宿主推文
        val norm = canonicalTweetUrl(tweetUrl)
        // 从规范 URL 提取 tweetId，只取属于该推文的缓存（引用/时间线里的其他推文不串入）
        val parsed = com.xverse.app.core.data.repo.HistoryRepo.parseTweetUrl(norm)
        val tweetId = parsed?.second ?: ""
        val cached = if (tweetId.isNotBlank()) synchronized(cachedMedia) { cachedMedia[tweetId] } else null
        if (cached != null && cached.isNotEmpty()) {
            LogStore.log(LogCategory.DOWNLOAD, "命中 GraphQL 缓存 ${cached.size} 个（tweet $tweetId）")
            return@withContext cached
        }
        try {
            val cookie = com.xverse.app.core.auth.CookieManagerReader.cookiesForFromBackground(norm)
            val html = fetch(norm, cookie)
            if (html.isNullOrBlank()) {
                LogStore.log(LogCategory.DOWNLOAD, "拉取推文页失败")
                return@withContext emptyList()
            }
            val items = extractMedia(html)
            LogStore.log(LogCategory.DOWNLOAD, "解析到 ${items.size} 个媒体")
            items
        } catch (e: Exception) {
            LogStore.error("解析推文媒体异常", e)
            emptyList()
        }
    }

    /** 从 GraphQL TweetDetail 响应提取媒体：只取主推文的媒体数组（递归找第一个带 media[] 的对象即止） */
    private fun parseGraphQLMedia(json: String): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        try {
            val root = JSONObject(json)
            walkGraphQL(root, out, stopAfterFirst = true)
        } catch (e: Exception) {
            LogStore.error("GraphQL 媒体解析失败", e)
        }
        return out.distinctBy { it.url }
    }

    private fun walkGraphQL(obj: JSONObject, out: MutableList<MediaItem>, stopAfterFirst: Boolean = false) {
        val media = obj.optJSONArray("media")
        if (media != null && out.size < 12) {
            parseMediaArray(media, out)
            return
        }
        // 递归遍历嵌套键
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            when (val v = obj.opt(k)) {
                is JSONObject -> walkGraphQL(v, out, stopAfterFirst)
                is JSONArray -> {
                    for (i in 0 until v.length()) {
                        (v.opt(i) as? JSONObject)?.let { walkGraphQL(it, out, stopAfterFirst) }
                        if (stopAfterFirst && out.isNotEmpty()) return
                    }
                }
            }
            if (out.size >= 12) break
            if (stopAfterFirst && out.isNotEmpty()) return
        }
    }

    /** 子页（/video/1、/photo/1、/mediaviewer）不内嵌 extended_entities，归一化到整帖页再拉取 */
    private fun canonicalTweetUrl(url: String): String {
        // mediaViewer 滑动浏览：真正目标推文在 currentTweet 参数（+currentTweetUser），路径 id 是宿主
        val currentTweet = Regex("currentTweet=(\\d+)").find(url)?.groupValues?.get(1)
        if (currentTweet != null) {
            val user = Regex("currentTweetUser=([^&]+)").find(url)?.groupValues?.get(1)
            return "https://x.com/${user ?: "i"}/status/$currentTweet"
        }
        return url
            .replace(Regex("/photo/\\d+$"), "")
            .replace(Regex("/video/\\d+$"), "")
            .replace(Regex("/mediaviewer.*", RegexOption.IGNORE_CASE), "")
    }

    private fun fetch(url: String, cookie: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", CHROME_MOBILE_UA)
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body.string()
        }
    }

    /**
     * 从 HTML 提取媒体。
     * 优先找 `extended_entities`（JSON 内嵌），兜底正则。
     */
    private fun extractMedia(html: String): List<MediaItem> {
        // 方法一：找 extended_entities 的 JSON 片段
        val items = mutableListOf<MediaItem>()
        extractFromJson(html, items)
        if (items.isNotEmpty()) return items
        // 方法二：兜底正则提取 pbs.twimg.com 直链
        extractFromRegex(html, items)
        return items.distinctBy { it.url }
    }

    /** 从内嵌 JSON 提取 */
    private fun extractFromJson(html: String, out: MutableList<MediaItem>) {
        // 尝试从 __NEXT_DATA__ 或 INITIAL_STATE 找 extended_entities
        val marker = "extended_entities"
        var idx = html.indexOf(marker)
        var guard = 0
        while (idx >= 0 && guard < 20) {
            // 向后找 JSON 对象起始
            val start = findJsonObjectStart(html, idx)
            if (start >= 0) {
                val obj = parseJsonObject(html, start)
                if (obj != null) {
                    parseMediaFromJson(obj, out)
                    if (out.isNotEmpty()) return
                }
            }
            guard++
            idx = html.indexOf(marker, idx + marker.length)
        }
    }

    private fun findJsonObjectStart(html: String, from: Int): Int {
        // 从 marker 位置向左找最近的 '{'
        var i = from
        while (i >= 0) {
            if (html[i] == '{') return i
            i--
        }
        return -1
    }

    /** 从指定 '{' 起解析平衡括号 JSON */
    private fun parseJsonObject(html: String, start: Int): JSONObject? {
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < html.length) {
            val c = html[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) {
                            // 结束
                            val end = i + 1
                            return try {
                                JSONObject(html.substring(start, end))
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    /** 从 JSON 对象提取 media */
    private fun parseMediaFromJson(obj: JSONObject, out: MutableList<MediaItem>) {
        try {
            val mediaArr = obj.optJSONArray("media")
            if (mediaArr != null) parseMediaArray(mediaArr, out)
            // 递归找嵌套
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                when (val v = obj.opt(k)) {
                    is JSONObject -> parseMediaFromJson(v, out)
                    is JSONArray -> {
                        for (i in 0 until v.length()) {
                            val item = v.opt(i)
                            if (item is JSONObject) parseMediaFromJson(item, out)
                        }
                    }
                }
                if (out.isNotEmpty() && out.size >= 12) break
            }
        } catch (_: Exception) {
        }
    }

    private fun parseMediaArray(arr: JSONArray, out: MutableList<MediaItem>) {
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val type = m.optString("type")
            // 封面帧（视频/GIF 的 media_url_https 是 jpg 海报图；图片则用它自身）
            val poster = m.optString("media_url_https")
            when {
                type == "video" || type == "animated_gif" -> {
                    val videoInfo = m.optJSONObject("video_info") ?: continue
                    val variants = videoInfo.optJSONArray("variants") ?: continue
                    // 选 MP4（排除 m3u8）
                    var best: JSONObject? = null
                    var bestBitrate = -1
                    for (j in 0 until variants.length()) {
                        val v = variants.optJSONObject(j) ?: continue
                        if (v.optString("content_type") == "video/mp4") {
                            val bitrate = v.optInt("bitrate", 0)
                            if (bitrate > bestBitrate) {
                                bestBitrate = bitrate
                                best = v
                            }
                        }
                    }
                    if (best != null) {
                        val url = best.optString("url")
                        // 分辨率优先取 media 对象 original_info（视频/GIF 均有真实宽高），
                        // 比 URL 内编码更可靠：新版 x.com 部分视频与 tweet_video(GIF) URL
                        // 不含 /vid/…/WxH/ 段（旧逻辑会兜底成 2176k / gif，用户不可读）。
                        // 拿不到 original_info 再退回 URL 正则，最后才兜底码率。
                        val oi = m.optJSONObject("original_info")
                        val oiDim = if (oi != null) {
                            val w = oi.optInt("width", 0)
                            val h = oi.optInt("height", 0)
                            if (w > 0 && h > 0) "${w}x${h}" else ""
                        } else ""
                        val label = when {
                            oiDim.isNotEmpty() -> oiDim
                            else -> {
                                val res = Regex("/vid/[^/]+?/(\\d+)x(\\d+)/").find(url)
                                if (res != null) "${res.groupValues[1]}x${res.groupValues[2]}"
                                else if (type == "animated_gif") "gif"
                                else "${bestBitrate / 1000}k"
                            }
                        }
                        if (url.isNotEmpty()) out.add(
                            MediaItem(
                                url = url,
                                quality = label,
                                extension = "mp4",
                                mediaType = if (type == "animated_gif") "gif" else "video",
                                thumbnailUrl = if (poster.isNotEmpty()) poster + ":small" else "",
                            )
                        )
                    }
                }
                type == "photo" -> {
                    // 图片取原图（去掉 :small 等后缀）
                    val url = m.optString("media_url_https")
                    if (url.isNotEmpty()) {
                        val orig = if (url.contains("?")) url.substringBefore("?") + "?format=jpg&name=orig" else url + ":orig"
                        // 图片只有原图一档（x.com 的多档只是缩略图），标签直接用真实分辨率
                        // （如「1279x1873」）更有信息量；拿不到时退回「原图」
                        val oi = m.optJSONObject("original_info")
                        val dim = if (oi != null) {
                            val w = oi.optInt("width", 0)
                            val h = oi.optInt("height", 0)
                            if (w > 0 && h > 0) "${w}x$h" else ""
                        } else ""
                        val label = dim.ifEmpty { "原图" }
                        out.add(
                            MediaItem(
                                url = orig,
                                quality = label,
                                extension = "jpg",
                                mediaType = "photo",
                                thumbnailUrl = if (url.contains("?")) url.substringBefore("?") + "?format=jpg&name=small" else url + ":small",
                            )
                        )
                    }
                }
            }
        }
    }

    /** 兜底：正则提取 pbs.twimg.com 图片直链 + video.twimg.com 视频直链 */
    private fun extractFromRegex(html: String, out: MutableList<MediaItem>) {
        val imgRe = Regex("""https://pbs\.twimg\.com/media/[A-Za-z0-9_-]+(?:\.[a-z]+)?""")
        imgRe.findAll(html).forEach { match ->
            val url = match.value
            if (url.isNotBlank() && out.none { it.url == url }) {
                // 兜底路径拿不到 original_info 尺寸，标签留空（UI 会显示「原画」兜底）
                out.add(MediaItem(url = url, quality = "", extension = url.substringAfterLast('.'), mediaType = "photo"))
            }
        }
        if (out.isEmpty()) {
            val vidRe = Regex("""https://video\.twimg\.com/[^"'\\\s]+?\.mp4[^"'\\\s]*""")
            val seen = mutableSetOf<String>()
            vidRe.findAll(html).forEach { match ->
                val raw = match.value.trimEnd('.', ';', ',')
                val url = raw.substringBefore('?')
                if (url.isNotBlank() && seen.add(url)) {
                    // 视频 URL 同样编码分辨率，兜底时也解析出来
                    val dim = Regex("/vid/[^/]+?/(\\d+)x(\\d+)/").find(url)
                    val label = if (dim != null) "${dim.groupValues[1]}x${dim.groupValues[2]}" else ""
                    out.add(MediaItem(url = url, quality = label, extension = "mp4", mediaType = "video"))
                }
            }
        }
    }

    companion object {
        private const val MEDIA_CACHE_LIMIT = 128
        private const val CHROME_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/128.0.6613.99 Mobile Safari/537.36"
    }
}
