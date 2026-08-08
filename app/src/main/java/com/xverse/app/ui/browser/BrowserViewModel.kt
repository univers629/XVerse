package com.xverse.app.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xverse.app.AppInstance
import com.xverse.app.core.data.db.HistoryRecord
import com.xverse.app.core.data.repo.HistoryRepo
import com.xverse.app.core.download.MediaItem
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import com.xverse.app.core.util.Constants
import com.xverse.app.core.webview.XWebView
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 浏览器页 ViewModel：持有 XWebView 引用，管理历史写入、注入、登录状态。
 */
class BrowserViewModel : ViewModel() {

    var webView: XWebView? = null

    /** WebView 就绪前收到的 URL（冷启动深链等）挂起，就绪后加载 */
    private var pendingUrl: String? = null

    val progress = MutableStateFlow(0)
    val title = MutableStateFlow("XVerse")
    val url = MutableStateFlow("")
    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn

    /** 顶栏下载：当前推文的媒体列表（下拉菜单数据源） */
    private val _mediaList = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaList: StateFlow<List<MediaItem>> = _mediaList
    /** 是否正在解析媒体（下拉打开时的加载态） */
    private val _parsing = MutableStateFlow(false)
    val parsing: StateFlow<Boolean> = _parsing

    private var historyJob: Job? = null

    val locator get() = AppInstance.locator

    /** WebView 就绪时由 UI 调用 */
    fun onWebViewReady(view: XWebView) {
        webView = view
        setupBridge(view)
    }

    /** 首次加载：有挂起 URL 则加载之，否则回首页 */
    fun loadInitial() {
        viewModelScope.launch {
            val wv = webView ?: return@launch
            // 过滤组件初始注册须在首导航前完成，否则内置 strip/CSS 对首屏 SPA 无效
            loadFilterScripts(wv)
            // 扩展注入提供者首帧注册须在首导航前完成，否则 document_start 组内容脚本错过首屏
            loadExtensions(wv)
            val pending = pendingUrl
            pendingUrl = null
            wv.loadUrl(pending ?: Constants.HOME_URL)
        }
    }

    /** 加载 URL（WebView 未就绪则挂起待命） */
    fun loadUrl(url: String) {
        val wv = webView
        if (wv != null) wv.loadUrl(url) else pendingUrl = url
    }

    /** 探针模式切换后重建：清空注入列表 + reload 首页，下一整页加载即按新模式注入 */
    fun rebuildWebView() {
        val wv = webView ?: return
        // 清空注入脚本列表（探针模式零注入 / 恢复后重新 setupBridge 挂全量）
        wv.injector.clear()
        if (!probeMode) {
            // 恢复正常模式：重新挂上全部注入（setupBridge 会根据 probeMode 决定注入内容）
            setupBridge(wv)
            viewModelScope.launch {
                loadFilterScripts(wv)
                loadExtensions(wv)
            }
        } else {
            setupBridge(wv)
        }
        // reload 首页触发整页加载，onPageStarted 按新列表注入
        wv.post { wv.loadUrl(Constants.HOME_URL) }
    }

    private fun setupBridge(view: XWebView) {
        // 探针模式（adb 广播 com.xverse.app.PROBE 切换）：纯净对照实验。
        // 页面零注入（无过滤/恢复/hook/扩展/历史上报），只注入只读探针记录滚动轨迹，
        // 用于对照 x.com 原生行为（scrollRestoration 默认值、返回时是否原生跳动）。
        if (probeMode) {
            view.injector.addEarly(PROBE_ONLY_SCRIPT)
            // 仍需要 Bridge 暴露（探针可读 native 侧状态，但页面不注入内容脚本）
            val bridge = com.xverse.app.core.webview.Bridge(view)
            bridge.expose()
            view.onProgress = { progress.value = it }
            view.onPageFinished = { pageUrl -> this.url.value = pageUrl }
            view.onTitle = { title.value = it }
            return
        }
        // SPA 返回滚动恢复：document_start 挂载，patch history 记录各路由滚动位置，
        // popstate 返回时按目标路由恢复（重试等虚拟列表渲染到位）
        view.injector.addEarly(SCROLL_RESTORE_SCRIPT)
        // 注入历史上报脚本
        view.injector.addLate(HISTORY_TRACKING_SCRIPT)
        // 注入 GraphQL 拦截（原生侧缓存媒体直链，供顶栏下载下拉解析命中）
        view.injector.addEarly(GRAPHQL_HOOK_SCRIPT)
        // Bridge：接收 JS 上报（expose 必须在页面加载前，否则页面早期 JS 拿不到桥）
        val bridge = com.xverse.app.core.webview.Bridge(view)
        // 扩展存储三件套（内容脚本 chrome.storage.local 往返）
        locator.extensionRuntime.registerStorageHandlers(bridge)
        bridge.register("recordHistory") { payload, _ ->
            val url = payload.optString("url")
            val text = payload.optString("text")
            val displayName = payload.optString("name")
            val mediaUrl = payload.optString("mediaUrl")
            if (url.isNotEmpty()) writeHistory(url, text, displayName, mediaUrl)
        }
        // Bridge：GraphQL TweetDetail 响应 → 原生缓存媒体（按推文 id 隔离，避免串帖）
        bridge.register("mediaResponse") { payload, _ ->
            val json = payload.optString("data")
            val tweetId = payload.optString("tweetId")
            if (json.isNotEmpty()) {
                viewModelScope.launch {
                    locator.mediaParser.cacheFromGraphQL(tweetId, json)
                }
            }
        }
        bridge.register("getState") { payload, reply ->
            reply(org.json.JSONObject().put("ok", true).put("loggedIn", loggedIn.value))
        }
        bridge.expose()
        // 扩展资源服务：内容脚本 chrome.runtime.getURL() 加载的资源走本地扩展目录
        view.onShouldInterceptUrl = { url -> locator.extensionRuntime.serveResource(url) }
        // 过滤脚本（M2）：early 三层防御。
        // 初始注册由 loadInitial 在首导航前完成（必须早于 onPageStarted，否则首屏 SPA 不生效）；
        // 这里只挂规则变更热更新。
        watchFilterRules()

        view.onProgress = { progress.value = it }
        view.onPageFinished = { pageUrl ->
            this.url.value = pageUrl
            refreshLoginState()
            // 推文 URL：启动 3s 停留计时
            if (pageUrl.contains("/status/")) {
                scheduleHistoryWrite(pageUrl)
            }
        }
        view.onTitle = { title.value = it }
    }

    /**
     * 扩展注入：观察已启用扩展 → 注册 bundle 提供者（每次整页加载重建，
     * 让 GM 存储缓存随落盘刷新）。切换开关/导入后**下一次整页加载**生效（Chrome 同行为）；
     * UI 侧可主动 Reload 立即生效。
     *
     * 拆成两步：
     *  - [loadExtensions]：挂起等 Room 首次 emit，把 provider 注册到 injector，
     *    供 loadInitial 在首导航前完成（否则 document_start 组内容脚本错过首屏）。
     *  - [watchExtensions]：此后持续观察，enabled 列表变化时热更新 provider。
     */
    private suspend fun loadExtensions(wv: XWebView) {
        val runtime = locator.extensionRuntime
        locator.extensionRepo.observeEnabled().first().let { enabled ->
            applyExtensionProvider(wv, runtime, enabled)
        }
        // 首次注册完成后启动持续观察
        viewModelScope.launch {
            locator.extensionRepo.observeEnabled().drop(1).collect { enabled ->
                val webView = webView ?: return@collect
                applyExtensionProvider(webView, locator.extensionRuntime, enabled)
            }
        }
    }

    /** 把 enabled 列表应用到注入 provider（空列表 → 无扩展） */
    private fun applyExtensionProvider(wv: XWebView, runtime: com.xverse.app.core.extensions.ExtensionRuntime, enabled: List<com.xverse.app.core.extensions.ExtensionEntity>) {
        if (enabled.isEmpty()) {
            wv.injector.setExtensionScripts { null }
            return
        }
        // 捕获当前已启用列表；每次加载调用时按最新存储重建 bundle
        wv.injector.setExtensionScripts {
            val early = StringBuilder()
            val late = StringBuilder()
            enabled.forEach { ext ->
                val (e, l) = runtime.bundlesFor(ext)
                if (e.isNotBlank()) early.append(e).append('\n')
                if (l.isNotBlank()) late.append(l).append('\n')
            }
            val e = early.toString()
            val l = late.toString()
            if (e.isBlank() && l.isBlank()) null else (e to l)
        }
        LogStore.log(
            com.xverse.app.core.log.LogCategory.FILTER,
            "扩展注入已更新：x${enabled.size} 个",
        )
    }

    /**
     * 主线程 toast（桥接处理在 UiExecutor 主线程执行，可直接调用）。
     * 自定义：位置调低（贴近屏幕底部）、支持换行（消息可能较长）、宽度不超过屏幕 2/3。
     * Toast.getView()/setView() 自 API 30 弃用（仍可用），此处仅为自定义布局所需。
     */
    @Suppress("DEPRECATION")
    private fun toast(msg: String) {
        val ctx = locator.appContext
        // 消息支持换行：\n 与 JS 传来的空格换行都转为真实换行
        val text = msg.replace("\\n", "\n")
        val toast = android.widget.Toast.makeText(
            ctx,
            text,
            if (text.length > 60) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
        )
        // 复用默认 Toast 视图（自带圆角深色背景保证对比度），改造为多行 + 限宽
        val view = toast.view
        if (view is android.widget.TextView) {
            view.setText(text)
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            view.setPadding(view.paddingLeft, dp(10), view.paddingRight, dp(10))
            // 允许换行（默认 maxLines=2 会被截断）
            view.maxLines = Int.MAX_VALUE
            // 限宽：超过屏幕 2/3 时按 2/3 宽度重排，让长消息自动换行
            val maxW = (ctx.resources.displayMetrics.widthPixels * 2 / 3).toInt()
            view.maxWidth = maxW
            view.width = maxW
        }
        // 位置：贴近底部，水平居中，上移少量避免被系统导航条遮挡
        toast.setGravity(
            android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM,
            0,
            dp(48),
        )
        toast.show()
    }

    /** dp → px（toast 样式用） */
    private fun dp(v: Int): Int =
        (v * locator.appContext.resources.displayMetrics.density).toInt()

    /**
     * 加载过滤脚本：内置 + 用户规则。
     * [reInject]：规则变更时只重注入用户规则脚本（内置资产已在页面加载时注入，不重复）。
     * @return true=过滤就绪（含未启用）；false=读取失败（调用方仍继续，不阻塞导航）。
     */
    private suspend fun loadFilterScripts(view: XWebView, reInject: Boolean = false): Boolean {
        return try {
            val enabled = locator.settings.filterEnabled.first()
            if (!enabled) {
                LogStore.log(LogCategory.FILTER, "过滤已关闭，跳过注入")
                return true
            }
            val rules = locator.filterRepo.getEnabled()
            // FilterScript 需要 context；AppInstance.locator 内部持有 appContext
            val fs = com.xverse.app.core.webview.FilterScript(locator.appContext)
            if (reInject) {
                // 规则变更：直接执行用户规则 + CSS（幂等，会覆盖旧匹配结果）
                val userScript = fs.userRuleScript(rules)
                if (userScript.isNotBlank()) view.injector.evaluate(userScript)
                val userCss = fs.userCss(rules)
                if (userCss.isNotBlank()) view.injector.evaluate(userCss)
                LogStore.log(LogCategory.FILTER, "过滤规则已热更新：x${rules.size}")
                return true
            }
            fs.buildEarlyScripts(rules).forEach { view.injector.addEarly(it) }
            // Redux 拦截层会改写 React 内部状态，x.com 改版时易引发整页重渲染/反复刷新，
            // 仅当用户显式配置了 STORE 规则时才注入（默认关，CSS + Mutation 两层已够用）。
            val hasStoreRules = rules.any { it.type == com.xverse.app.core.data.db.RuleType.STORE }
            if (hasStoreRules) {
                fs.reduxScript()?.let { view.injector.addEarly(it) }
            } else {
                LogStore.log(LogCategory.FILTER, "无 STORE 规则，跳过 Redux 拦截层")
            }
            LogStore.log(LogCategory.FILTER, "过滤脚本就绪：内置 + 用户规则 x${rules.size}")
            true
        } catch (e: Exception) {
            LogStore.error("加载过滤脚本失败", e)
            false
        }
    }

    /** 过滤规则变化（增删/开关）→ 热更新已加载页面，无需重载 */
    private fun watchFilterRules() {
        viewModelScope.launch {
            locator.filterRepo.observeAll().collect { rules ->
                val wv = webView
                if (wv == null || rules.isEmpty()) return@collect
                // 只在页面已加载后热更新（避免首次空跑）
                loadFilterScripts(wv, reInject = true)
            }
        }
    }
    /** 刷新登录状态（主线程） */
    fun refreshLoginState() {
        val wv = webView ?: return
        _loggedIn.value = wv.isLoggedIn()
        // 同步到 AuthController（供设置页等共享）
        locator.authController.refresh()
    }

    // ---- 登录 ----

    /** 打开登录页：直接在 WebView 内加载，Cookie 写入 WebView 存储 */
    fun startLogin() {
        LogStore.log(LogCategory.AUTH, "在 WebView 内打开登录页")
        loadUrl(Constants.LOGIN_URL)
    }

    /** 已登录点击徽章：确认后登出 */
    fun confirmLogout(context: android.content.Context) {
        val activity = context as? android.app.Activity
        val dialog = android.app.AlertDialog.Builder(activity ?: return)
            .setTitle("登出")
            .setMessage("确定要退出登录吗？将清除 x.com 的登录 Cookie。")
            .setPositiveButton("登出") { _, _ ->
                locator.authController.logout(webView)
                refreshLoginState()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    private fun scheduleHistoryWrite(url: String) {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            delay(3000)
            writeHistory(url, "", "", "")
        }
    }

    /**
     * 停留超 3s / 点击详情时写入历史。
     * [text]/[displayName]/[mediaUrl] 由页面 JS 从 DOM 提取后经 Bridge 上报。
     *
     * 缩略图时序：点击详情立即写入时，GraphQL 拦截脚本可能尚未把媒体缓存上报原生
     * （缓存晚几百 ms 就绪），此时 cachedThumbnail 为空。若直接落库，历史页将长期
     * 显示无缩略图记录。这里在 mediaUrl 为空时延迟重试：等缓存就绪后补写同一记录。
     */
    fun writeHistory(url: String, text: String = "", displayName: String = "", mediaUrl: String = "") {
        viewModelScope.launch {
            // URL 归一化：/photo/N、/video/N 子页一律指向帖子整页（历史点击回到帖子页而非放大图）
            val norm = url.replace(Regex("/photo/\\d+$"), "").replace(Regex("/video/\\d+$"), "")
            val parsed = HistoryRepo.parseTweetUrl(norm) ?: return@launch
            val (username, tweetId) = parsed
            val enabled = locator.settings.historyEnabled.first()
            if (!enabled) return@launch
            // 页面 JS 未上报标题时（非详情点击路径），主动回读页面
            var t = text
            var name = displayName
            var mUrl = mediaUrl
            if (t.isBlank() && mUrl.isBlank()) {
                val meta = queryPageForMetadata()
                t = meta.first
                mUrl = meta.second
            }
            // 视频帖：DOM 提取常拿不到海报帧（可能在 <video poster> 属性 / mediaViewer 结构差异），
            // 兜底复用 GraphQL 缓存的缩略图（下载链路已验证拿到 ext_tw_video_thumb 海报）
            if (mUrl.isBlank()) {
                mUrl = locator.mediaParser.cachedThumbnail(tweetId)
            }
            val record = HistoryRecord(
                url = norm,
                tweetId = tweetId,
                username = username,
                displayName = name,
                textPreview = t,
                mediaUrl = mUrl,
                visitedAt = System.currentTimeMillis(),
            )
            locator.historyRepo.upsert(record)
            // 缩略图本地化：落盘到 filesDir/thumb/history-{tweetId}.jpg（重复访问覆盖同一文件）
            if (mUrl.isNotBlank()) {
                val key = "history-$tweetId"
                val thumbPath = com.xverse.app.core.util.ThumbCache.persist(
                    locator.appContext, key, mUrl
                )
                if (thumbPath.isNotBlank()) {
                    locator.historyRepo.upsert(record.copy(thumbPath = thumbPath))
                }
            }
            LogStore.log(LogCategory.HISTORY, "已记录: @$username/$tweetId")
            // 缩略图缺失（点击详情早于 GraphQL 缓存）：延迟重试，缓存就绪后补写同一记录。
            // 最多 4 次 × 1s，覆盖「进页面即点详情」的最坏时序。
            if (mUrl.isBlank()) {
                for (attempt in 1..4) {
                    delay(1000)
                    val cached = locator.mediaParser.cachedThumbnail(tweetId)
                    if (cached.isBlank()) continue
                    locator.historyRepo.upsert(
                        record.copy(mediaUrl = cached, visitedAt = System.currentTimeMillis())
                    )
                    val thumbPath = com.xverse.app.core.util.ThumbCache.persist(
                        locator.appContext, "history-$tweetId", cached
                    )
                    if (thumbPath.isNotBlank()) {
                        locator.historyRepo.upsert(record.copy(mediaUrl = cached, thumbPath = thumbPath))
                    }
                    LogStore.log(LogCategory.HISTORY, "缩略图补写: @$username/$tweetId")
                    break
                }
            }
        }
    }

    /** 主线程 evaluateJavascript 读页面元数据（tweetText 摘要 + 媒体缩略图），返回 (text, mediaUrl) */
    private suspend fun queryPageForMetadata(): Pair<String, String> {
        val wv = webView ?: return "" to ""
        return withContext(kotlinx.coroutines.Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript(META_READER_SCRIPT) { r ->
                    val raw = r ?: "null"
                    // evaluateJavascript 对返回值再做一次 JSON 编码：脚本返回 JSON 字符串时，
                    // 回调拿到的是带转义的字符串字面量，先解包再解析
                    val json = try {
                        val first = org.json.JSONTokener(raw).nextValue()
                        if (first is String) org.json.JSONObject(first) else org.json.JSONObject()
                    } catch (_: Exception) {
                        org.json.JSONObject()
                    }
                    cont.resume(json.optString("text") to json.optString("mediaUrl"))
                }
            }
        }
    }

    fun goBack() {
        val wv = webView ?: return
        if (wv.canGoBack()) wv.goBack()
    }

    fun goForward() {
        webView?.takeIf { it.canGoForward() }?.goForward()
    }

    fun reload() {
        webView?.reload()
    }

    fun goHome() {
        webView?.loadUrl("https://x.com")
    }

    /**
     * 瞬显打开推文：WebView 当前已在 x.com 应用内（同源）时，用 SPA 路由
     * history.pushState + 模拟 popstate 在 React 应用内部切页 —— 应用仍存活，
     * 只切路由 + fetch 新帖数据，无需整页重载（不会闪 X 徽标，近似原生瞬显）。
     * 若当前不在 x.com 或页面未就绪，退化为整页 loadUrl。
     */
    fun openTweetInstant(url: String) {
        val wv = webView ?: run { pendingUrl = url; return }
        viewModelScope.launch {
            // 同源判断：当前页确为 x.com（含子域），React 应用应已加载
            val cur = currentPageUrl()
            if (!isXComPage(cur)) {
                wv.loadUrl(url)
                return@launch
            }
            val ok = withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val js = """
                        (function(){
                          try {
                            history.pushState({}, '', '$url');
                            window.dispatchEvent(new PopStateEvent('popstate', {state: history.state}));
                            return 'ok';
                          } catch (e) { return 'err:' + e.message; }
                        })();
                    """.trimIndent()
                    wv.evaluateJavascript(js) { r ->
                        val raw = r?.removeSurrounding("\"") ?: ""
                        cont.resume(raw == "ok")
                    }
                }
            }
            if (!ok) wv.loadUrl(url)
        }
    }

    /** 判断页面地址是否 x.com（http/https + x.com/twitter.com 域） */
    private fun isXComPage(u: String): Boolean =
        u.startsWith("https://") && (
            u.startsWith("https://x.com") ||
            u.startsWith("https://www.x.com") ||
            u.startsWith("https://twitter.com") ||
            u.startsWith("https://mobile.twitter.com")
            )


    /** 解析当前推文媒体（下拉打开时调用；重复点击只重新解析不重复入队） */
    fun refreshMediaList() {
        val wv = webView ?: return
        // 同步置「解析中」：下拉打开的瞬间就是 spinner。
        // 不能把置位放协程里——currentPageUrl 走 evaluateJavascript 异步回调，
        // 那之前 parsing=false + 空列表会先渲染「未解析到媒体」，闪烁后才进解析态。
        _parsing.value = true
        _mediaList.value = emptyList()
        viewModelScope.launch {
            try {
                // 实时读 location.href：x.com 是 SPA（pushState 导航），
                // url StateFlow 只在整页加载（onPageFinished）时更新，停留在首页等旧地址
                val tweetUrl = currentPageUrl()
                if (tweetUrl.isBlank() || !isTweetUrl(tweetUrl)) {
                    toast("请在推文页面使用下载")
                    return@launch
                }
                val items = locator.downloadController.parseTweet(tweetUrl)
                _mediaList.value = items
                if (items.isEmpty()) toast("未解析到媒体")
            } finally {
                _parsing.value = false
            }
        }
    }

    /** 实时读取页面地址（evaluateJavascript 通道，SPA 导航也准确） */
    private suspend fun currentPageUrl(): String {
        val wv = webView ?: return ""
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript("(function(){ return location.href; })();") { r ->
                    // evaluateJavascript 对返回值做 JSON 编码：字符串会带引号，先解包
                    val raw = r ?: return@evaluateJavascript cont.resume("")
                    val value = try {
                        org.json.JSONTokener(raw).nextValue() as? String
                    } catch (_: Exception) {
                        null
                    } ?: raw
                    cont.resume(value)
                }
            }
        }
    }

    /** 判断是否为推文页 URL（/user/status/id，含 mediaViewer 等子页） */
    private fun isTweetUrl(url: String): Boolean =
        url.contains("/status/") || url.contains("currentTweet=")

    /** 顶栏下拉选择清晰度 → 入队下载 */
    fun enqueueMedia(item: MediaItem) {
        viewModelScope.launch {
            // 与解析一致：实时读 location.href（SPA 导航下 url StateFlow 可能停在旧地址）
            val tweetUrl = currentPageUrl()
            if (tweetUrl.isBlank()) return@launch
            val ok = locator.downloadController.enqueue(tweetUrl, item)
            toast(if (ok) "已加入下载队列" else "下载入队失败")
        }
    }

    /** WebChromeClient 进度回调 */
    fun onProgress(p: Int) {
        progress.value = p
    }

    override fun onCleared() {
        historyJob?.cancel()
        super.onCleared()
    }

    /** 探针模式开关（adb 广播切换）：true = 零注入对照实验 */
    @Volatile
    var probeMode: Boolean = false

    /**
     * 探针模式切换（adb 广播）：置开关 + 清注入列表 + reload 首页。
     * 下一整页加载即按新模式注入（探针=零注入 / 正常=全量）。
     */
    fun enterProbeMode(on: Boolean) {
        probeMode = on
        LogStore.log(
            LogCategory.FILTER,
            "探针模式 ${if (on) "开" else "关"}：${if (on) "零注入对照" else "恢复正常注入"}",
        )
        rebuildWebView()
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return BrowserViewModel() as T
            }
        }

        /**
         * SPA 返回滚动恢复（document_start 注入）：
         * x.com 是 React SPA，返回首页时虚拟列表重排 + 广告隐藏/剥离会改变像素布局，
         * 纯像素恢复会落在不同帖子上（乱跳）。方案改为**锚点帖恢复**：
         *  - 不 patch history（x.com 会覆盖 pushState/replaceState，上一版已验证），
         *    全用事件监听 + 自维护 Map，按 pathname 记录快照；
         *  - 滚动时记录视口顶部附近「可见」帖子的 tweet id + 距视口顶偏移（隐藏帖不参与锚定）；
         *  - 返回（popstate）时先按像素粗略复位（让虚拟列表渲染到锚点附近），
         *    再轮询找锚点帖精确定位，稳定两次后停，图片加载高度抖动不反复跳。
         * 工程规范 #1：内联 IIFE；#4：注释一律行注释。
         */
        private val SCROLL_RESTORE_SCRIPT = """
            (function(){
              'use strict';
              if (window.__xvScrollRestore) return;
              window.__xvScrollRestore = true;

              // ---- 根因：x.com 把 scrollRestoration 设为 manual，禁用浏览器原生滚动恢复 ----
              // manual 下 WebView 的 SPA popstate 不做原生恢复，返回首页时页面停在顶部，
              // 全凭脚本 scrollTo 去追虚拟列表流式渲染，脚本与内容回流打架 → 跳动。
              // 解法：拦截 scrollRestoration 的 setter，把 manual 拦成 auto，
              // 让浏览器在 back() 时一帧恢复滚动位置，虚拟列表从正确位置往下渲染，无漂移。
              // （实测：auto 下 back() 后 scrollTop 瞬间 8000→12821 恢复原位置。）
              try {
                var srDesc = Object.getOwnPropertyDescriptor(History.prototype, 'scrollRestoration');
                if (srDesc && srDesc.set) {
                  Object.defineProperty(History.prototype, 'scrollRestoration', {
                    configurable: true,
                    enumerable: true,
                    get: function() { return srDesc.get.call(this); },
                    set: function(v) {
                      return srDesc.set.call(this, v === 'manual' ? 'auto' : v);
                    }
                  });
                }
                history.scrollRestoration = 'auto';
              } catch (e) {}

              var routeMap = {};

              function scrollTop() {
                var d = document.scrollingElement || document.documentElement;
                return d ? d.scrollTop : 0;
              }

              // 可见判断：display:none 的隐藏帖 rect 全为 0，不能当锚点
              function visible(el) {
                return el.offsetWidth > 0 && el.offsetHeight > 0;
              }

              // 取帖子的 status id（链接 /user/status/<id>）
              function tweetId(art) {
                var a = art.querySelector('a[href*="/status/"]');
                if (!a) return '';
                var m = (a.getAttribute('href') || '').match(/\/status\/(\d+)/);
                return m ? m[1] : '';
              }

              // 记录当前路由快照：视口顶部附近的第一篇可见帖 + 像素兜底
              function snapshot() {
                var arts = document.querySelectorAll('article[data-testid="tweet"]');
                var best = null, bestDist = 1e9;
                for (var i = 0; i < arts.length; i++) {
                  if (!visible(arts[i])) continue;
                  var r = arts[i].getBoundingClientRect();
                  if (r.top < -r.height) continue; // 已完全滚出视口上方
                  var d = r.top < 0 ? -r.top : r.top;
                  if (d < bestDist) { bestDist = d; best = arts[i]; }
                }
                if (!best) { routeMap[location.pathname] = { pixel: scrollTop() }; return; }
                routeMap[location.pathname] = {
                  id: tweetId(best),
                  off: Math.round(best.getBoundingClientRect().top),
                  pixel: scrollTop()
                };
              }

              // 节流记录（200ms），passive 不阻塞滚动。
              // 恢复期间 suppressSnap：恢复中的 scrollTo 会触发 scroll 事件，
              // 若不挡会把"恢复中间态"写回快照，污染下次返回（自反馈）。
              var suppressSnap = false;
              var lastRec = 0;
              window.addEventListener('scroll', function() {
                var now = Date.now();
                if (now - lastRec < 200) return;
                lastRec = now;
                if (suppressSnap) return;
                try { snapshot(); } catch (e) {}
              }, { passive: true });

              // 返回时恢复（原生恢复优先 + 惰性校正兜底）：
              // 1) scrollRestoration 已 hook 成 auto，浏览器在 back() 时一帧原生恢复滚动位置，
              //    虚拟列表从正确位置往下渲染，无"填上方空隙"的漂移；
              // 2) 脚本不立即 scrollTo 覆盖原生恢复——先观望：原生恢复完成时 scrollTop 会
              //    自行跳到 rec.pixel 附近（实测 st=53→11228，恢复率 ~99.3%）。
              //    仅当观望 2s 后 scrollTop 仍停在顶部（原生恢复未发生，如直接跳转）才粗定位；
              // 3) 之后惰性观察锚点帖：等布局稳定（连续 3 次不变）最多校正一次，对齐快照偏移。
              // 全程至多 2 次 scrollTo（且都避开了原生恢复的窗口），杜绝与原生打架的跳动。
              var timer = null, stable = 0, lastAnchor = -1, done = false, coarseDone = false;
              var restoreStart = 0;
              function restore() {
                var rec = routeMap[location.pathname];
                if (!rec) return;
                if (timer) { clearInterval(timer); timer = null; }
                stable = 0; lastAnchor = -1; done = false; coarseDone = false;
                suppressSnap = true;
                restoreStart = Date.now();
                rlog('RESTORE', 'rec.pixel=' + rec.pixel + ' id=' + (rec.id || '-'));
                var tries = 0;
                timer = setInterval(function() {
                  tries++;
                  if (tries > 60) { clearInterval(timer); timer = null; suppressSnap = false; rlog('GIVEUP', 'tries=' + tries); return; }
                  // 首帧观望：给原生 scroll restoration 时间跑完（虚拟列表首次渲染 + 布局）。
                  // 原生恢复成功时 st 会自行接近 rec.pixel，此时绝不 scrollTo 覆盖。
                  // 仅当恢复发生后长时间 st 仍停在顶部（原生没恢复，如直接 SPA 跳转），
                  // 才粗定位兜底，避免与原生恢复打架造成二次跳动。
                  if (!coarseDone) {
                    var stNow = scrollTop();
                    var elapsed = Date.now() - restoreStart;
                    if (rec.pixel != null && Math.abs(stNow - rec.pixel) > 60 && stNow < 300 && elapsed > 2000) {
                      rlog('COARSE', 'to=' + rec.pixel + ' (st=' + Math.round(stNow) + ')');
                      window.scrollTo(0, rec.pixel);
                    }
                    coarseDone = true;
                  }
                  var anchor = null;
                  if (rec.id) {
                    var arts = document.querySelectorAll('article[data-testid="tweet"]');
                    for (var i = 0; i < arts.length; i++) {
                      if (!visible(arts[i])) continue;
                      if (tweetId(arts[i]) !== rec.id) continue;
                      anchor = Math.round(arts[i].getBoundingClientRect().top + scrollTop() - rec.off);
                      break;
                    }
                  }
                  if (anchor == null && !rec.id) {
                    // 无锚点帖的兜底：稳定到像素位置即认为恢复完成
                    anchor = rec.pixel != null ? rec.pixel : null;
                    if (anchor != null && Math.abs(scrollTop() - anchor) <= 3) anchor = -1;
                  }
                  if (anchor == null) { rlog('NOANCHOR', 'tries=' + tries); return; }
                  if (anchor === lastAnchor) {
                    stable++;
                    rlog('OBS', 'anchor=' + anchor + ' stable=' + stable);
                    if (stable >= 3 && !done) {
                      done = true;
                      // 原生恢复优先：scrollTop 已接近 rec.pixel（恢复成功）时，
                      // 不再 scrollTo 校正——避免返回后页面「又动一下」的二次跳动。
                      // 仅当 scrollTop 距 rec.pixel 仍很远（原生恢复失败/落错帖，如直接跳转）
                      // 才做锚点校正兜底。
                      if (Math.abs(scrollTop() - rec.pixel) > 300) {
                        rlog('CORRECT', 'to=' + anchor + ' (st=' + Math.round(scrollTop()) + ')');
                        window.scrollTo(0, anchor);
                      } else {
                        rlog('SKIP', 'native-recovered st=' + Math.round(scrollTop()) + ' near=' + rec.pixel);
                      }
                    }
                    if (stable >= 5) { clearInterval(timer); timer = null; suppressSnap = false; rlog('DONE', 'final=' + Math.round(scrollTop())); }
                  } else {
                    stable = 0;
                  }
                  lastAnchor = anchor;
                }, 200);
              }

              window.addEventListener('popstate', restore);
              window.addEventListener('pageshow', function(e) { if (e.persisted) restore(); });

              // 调试钩子：CDP 验证时读快照 map 与恢复状态
              window.__xvScrollDebug = function() {
                return JSON.stringify({ map: routeMap, restoring: !!timer });
              };
              // 恢复动作日志：记录脚本每次 scrollTo/观察/校正，用于区分
              // "脚本在动" vs "页面自身回流"（广告隐藏/虚拟列表重排）。
              window.__xvRestoreLog = [];
              function rlog(act, extra) {
                if (window.__xvRestoreLog.length > 300) window.__xvRestoreLog.shift();
                window.__xvRestoreLog.push(act + '|st=' + Math.round(scrollTop()) + (extra != null ? '|' + extra : ''));
              }
            })();
        """.trimIndent()

        /** 历史上报脚本：document_idle 注入，点击详情/媒体链接时上报（含行注释规范）。
         *  同时从 DOM 提取推文正文摘要（tweetText）与媒体缩略图直链。
         */
        private val HISTORY_TRACKING_SCRIPT = """
            // 历史记录：点击推文详情/媒体链接时上报原生
            // URL 归一化：photo/N、video/N 等媒体子页一律指向帖子整页（历史点击回到帖子页，而非放大图）
            function xvNorm(h) {
              return (h || '').replace(/\/photo\/\d+$/, '').replace(/\/video\/\d+$/, '');
            }
            // 元数据从被点击链接所在的 article 提取：
            // 时间线上 querySelector('article') 会取到页面第一个帖子，与点中的那条张冠李戴，
            // 多图帖因此丢失正文/缩略图（记成了别的帖子）。
            function xvMeta(anchor) {
              var t = '', n = '', m = '';
              var art = anchor && anchor.closest ? anchor.closest('article') : null;
              if (!art) art = document.querySelector('article');
              if (art) {
                var text = art.querySelector('[data-testid="tweetText"]');
                t = text ? text.innerText.trim().slice(0, 200) : '';
                var nameEl = art.querySelector('[data-testid="User-Name"]');
                n = nameEl ? nameEl.innerText.split(String.fromCharCode(10))[0].trim() : '';
                // 预览图：优先帖子图片直链；视频帖取海报帧（ext_tw_video_thumb / amplify_video_thumb，非 /media/）
                var img = art.querySelector('img[src*="pbs.twimg.com/media/"]');
                if (!img || !img.src) {
                  var vp = art.querySelector('img[src*="ext_tw_video_thumb/"], img[src*="amplify_video_thumb/"]');
                  img = vp || null;
                }
                if ((!img || !img.src) && art.querySelector('video[poster]')) {
                  img = art.querySelector('video[poster]');
                }
                if (img && (img.src || img.poster)) {
                  m = (img.src || img.poster).split('?')[0] + '?format=jpg&name=small';
                }
              }
              return {text:t, name:n, mediaUrl:m};
            }
            document.addEventListener('click', function(e){
              var a = e.target && e.target.closest ? e.target.closest('a[href*="/status/"]') : null;
              if (!a) {
                // 兜底：点击帖子卡片主体（正文）进入详情。
                // x.com 首页正文是独立 div 不在 <a> 内，只有图片/视频/时间戳是链接；
                // 点正文 SPA 路由跳转、整页不重载，onPageFinished 的 3s 停留不触发，
                // 只能在此补记录。排除 action 栏（不导航）与用户主页/hashtag 导航，避免误记。
                var art = e.target && e.target.closest ? e.target.closest('article[data-testid="tweet"]') : null;
                if (!art) return;
                if (e.target.closest('[data-testid="caret"],[data-testid="share"],[data-testid^="reply"],[data-testid^="retweet"],[data-testid^="like"],[data-testid^="bookmark"]')) return;
                // 点击落在链接上但非帖子详情（用户主页/hashtag/外部/analytics）→ 不记
                var cl = e.target.closest('a');
                if (cl && !/\/status\/\d+$/.test(cl.getAttribute('href') || '')) return;
                // 取文章内帖子链接（排除 analytics）作为记录 URL
                var sl = art.querySelector('a[href*="/status/"]:not([href*="/analytics"])');
                if (sl && window.XVerseNative) {
                  var sh = xvNorm(sl.getAttribute('href') || '');
                  var m2 = sh.match(/\/status\/\d+/);
                  if (m2) {
                    var meta2 = xvMeta(sl);
                    var url2 = sh.indexOf('http') === 0 ? sh : (location.origin + sh);
                    XVerseNative.call('recordHistory', JSON.stringify({
                      url: url2, text: meta2.text, name: meta2.name, mediaUrl: meta2.mediaUrl
                    }));
                  }
                }
                return;
              }
              var href = xvNorm(a.getAttribute('href') || '');
              if (!href) return;
              var url = href.indexOf('http') === 0 ? href : (location.origin + href);
              if (window.XVerseNative) {
                var meta = xvMeta(a);
                XVerseNative.call('recordHistory', JSON.stringify({
                  url: url, text: meta.text, name: meta.name, mediaUrl: meta.mediaUrl
                }));
              }
            }, true);
        """.trimIndent()

        /** 页面元数据读取（停留路径被动回读）：返回 JSON 字符串 */
        private val META_READER_SCRIPT = """
            (function(){
              var art = document.querySelector('article');
              var t = '', m = '';
              if (art) {
                var text = art.querySelector('[data-testid="tweetText"]');
                t = text ? text.innerText.trim().slice(0, 200) : '';
                // 预览图：优先帖子图片直链；视频帖取海报帧（ext_tw_video_thumb / amplify_video_thumb）
                var img = art.querySelector('img[src*="pbs.twimg.com/media/"]');
                if (!img || !img.src) {
                  var vp = art.querySelector('img[src*="ext_tw_video_thumb/"], img[src*="amplify_video_thumb/"]');
                  img = vp || null;
                }
                if ((!img || !img.src) && art.querySelector('video[poster]')) {
                  img = art.querySelector('video[poster]');
                }
                if (img && (img.src || img.poster)) m = (img.src || img.poster).split('?')[0] + '?format=jpg&name=small';
              }
              return JSON.stringify({text:t, mediaUrl:m});
            })();
        """.trimIndent()

        /** GraphQL TweetDetail 响应拦截（X-Vault 思路，document_start 注入）：
         *  patch fetch/XHR，命中 TweetDetail 时把响应 JSON 经 Bridge 上报原生缓存媒体直链。
         *  工程规范 #1：内联 IIFE；#4：含 URL 注释一律行注释。
         *  document_start 时 window.fetch 可能未就绪，用重试等待（最多 5s）。
         */
        private val GRAPHQL_HOOK_SCRIPT = """
            (function(){
              // 低侵入 GraphQL 拦截（参照 BetterX 防御手法）：
              //  - 快速路径预扫描：响应不含 extended_entities 就不 JSON.parse
              //  - 响应大小守卫：>8MB 跳过
              //  - XHR 用 load 一次性监听（once:true，自动移除，不泄漏）
              //  - watchdog 周期重装（SPA 重建 / 原型被替换后恢复）
              //  - 全程 try/catch，绝不抛错，绝不阻塞原请求
              if (window.__xvGraphqlHooked) return;
              window.__xvGraphqlHooked = true;
              // 放宽到所有 /graphql/ 请求：mediaViewer 用 TweetResultByRestId 等端点加载，
              // 不只 TweetDetail；响应含媒体才解析上报（快速路径过滤，性能可接受）
              var endpointTest = /\/graphql\/[A-Za-z0-9_-]+\//;
              var MAX_BYTES = 8388608; // 8MB
              // 当前页面推文 id（/user/status/12345 → 12345），用于缓存按推文区分
              var pageTweetId = '';
              var pm = location.pathname.match(/\/status\/(\d+)/);
              if (pm) pageTweetId = pm[1];

              function tryReport(text) {
                // 快速路径：不含媒体标记直接跳过（避免对每个 API 响应做 JSON.parse）
                if (text.indexOf('extended_entities') < 0 &&
                    text.indexOf('"media"') < 0 &&
                    text.indexOf('media_url_https') < 0) return;
                try {
                  var j = JSON.parse(text);
                  var d = j && j.data || {};
                  // 递归找含媒体数组的推文对象（深度上限 20）
                  function hasMediaObj(o) {
                    return o && typeof o === 'object' && o.media_url_https;
                  }
                  function findTweet(obj, depth) {
                    if (!obj || typeof obj !== 'object' || depth > 20) return null;
                    var leg = obj.legacy;
                    if (leg) {
                      var em = leg.extended_entities;
                      if (em && em.media && em.media.length && hasMediaObj(em.media[0])) return obj;
                      if (leg.entities && leg.entities.media && leg.entities.media.length && hasMediaObj(leg.entities.media[0])) return obj;
                    }
                    if (obj.media && obj.media.length && hasMediaObj(obj.media[0])) return obj;
                    var keys = obj instanceof Array ? obj : Object.keys(obj);
                    for (var i = 0; i < keys.length; i++) {
                      var r = findTweet(obj instanceof Array ? obj[i] : obj[keys[i]], depth + 1);
                      if (r) return r;
                    }
                    return null;
                  }
                  var tresult = findTweet(d, 0);
                  if (!tresult) return;
                  var media = (tresult.legacy && tresult.legacy.extended_entities &&
                              tresult.legacy.extended_entities.media &&
                              tresult.legacy.extended_entities.media.length)
                              ? tresult.legacy.extended_entities.media
                              : (tresult.media && tresult.media.length ? tresult.media
                                 : (tresult.legacy && tresult.legacy.entities && tresult.legacy.entities.media
                                    ? tresult.legacy.entities.media : []));
                  console.log('[XV] graphql media=' + media.length);
                  if (window.XVerseNative) {
                    // 只上报主推文的 legacy（不含回复线程），避免把评论区媒体也计入
                    var sendObj = tresult.legacy ? {legacy: tresult.legacy} : {media: media};
                    // 同时上报当前页面推文 id，原生侧按推文隔离缓存
                    var pageId = (tresult.rest_id || tresult.id_str || pageTweetId || '');
                    XVerseNative.call('mediaResponse', JSON.stringify({
                      tweetId: pageId,
                      data: JSON.stringify(sendObj)
                    }));
                  }
                } catch (e) {}
              }

              function onXhrDone(xhr) {
                try {
                  var url = xhr.__xvUrl || '';
                  if (!endpointTest.test(url)) return;
                  var body = xhr.responseText;
                  if (!body && xhr.responseType === 'json' && xhr.response) {
                    body = JSON.stringify(xhr.response);
                  }
                  if (body && body.length > 100 && body.length < MAX_BYTES) tryReport(body);
                } catch (e) {}
              }

              var installed = false;
              function install() {
                try {
                  var origFetch = window.fetch;
                  if (origFetch && !window.__xvFetchHooked) {
                    window.fetch = function(input, init) {
                      var u = typeof input === 'string' ? input : (input && input.url) || '';
                      var p = origFetch.apply(this, arguments);
                      if (endpointTest.test(u)) {
                        p.then(function(res){
                          try {
                            res.clone().text().then(function(body){
                              if (body && body.length > 100 && body.length < MAX_BYTES) tryReport(body);
                            });
                          } catch (e) {}
                        }).catch(function(){});
                      }
                      return p;
                    };
                    window.__xvFetchHooked = true;
                  }
                  var origOpen = XMLHttpRequest.prototype.open;
                  var origSend = XMLHttpRequest.prototype.send;
                  if (origSend && !window.__xvXhrHooked) {
                    XMLHttpRequest.prototype.open = function(m, url) {
                      this.__xvUrl = url;
                      return origOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function() {
                      // 参照 BetterX：load 一次性监听，自动移除，不泄漏
                      try { this.addEventListener('load', function(){ onXhrDone(this); }, {once:true}); }
                      catch (e) {}
                      return origSend.apply(this, arguments);
                    };
                    window.__xvXhrHooked = true;
                  }
                  installed = true;
                  console.log('[XV] graphql hook ok');
                } catch (e) {}
              }

              // document_start 时 fetch/XHR 可能未就绪，重试等待 + watchdog 兜底
              var tries = 0;
              (function retry() {
                if (installed) return;
                if ((window.fetch || window.XMLHttpRequest) && tries < 25) {
                  install();
                } else if (tries < 25) {
                  tries++;
                  setTimeout(retry, 200);
                }
              })();
            })();
        """.trimIndent()

        /**
         * 探针模式专用只读探针（document_start 注入，零改动）：
         * 不 patch 任何 API（不碰 History/scrollRestoration/滚动），只挂被动监听器——
         *  1. rAF 每帧采样 scrollTop/scrollHeight/pathname/scrollRestoration/顶部推文 id；
         *     静态帧去重（仅 st/h/path/sr 变化才记），防止 100 条相同样本刷屏；
         *  2. popstate 强制打点（返回瞬间，必记）；
         *  3. scroll 事件 150ms 去抖打点。
         * 自动开始，样本永不手动清空（保留全程轨迹）。用于对照 x.com 原生返回行为。
         */
        private val PROBE_ONLY_SCRIPT = """
            (function(){
              'use strict';
              if (window.__xvHf) return;
              var HF = {
                sr0: history.scrollRestoration,
                samples: [],
                lastSt: -1, lastH: -1, lastPath: '', lastSr: ''
              };
              function tid(){
                var arts = document.querySelectorAll('article[data-testid="tweet"]');
                var best = '', bestTop = 1e9, i, a, r, link, hh;
                for (i = 0; i < arts.length; i++) {
                  a = arts[i];
                  if (!a.offsetWidth) continue;
                  r = a.getBoundingClientRect();
                  if (r.top < 0 || r.top > 500) continue;
                  link = a.querySelector('a[href*="/status/"]');
                  if (!link) continue;
                  hh = (link.getAttribute('href') || '').split('/status/')[1];
                  if (!hh) continue;
                  if (r.top < bestTop) { bestTop = r.top; best = hh; }
                }
                return best;
              }
              function rec(tag, force){
                var sc = document.scrollingElement || document.documentElement;
                var st = Math.round(sc.scrollTop), h = Math.round(sc.scrollHeight);
                var path = location.pathname, sr = history.scrollRestoration;
                if (!force && tag === 'rAF' && st === HF.lastSt && h === HF.lastH && path === HF.lastPath && sr === HF.lastSr) return;
                HF.lastSt = st; HF.lastH = h; HF.lastPath = path; HF.lastSr = sr;
                HF.samples.push({ t: Date.now(), tag: tag, st: st, h: h, p: path, sr: sr, id: tid() });
                if (HF.samples.length > 12000) HF.samples.splice(0, 400);
              }
              HF.rec = rec;
              window.addEventListener('popstate', function(){ rec('pop', true); });
              var _t = 0;
              window.addEventListener('scroll', function(){ if (Date.now() - _t < 150) return; _t = Date.now(); rec('scroll', true); }, { passive: true });
              (function loop(){ requestAnimationFrame(function(){ rec('rAF'); loop(); }); })();
              rec('start', true);
              HF.armed = true;
              window.__xvHf = HF;
            })();
        """.trimIndent()
    }
}
