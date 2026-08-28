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
 * 页面侧单帖解析器：由浏览器层实现，用 WebView 自己的会话按 tweetId 现取**这一条**推文的 JSON。
 *
 * 存在的原因：X 是 SPA，从时间线点进详情页 / 竖屏 mediaViewer 时经常复用前端内存缓存，
 * 不再发新的 GraphQL 请求，被动拦截（[MediaParser.cacheFromGraphQL]）必然 miss，
 * 于是「停留再久也解析不出媒体」。点下载的瞬间主动问页面要这一条即可立即命中。
 *
 * 约定：只解析被请求的那一条推文，不遍历时间线、不批量解析整页媒体。
 */
interface PageTweetResolver {
    /** 返回该推文的 GraphQL 形状 JSON（`{legacy:…}` 或 `{media:[…]}`）；取不到返回 null */
    suspend fun resolveTweet(tweetId: String): String?
}

/**
 * 推文媒体解析器。
 *
 * 逐级降级，全程只针对**一条**推文（不做整页/时间线批量解析）：
 *  1. 页面自身 GraphQL 响应缓存 —— 注入 JS 拦截 `/graphql/` 响应，经 Bridge `mediaResponse`
 *     上报原生，从 `tweetResult` 的 `extended_entities` 直取直链。数据来自用户登录会话。
 *  2. [PageTweetResolver] —— 缓存未命中时，点下载的瞬间让页面按 tweetId 现取这一条
 *     （页面上下文有登录态、可用网络，敏感/年龄限制帖也能拿到）。
 *  3. syndication `tweet-result?id=` —— 原生侧按 id 查单帖，无需登录态。
 *  4.（末位兜底）整页 HTML 内嵌 JSON 的 `extended_entities` / `video_info`。
 */
class MediaParser(private val context: Context) {

    /** 页面侧单帖解析器；由 BrowserViewModel 在 WebView 就绪时注入（无 WebView 时为 null）。 */
    @Volatile
    var pageResolver: PageTweetResolver? = null

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
            LogStore.log(LogCategory.DOWNLOAD, "GraphQL cached ${items.size} media URLs (tweet $tweetId)")
        }
    }

    /** 解析推文 URL 的全部媒体直链（优先按 tweetId 命中的 GraphQL 缓存，兜底页面 HTML） */
    suspend fun parse(tweetUrl: String): List<MediaItem> = withContext(Dispatchers.IO) {
        LogStore.log(LogCategory.DOWNLOAD, "Parsing tweet media: $tweetUrl")
        // mediaViewer 里真正要下载的推文在 currentTweet 参数（滑动浏览），路径 id 只是宿主推文
        val norm = canonicalTweetUrl(tweetUrl)
        // 从规范 URL 提取 tweetId，只取属于该推文的缓存（引用/时间线里的其他推文不串入）
        val parsed = com.xverse.app.core.data.repo.HistoryRepo.parseTweetUrl(norm)
        val tweetId = parsed?.second ?: ""
        val cached = if (tweetId.isNotBlank()) synchronized(cachedMedia) { cachedMedia[tweetId] } else null
        if (cached != null && cached.isNotEmpty()) {
            LogStore.log(LogCategory.DOWNLOAD, "Hit GraphQL cache ${cached.size} items (tweet $tweetId)")
            return@withContext cached
        }
        // 通道二：向页面索取这一条推文（WebView 自己的登录会话 + 已验证可用的网络栈）。
        // SPA 复用前端缓存时不会再发 GraphQL 请求，被动拦截必然 miss；这里主动问页面要**这一条**。
        val resolver = pageResolver
        if (tweetId.isNotBlank() && resolver != null) {
            val fromPage = runCatching { resolver.resolveTweet(tweetId) }
                .onFailure { LogStore.error("Page resolve failed (tweet $tweetId)", it) }
                .getOrNull()
            val items = if (fromPage.isNullOrBlank()) emptyList() else parseGraphQLMedia(fromPage)
            if (items.isNotEmpty()) {
                synchronized(cachedMedia) { cachedMedia[tweetId] = items }
                LogStore.log(LogCategory.DOWNLOAD, "Page resolved ${items.size} media items (tweet $tweetId)")
                return@withContext items
            }
            LogStore.log(LogCategory.DOWNLOAD, "Page resolve empty (tweet $tweetId)")
        }
        // 通道三：按 tweetId 单帖查询 syndication tweet-result。
        // x.com 现在只回 SPA 外壳，整页 HTML 里已不再内嵌 extended_entities（实测 0 次命中），
        // 所以缓存未命中时先走这条按 id 精确取单帖的接口：无需登录态、返回完整 mp4 档位（含 4K）。
        if (tweetId.isNotBlank()) {
            val fromSyndication = runCatching { fetchFromSyndication(tweetId) }
                .onFailure { LogStore.error("Syndication lookup failed (tweet $tweetId)", it) }
                .getOrDefault(emptyList())
            if (fromSyndication.isNotEmpty()) {
                synchronized(cachedMedia) { cachedMedia[tweetId] = fromSyndication }
                LogStore.log(
                    LogCategory.DOWNLOAD,
                    "Syndication resolved ${fromSyndication.size} media items (tweet $tweetId)",
                )
                return@withContext fromSyndication
            }
        }
        // 通道四（末位兜底）：整页 HTML 抓取。保留给以上通道都不覆盖的情形。
        try {
            val cookie = com.xverse.app.core.auth.CookieManagerReader.cookiesForFromBackground(norm)
            val html = fetch(norm, cookie)
            if (html.isNullOrBlank()) {
                LogStore.log(LogCategory.DOWNLOAD, "Failed to fetch tweet page")
                return@withContext emptyList()
            }
            val items = extractMedia(html)
            LogStore.log(LogCategory.DOWNLOAD, "Parsed ${items.size} media items")
            items
        } catch (e: Exception) {
            LogStore.error("Exception parsing tweet media", e)
            emptyList()
        }
    }

    /**
     * 按推文 id 精确解析单帖媒体（syndication tweet-result）。
     *
     * 只查这一条推文，不遍历时间线、不批量抓取：响应里的 `mediaDetails` 就是该帖
     * 自己的媒体数组，字段形状与 GraphQL 的 `extended_entities.media` 一致
     * （type / media_url_https / original_info / video_info.variants），可直接复用
     * [parseMediaArray]。
     *
     * 注意 `video.variants` 是另一套形状（type/src，且无 bitrate），不能用——
     * X-Vault 1.4.9 正是误取了它，导致 `.url` 为 undefined 抛错、这条兜底整体失效。
     */
    private fun fetchFromSyndication(tweetId: String): List<MediaItem> {
        val url = "$SYNDICATION_ENDPOINT?id=$tweetId&token=${syndicationToken(tweetId)}"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", CHROME_MOBILE_UA)
            .header("Accept", "application/json")
            .build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                LogStore.log(LogCategory.DOWNLOAD, "Syndication HTTP ${resp.code} (tweet $tweetId)")
                return emptyList()
            }
            resp.body.string()
        }
        if (body.isBlank()) return emptyList()
        val root = JSONObject(body)
        val details = root.optJSONArray("mediaDetails") ?: return emptyList()
        val out = mutableListOf<MediaItem>()
        parseMediaArray(details, out)
        return out.distinctBy { it.url }
    }

    /** 从 GraphQL TweetDetail 响应提取媒体：只取主推文的媒体数组（递归找第一个带 media[] 的对象即止） */
    private fun parseGraphQLMedia(json: String): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        try {
            val root = JSONObject(json)
            walkGraphQL(root, out, stopAfterFirst = true)
        } catch (e: Exception) {
            LogStore.error("GraphQL media parsing failed", e)
        }
        return out.distinctBy { it.url }
    }

    private fun walkGraphQL(obj: JSONObject, out: MutableList<MediaItem>, stopAfterFirst: Boolean = false) {
        val media = obj.optJSONArray("media")
        if (media != null && out.size < 12) {
            parseMediaArray(media, out)
            // 只有真的取到媒体才收敛：首个 media[] 若是纯 HLS 直播等取不出直链的情形，
            // 继续往下走，否则整帖会被判成「无媒体」。
            if (out.isNotEmpty()) return
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
                    // 选 MP4（排除 m3u8）；mp4 变体的类型键在 GraphQL 是 content_type，syndication 的
                    // video.variants 用 type；两个都认，URL 键同理（url / src）。
                    val mp4s = mutableListOf<JSONObject>()
                    for (j in 0 until variants.length()) {
                        val v = variants.optJSONObject(j) ?: continue
                        val contentType = v.optString("content_type").ifEmpty { v.optString("type") }
                        if (contentType == "video/mp4") mp4s.add(v)
                    }
                    var best: JSONObject? = null
                    var bestBitrate = -1
                    for (v in mp4s) {
                        val bitrate = v.optInt("bitrate", 0)
                        if (bitrate > bestBitrate) {
                            bestBitrate = bitrate
                            best = v
                        }
                    }
                    // 部分响应的 mp4 变体不带 bitrate（全为 0），按 bitrate 排会稳定命中
                    // 第一档 480x270；这时改用 URL 里编码的分辨率挑最高档。
                    if (bestBitrate <= 0 && mp4s.isNotEmpty()) {
                        best = mp4s.maxByOrNull { pixelsOf(variantUrl(it)) } ?: mp4s.first()
                        bestBitrate = 0
                    }
                    if (best != null) {
                        val url = variantUrl(best)
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
                        val label = dim
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
        private const val SYNDICATION_ENDPOINT = "https://cdn.syndication.twimg.com/tweet-result"
        private const val RADIX36_DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz"

        /** 变体直链：GraphQL/syndication.mediaDetails 用 url，syndication.video 用 src。 */
        internal fun variantUrl(variant: JSONObject): String =
            variant.optString("url").ifEmpty { variant.optString("src") }

        /** 从 video.twimg.com 直链里的 /<W>x<H>/ 段取像素数，取不到记 0。 */
        internal fun pixelsOf(url: String): Long {
            val m = Regex("/(\\d+)x(\\d+)/").find(url) ?: return 0
            return m.groupValues[1].toLong() * m.groupValues[2].toLong()
        }

        /**
         * syndication tweet-result 的 token（对齐 X 前端算法）：
         * `((id / 1e15) * Math.PI).toString(36).replace(/(0+|\.)/g, '')`。
         *
         * 当前服务端并不校验（token=x 也返回 200），这里按官方算法生成只是防它日后收紧。
         */
        internal fun syndicationToken(tweetId: String): String {
            val id = tweetId.toDoubleOrNull() ?: return "x"
            val token = jsRadix36(id / 1e15 * Math.PI).replace(Regex("(0+|\\.)"), "")
            return token.ifEmpty { "x" }
        }

        /**
         * 复刻 JS `Number.prototype.toString(36)`（V8 DoubleToRadixCString）。
         * 直接十进制转换会和 JS 结果不一致，token 就对不上，所以照搬其定点算法。
         */
        internal fun jsRadix36(value: Double): String {
            if (!value.isFinite()) return "0"
            val radix = 36
            var integer = Math.floor(value)
            var fraction = value - integer
            var delta = 0.5 * (Math.nextUp(value) - value)
            if (delta < Double.MIN_VALUE) delta = Double.MIN_VALUE
            val fractionPart = StringBuilder()
            if (fraction >= delta) {
                while (true) {
                    fraction *= radix
                    delta *= radix
                    var digit = fraction.toInt()
                    fractionPart.append(RADIX36_DIGITS[digit])
                    fraction -= digit
                    if (fraction > 0.5 || (fraction == 0.5 && (digit and 1) == 1)) {
                        if (fraction + delta > 1) {
                            // 进位：从末位往前找可加 1 的位，全部溢出则整数部分 +1
                            var cursor = fractionPart.length
                            while (true) {
                                cursor--
                                if (cursor < 0) {
                                    integer += 1
                                    break
                                }
                                digit = RADIX36_DIGITS.indexOf(fractionPart[cursor])
                                if (digit + 1 < radix) {
                                    fractionPart.setCharAt(cursor, RADIX36_DIGITS[digit + 1])
                                    cursor++
                                    break
                                }
                            }
                            fractionPart.setLength(maxOf(cursor, 0))
                            break
                        }
                    }
                    if (fraction < delta) break
                }
            }
            val integerPart = StringBuilder()
            do {
                val remainder = integer % radix
                integerPart.append(RADIX36_DIGITS[remainder.toInt()])
                integer = (integer - remainder) / radix
            } while (integer > 0)
            integerPart.reverse()
            return if (fractionPart.isEmpty()) {
                integerPart.toString()
            } else {
                "$integerPart.$fractionPart"
            }
        }
    }
}
