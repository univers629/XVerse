package com.xverse.app.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.net.toUri
import com.xverse.app.core.data.db.HistoryRecord
import com.xverse.app.core.data.repo.HistoryRepo
import com.xverse.app.core.download.MediaItem
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import com.xverse.app.core.search.XSearchFavorite
import com.xverse.app.core.search.XSearchHistoryItem
import com.xverse.app.core.search.XSearchQuery
import com.xverse.app.core.search.XSearchStore
import com.xverse.app.core.util.Constants
import com.xverse.app.core.webview.JsInjector
import com.xverse.app.core.webview.XWebView
import com.xverse.app.core.webview.WebAppearanceScript
import com.xverse.app.di.ServiceLocator
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
import java.io.File
import java.lang.ref.WeakReference

/**
 * 浏览器页 ViewModel：持有 XWebView 引用，管理历史写入、注入、登录状态。
 */
class BrowserViewModel(private val locator: ServiceLocator) : ViewModel() {

    private var webViewRef = WeakReference<XWebView>(null)
    private val webView: XWebView? get() = webViewRef.get()

    /** WebView 就绪前收到的 URL（冷启动深链等）挂起，就绪后加载 */
    private var pendingUrl: String? = null

    val progress = MutableStateFlow(0)
    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn
    private var usernameProbeJob: Job? = null
    private var loginProbeJob: Job? = null

    /** 顶栏下载：当前推文的媒体列表（下拉菜单数据源） */
    private val _mediaList = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaList: StateFlow<List<MediaItem>> = _mediaList
    /** 是否正在解析媒体（下拉打开时的加载态） */
    private val _parsing = MutableStateFlow(false)
    val parsing: StateFlow<Boolean> = _parsing

    private var historyJob: Job? = null
    private var initialLoadJob: Job? = null
    private var rebuildJob: Job? = null
    private var filterRulesJob: Job? = null
    private var appearanceSettingsJob: Job? = null
    private var extensionWatchJob: Job? = null
    private var initialized = false
    private var legacyHistoryCleanupStarted = false

    private val xSearchStore by lazy(LazyThreadSafetyMode.NONE) {
        XSearchStore(locator.appContext)
    }
    val searchHistory: StateFlow<List<XSearchHistoryItem>> get() = xSearchStore.history
    val searchFavorites: StateFlow<List<XSearchFavorite>> get() = xSearchStore.favorites

    /** WebView 就绪时由 UI 调用 */
    fun onWebViewReady(view: XWebView) {
        webViewRef = WeakReference(view)
        setupBridge(view)
    }

    /** Compose 释放 AndroidView 时断开 ViewModel 与 View 的生命周期联系。 */
    fun onWebViewReleased(view: XWebView) {
        if (webView === view) {
            webViewRef.clear()
            initialized = false
        }
    }

    /** 首次加载：有挂起 URL 则加载之，否则回首页 */
    fun loadInitial() {
        if (initialLoadJob?.isActive == true) return
        initialLoadJob = viewModelScope.launch {
            val wv = webView ?: return@launch
            // 过滤组件初始注册须在首导航前完成，否则内置 strip/CSS 对首屏 SPA 无效
            loadFilterScripts(wv)
            loadAppearanceScript(wv)
            // 扩展注入提供者首帧注册须在首导航前完成，否则 document_start 组内容脚本错过首屏
            loadExtensions(wv)
            if (webView !== wv) return@launch
            val pending = pendingUrl
            pendingUrl = null
            initialized = true
            wv.injector.prepareForNavigation()
            wv.loadUrl(pending ?: Constants.HOME_URL)
        }
    }

    /** 加载 URL（WebView 未就绪则挂起待命） */
    fun loadUrl(url: String) {
        val wv = webView
        if (wv != null && initialized) {
            wv.injector.prepareForNavigation()
            wv.loadUrl(url)
        } else pendingUrl = url
        if (url.contains("/i/flow/login")) scheduleLoginProbe()
    }

    /** Build history locally, collapse the native panel in UI, then open X's result route. */
    fun executeSearch(query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        xSearchStore.record(normalized)
        loadUrl(XSearchQuery.resultUrl(normalized))
    }

    fun saveSearchFavorite(name: String, query: String) {
        xSearchStore.addFavorite(name, query)
    }

    fun removeSearchFavorite(id: String) {
        xSearchStore.removeFavorite(id)
    }

    fun clearSearchHistory() {
        xSearchStore.clearHistory()
    }

    /** 探针模式切换后重建：清空注入列表 + reload 首页，下一整页加载即按新模式注入 */
    fun rebuildWebView() {
        val wv = webView ?: return
        initialLoadJob?.cancel()
        initialized = true
        rebuildJob?.cancel()
        // 清空注入脚本列表，再按最新设置挂载全量脚本。
        wv.injector.clear()
        wv.setAdNetworkBlocking(false)
        setupBridge(wv)
        rebuildJob = viewModelScope.launch {
            loadFilterScripts(wv)
            loadAppearanceScript(wv)
            loadExtensions(wv)
            if (webView !== wv) return@launch
            wv.injector.prepareForNavigation()
            wv.loadUrl(Constants.HOME_URL)
        }
    }

    private fun setupBridge(view: XWebView) {
        // SPA 返回滚动恢复：document_start 挂载，patch history 记录各路由滚动位置，
        // popstate 返回时按目标路由恢复（重试等虚拟列表渲染到位）
        view.injector.addEarly(SCROLL_RESTORE_SCRIPT)
        // 当前账户识别：复用 X 自己 GraphQL 请求的授权头读取账户设置，不依赖易变的导航 DOM。
        view.injector.addEarly(ACCOUNT_IDENTITY_HOOK)
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
            // 竖屏滑动记录（fromMedia=true）：mediaViewer 无 tweetText/article，
            // 跳过读页面 DOM 兜底（否则取到别的帖子的海报/正文，张冠李戴）
            val fromMedia = payload.optBoolean("fromMedia")
            if (url.isNotEmpty()) writeHistory(url, text, displayName, mediaUrl, fromMedia = fromMedia)
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
        // 用户名优先从导航栏读取；移动布局未渲染个人主页链接时，由账户设置接口异步回传。
        bridge.register("accountName") { payload, _ ->
            payload.optString("username").takeIf { it.isNotBlank() }?.let {
                locator.authController.setUsername(it)
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
        watchAppearanceSettings()
        // 一次性清理历史遗留：修复前的小写 mediaviewer 孤儿记录（进入媒体贴会产生
        // 「点击路径干净 URL + onPageFinished 停留路径 mediaviewer URL」的重复历史）。
        if (!legacyHistoryCleanupStarted) {
            legacyHistoryCleanupStarted = true
            viewModelScope.launch {
                val removed = locator.historyRepo.deleteOrphanMediaviewer()
                if (removed > 0) {
                    LogStore.log(LogCategory.HISTORY, "Cleaned mediaviewer duplicate history: $removed items")
                }
            }
        }

        view.onPageFinished = { pageUrl ->
            refreshLoginState()
            // 推文 URL：启动 3s 停留计时。排除竖屏 mediaViewer——入口视频已由点击路径
            // （HISTORY_TRACKING_SCRIPT click handler）记录带完整正文；这里再写空正文的
            // mediaViewer 记录会与点击记录并存产生重复历史（且归一化后 REPLACE 会覆盖正文）。
            if (pageUrl.contains("/status/") && !pageUrl.contains("/mediaviewer", ignoreCase = true)) {
                scheduleHistoryWrite(pageUrl)
            }
        }
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
        extensionWatchJob?.cancel()
        extensionWatchJob = viewModelScope.launch {
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
            "Extension injection updated: x${enabled.size}",
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
            val maxW = ctx.resources.displayMetrics.widthPixels * 2 / 3
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
                view.setAdNetworkBlocking(false)
                LogStore.log(LogCategory.FILTER, "Filter disabled, skipping injection")
                return true
            }
            locator.ensureBuiltinFilterRules()
            val rules = locator.filterRepo.getEnabled()
            val mode = locator.settings.filterMode.first()
            val extensionDefaultsEnabled = locator.settings.filterExtensionDefaults.first()
            val disabledExtensionGroups = locator.settings.filterExtensionDisabledGroups.first()
            val extensionPacks = if (extensionDefaultsEnabled) {
                locator.extensionRuntime.loadFilterPacks(disabledExtensionGroups)
            } else {
                emptyList()
            }
            val extensionAllowedHosts = extensionPacks.flatMap { it.allowedHosts }.toSet()
            val extensionBlockedHosts = extensionPacks.flatMap { it.blockedHosts }.toSet()
            val extensionCssSelectors = extensionPacks.flatMap { it.cssSelectors }.distinct()
            // 总开关开启时，所有模式都启用通用广告/跟踪域名与自定义网络规则；
            // strip 额外阻断 X 曝光端点并在 GraphQL 层删除推广条目。
            view.setAdNetworkBlocking(
                true,
                stripMode = mode == "strip",
                rules = rules,
                extensionAllowedHosts = extensionAllowedHosts,
                extensionBlockedHosts = extensionBlockedHosts,
            )
            val ccVideos = locator.settings.filterCcVideos.first()
            val aiLabel = locator.settings.filterAiLabel.first()
            // FilterScript 只持有 Application Context，用于读取 assets。
            val fs = com.xverse.app.core.webview.FilterScript(locator.appContext)
            if (reInject) {
                // 规则变更：直接执行用户规则 + CSS（幂等，会覆盖旧匹配结果）
                view.injector.evaluate(fs.userRuleScript(rules))
                view.injector.evaluate(fs.userCss(rules))
                LogStore.log(LogCategory.FILTER, "Filter rules hot-reloaded: x${rules.size}")
                return true
            }
            fs.buildEarlyScripts(
                rules,
                mode,
                ccVideos,
                aiLabel,
                extensionCssSelectors,
            ).forEach { view.injector.addEarly(it) }
            val ccStatus = if (ccVideos) "ON" else "OFF"
            val aiStatus = if (aiLabel) "ON" else "OFF"
            val integratedCount = extensionAllowedHosts.size + extensionBlockedHosts.size + extensionCssSelectors.size
            LogStore.log(
                LogCategory.FILTER,
                "Filter script ready (mode=$mode, cc=$ccStatus, ai=$aiStatus): builtin+user=${rules.size}, integrated=$integratedCount",
            )
            true
        } catch (e: Exception) {
            LogStore.error("Failed to load filter script", e)
            false
        }
    }

    /** 过滤规则变化（增删/开关）→ 热更新已加载页面，无需重载 */
    private fun watchFilterRules() {
        filterRulesJob?.cancel()
        filterRulesJob = viewModelScope.launch {
            locator.filterRepo.observeAll().collect { rules ->
                val wv = webView
                if (wv == null) return@collect
                // 只在页面已加载后热更新（避免首次空跑）
                loadFilterScripts(wv, reInject = true)
            }
        }
    }

    /** 外观开关变化后同步当前页面，并替换后续导航使用的 document_start 脚本。 */
    private fun watchAppearanceSettings() {
        appearanceSettingsJob?.cancel()
        appearanceSettingsJob = viewModelScope.launch {
            locator.settings.hideXBottomBar.collect { hidden ->
                val wv = webView ?: return@collect
                applyAppearanceScript(wv, hidden, updateCurrentPage = initialized)
            }
        }
    }

    private suspend fun loadAppearanceScript(view: XWebView) {
        applyAppearanceScript(view, locator.settings.hideXBottomBar.first(), updateCurrentPage = false)
    }

    private fun applyAppearanceScript(view: XWebView, hidden: Boolean, updateCurrentPage: Boolean) {
        val script = WebAppearanceScript.hideXBottomBar(hidden)
        view.injector.setEarly(WEB_APPEARANCE_SCRIPT_KEY, script)
        if (updateCurrentPage) view.injector.evaluate(JsInjector.wrapIife(script))
        view.injector.prepareForNavigation()
    }
    /** 刷新登录状态（主线程） */
    fun refreshLoginState() {
        val wv = webView ?: return
        _loggedIn.value = wv.isLoggedIn()
        // 同步到 AuthController（供设置页等共享）
        locator.authController.refresh()
        if (_loggedIn.value) scheduleUsernameProbe()
    }

    /** X 登录完成是 SPA 跳转，未必再触发 onPageFinished；登录页期间轮询 Cookie 直至识别账户。 */
    private fun scheduleLoginProbe() {
        loginProbeJob?.cancel()
        loginProbeJob = viewModelScope.launch {
            repeat(90) {
                delay(1000)
                refreshLoginState()
                if (_loggedIn.value && locator.authController.username.value.isNotBlank()) return@launch
            }
        }
    }

    /** 导航栏在页面完成后异步挂载，连续探测直到取到 @用户名，确保账户会话被保存。 */
    private fun scheduleUsernameProbe() {
        if (locator.authController.username.value.isNotBlank()) return
        usernameProbeJob?.cancel()
        usernameProbeJob = viewModelScope.launch {
            repeat(15) {
                val wv = webView ?: return@launch
                if (!wv.isLoggedIn()) return@launch
                // 移动/桌面布局的个人主页入口 data-testid 不同，均从其 href 取当前用户名。
                wv.evaluateJavascript(
                    """(function(){var a=document.querySelector('a[data-testid="AppTabBar_Profile_Link"],a[data-testid="DashButton_ProfileIcon_Link"],a[data-testid="SideNav_AccountSwitcher_Button"] a[href^="/"]');var h=(a&&a.getAttribute('href'))||'';var p=h.split(/[?#]/)[0].split('/').filter(Boolean)[0]||'';if(!p&&window.XVerseNative&&!window.__xvAccountLookup){window.__xvAccountLookup=true;var q=document.cookie.split(';').map(function(x){return x.trim();}).filter(function(x){return x.indexOf('ct0=')===0;})[0]||'';var c=q.slice(4);fetch('/i/api/1.1/account/settings.json',{credentials:'include',headers:c?{'x-csrf-token':decodeURIComponent(c)}:{}}).then(function(r){return r.json();}).then(function(j){if(j&&j.screen_name)XVerseNative.call('accountName',JSON.stringify({username:j.screen_name}));}).catch(function(){});}return /^[A-Za-z0-9_]{1,30}$/.test(p)?p:'';})()"""
                ) { result ->
                    val account = runCatching {
                        org.json.JSONTokener(result).nextValue() as? String
                    }.getOrNull().orEmpty()
                    if (account.isNotBlank()) locator.authController.setUsername(account)
                }
                delay(1000)
                if (locator.authController.username.value.isNotBlank()) return@launch
            }
            LogStore.log(LogCategory.AUTH, "Could not identify current username from navbar")
        }
    }

    // ---- 登录 ----

    /** 打开登录页：直接在 WebView 内加载，Cookie 写入 WebView 存储 */
    fun startLogin() {
        LogStore.log(LogCategory.AUTH, "Opening login page inside WebView")
        loadUrl(Constants.LOGIN_URL)
    }

    /** 登出（确认弹窗在 UI 层，见 BrowserScreen） */
    fun logout() {
        locator.authController.logout(webView)
        refreshLoginState()
    }

    private fun scheduleHistoryWrite(url: String) {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            delay(1500)
            writeHistory(url, "", "", "")
        }
    }

    /**
     * 停留超 1.5s / 点击详情时写入历史。
     * [text]/[displayName]/[mediaUrl] 由页面 JS 从 DOM 提取后经 Bridge 上报。
     *
     * 缩略图时序：点击详情立即写入时，GraphQL 拦截脚本可能尚未把媒体缓存上报原生
     * （缓存晚几百 ms 就绪），此时 cachedThumbnail 为空。若直接落库，历史页将长期
     * 显示无缩略图记录。这里在 mediaUrl 为空时延迟重试：等缓存就绪后补写同一记录。
     */
    fun writeHistory(url: String, text: String = "", displayName: String = "", mediaUrl: String = "", fromMedia: Boolean = false) {
        viewModelScope.launch {
            // 在任务开始时冻结账户归属；后续元数据/缩略图读取会挂起，期间切换账户也不能串写历史。
            val accountUsername = locator.authController.username.value
            if (accountUsername.isBlank()) return@launch
            // URL 归一化：/photo/N、/video/N、/mediaViewer 子页一律指向帖子整页（历史点击回到帖子页而非放大图）。
            // mediaViewer 的 ?currentTweet= 查询参数是当前刷到的视频，路径 /status/<id> 仍是入口帖子。
            // IGNORE_CASE：x.com 实际用小写 /mediaviewer，大小写敏感会把两条 URL 当不同记录写入，
            // 产生「点击路径干净 URL + onPageFinished 停留路径 mediaviewer URL」的重复历史。
            val norm = url
                .replace(Regex("/photo/\\d+$"), "")
                .replace(Regex("/video/\\d+$"), "")
                .replace(Regex("/mediaviewer.*$", RegexOption.IGNORE_CASE), "")
            val parsed = HistoryRepo.parseTweetUrl(norm) ?: return@launch
            val (username, tweetId) = parsed
            // 页面 JS 未上报标题时（非详情点击路径），主动回读页面。
            // 竖屏滑动记录（fromMedia=true）跳过：mediaViewer 无 tweetText/article，
            // 回读会取到别的帖子的海报/正文（张冠李戴，快速滑动连续同图的根源）。
            var t = text
            var name = displayName
            var mUrl = mediaUrl
            if (!fromMedia && t.isBlank() && mUrl.isBlank()) {
                val meta = queryPageForMetadata()
                t = meta.first
                name = meta.second
                mUrl = meta.third
            }
            // 视频帖：DOM 提取常拿不到海报帧（可能在 <video poster> 属性 / mediaViewer 结构差异），
            // 兜底复用 GraphQL 缓存的缩略图（下载链路已验证拿到 ext_tw_video_thumb 海报）
            if (mUrl.isBlank()) {
                mUrl = locator.mediaParser.cachedThumbnail(tweetId)
            }
            val mediaType = historyMediaType(tweetId, mUrl)
            val record = HistoryRecord(
                url = norm,
                tweetId = tweetId,
                accountUsername = accountUsername,
                username = username,
                displayName = name,
                textPreview = t,
                mediaType = mediaType,
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
            LogStore.log(LogCategory.HISTORY, "Recorded: @$username/$tweetId")
            // 缩略图缺失（点击详情早于 GraphQL 缓存）：延迟重试，缓存就绪后补写同一记录。
            // 最多 4 次 × 1s，覆盖「进页面即点详情」的最坏时序。
            if (mUrl.isBlank()) {
                for (attempt in 1..4) {
                    delay(1000)
                    val cached = locator.mediaParser.cachedThumbnail(tweetId)
                    if (cached.isBlank()) continue
                    val cachedType = historyMediaType(tweetId, cached)
                    locator.historyRepo.upsert(
                        record.copy(
                            mediaType = cachedType,
                            mediaUrl = cached,
                            visitedAt = System.currentTimeMillis(),
                        )
                    )
                    val thumbPath = com.xverse.app.core.util.ThumbCache.persist(
                        locator.appContext, "history-$tweetId", cached
                    )
                    if (thumbPath.isNotBlank()) {
                        locator.historyRepo.upsert(
                            record.copy(mediaType = cachedType, mediaUrl = cached, thumbPath = thumbPath)
                        )
                    }
                    LogStore.log(LogCategory.HISTORY, "Thumbnail updated: @$username/$tweetId")
                    break
                }
            }
        }
    }

    /** 历史页只分图片/视频两类；X 的 animated_gif 统一归入视频。 */
    private fun historyMediaType(tweetId: String, mediaUrl: String): String {
        return when (locator.mediaParser.cachedMediaType(tweetId).lowercase()) {
            "photo", "image" -> "photo"
            "video", "gif", "animated_gif" -> "video"
            else -> when {
                mediaUrl.contains("/media/", ignoreCase = true) -> "photo"
                mediaUrl.contains("video_thumb", ignoreCase = true) ||
                    mediaUrl.startsWith("https://video.twimg.com/", ignoreCase = true) -> "video"
                else -> ""
            }
        }
    }

    /** 主线程 evaluateJavascript 读页面元数据（tweetText 摘要 + 昵称 + 媒体缩略图），返回 (text, name, mediaUrl) */
    private suspend fun queryPageForMetadata(): Triple<String, String, String> {
        val wv = webView ?: return Triple("", "", "")
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
                    cont.resume(Triple(json.optString("text"), json.optString("name"), json.optString("mediaUrl")))
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
        webView?.let {
            it.injector.prepareForNavigation()
            it.reload()
        }
    }

    fun goHome() {
        loadUrl(Constants.HOME_URL)
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
                wv.injector.prepareForNavigation()
                wv.loadUrl(url)
                return@launch
            }
            val ok = withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val quotedUrl = org.json.JSONObject.quote(url)
                    val js = """
                        (function(){
                          try {
                            history.pushState({}, '', $quotedUrl);
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
            if (!ok) {
                wv.injector.prepareForNavigation()
                wv.loadUrl(url)
            }
        }
    }

    /** 判断页面地址是否 x.com（http/https + x.com/twitter.com 域） */
    private fun isXComPage(u: String): Boolean =
        runCatching {
            val uri = u.toUri()
            uri.scheme == "https" && uri.host?.lowercase() in X_HOSTS
        }.getOrDefault(false)


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
                    toast(com.xverse.app.R.string.browser_toast_use_in_tweet)
                    return@launch
                }
                val items = locator.downloadController.parseTweet(tweetUrl)
                _mediaList.value = items
                if (items.isEmpty()) toast(com.xverse.app.R.string.browser_no_media_found)
            } finally {
                _parsing.value = false
            }
        }
    }

    private fun toast(resId: Int) {
        toast(com.xverse.app.core.util.LocaleUtils.getString(locator.appContext, resId))
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
            toast(if (ok) com.xverse.app.R.string.browser_toast_queue_success else com.xverse.app.R.string.browser_toast_queue_failed)
        }
    }

    /** WebChromeClient 进度回调 */
    fun onProgress(p: Int) {
        progress.value = p
    }

    override fun onCleared() {
        historyJob?.cancel()
        usernameProbeJob?.cancel()
        loginProbeJob?.cancel()
        initialLoadJob?.cancel()
        rebuildJob?.cancel()
        filterRulesJob?.cancel()
        appearanceSettingsJob?.cancel()
        extensionWatchJob?.cancel()
        webViewRef.clear()
    }

    /**
     * 过滤方式切换后重建注入：清注入列表 + 重新挂全量脚本 + reload 首页。
     * addEarly 列表只增不减，普通 reload 不会更新注入组合（strip 脚本残留/缺失），
     * 必须走重建路径让下一整页加载按新模式注入。
     */
    fun reapplyInjections() {
        LogStore.log(LogCategory.FILTER, "Filter mode changed: rebuilding injection and reloading page")
        rebuildWebView()
    }

    /**
     * 过滤带字幕（CC）视频开关热更新：只改页面内标记 __xvFilterCc，
     * 检测逻辑在已注入的 mutation 脚本内、运行时读标记 → 无需重建注入/reload 首页。
     * 关闭时同时调 __xvFilterCard.revealCc() 恢复已隐藏的 CC 帖（只动 CC 标记，不碰广告帖）。
     * （开关需在设置页改完后回调，否则先读库再 evaluate 读到旧值。）
     */
    fun applyCcFilterSetting(on: Boolean) {
        val wv = webView ?: return
        val js = if (on) {
            "window.__xvFilterCc = true;"
        } else {
            "window.__xvFilterCc = false;" +
                "if (window.__xvFilterCard && window.__xvFilterCard.revealCc)" +
                " window.__xvFilterCard.revealCc();"
        }
        wv.injector.evaluate(js)
        LogStore.log(LogCategory.FILTER, "CC video filter ${if (on) "ON" else "OFF"} (hot-updated flag, no reload)")
    }

    /**
     * 过滤 AI 生成标签（Made with AI）开关热更新：只改页面内标记 __xvFilterAi，
     * 检测逻辑在已注入的 mutation 脚本内（scan 轮询）、运行时读标记 → 无需重建注入/reload。
     * 关闭时同时调 __xvFilterCard.revealAi() 恢复已隐藏的 AI 帖（只动 AI 标记，不碰广告帖）。
     */
    fun applyAiFilterSetting(on: Boolean) {
        val wv = webView ?: return
        val js = if (on) {
            "window.__xvFilterAi = true;"
        } else {
            "window.__xvFilterAi = false;" +
                "if (window.__xvFilterCard && window.__xvFilterCard.revealAi)" +
                " window.__xvFilterCard.revealAi();"
        }
        wv.injector.evaluate(js)
        LogStore.log(LogCategory.FILTER, "AI label filter ${if (on) "ON" else "OFF"} (hot-updated flag, no reload)")
    }

    companion object {
        private const val WEB_APPEARANCE_SCRIPT_KEY = "web-appearance"

        private val X_HOSTS = setOf(
            "x.com",
            "www.x.com",
            "twitter.com",
            "www.twitter.com",
            "mobile.twitter.com",
        )

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                BrowserViewModel((app as com.xverse.app.XVerseApp).locator)
            }
        }

        /**
         * X 的移动布局常只渲染头像按钮，不提供可读取用户名的个人主页链接。此 hook 截获
         * 页面自身请求里的 Bearer 授权头，再请求 account/settings 取得 screen_name 并回传。
         * 不保存授权头，也不改写任意响应。
         */
        private val ACCOUNT_IDENTITY_HOOK = """
            (function(){
              'use strict';
              if(window.__xvAccountIdentityHooked)return;
              window.__xvAccountIdentityHooked=true;
              var requested=false;
              var resolved=false;
              function report(name){
                if(resolved||!name||!window.XVerseNative)return;
                resolved=true;
                XVerseNative.call('accountName',JSON.stringify({username:name}));
              }
              function ownIds(){
                var raw='';try{raw=localStorage.getItem('rweb.recentUserIds')||'';}catch(e){}
                // rweb.recentUserIds 在部分移动布局不会写入；twid=u%3D<id> 是同一会话
                // 可读的用户 ID Cookie，作为可靠兜底。
                var twid=document.cookie.split(';').map(function(x){return x.trim();})
                  .filter(function(x){return x.indexOf('twid=')===0;})[0]||'';
                try{raw+=' '+decodeURIComponent(twid.slice(5));}catch(e){}
                var ids={},m,re=/[0-9]{6,}/g;
                while((m=re.exec(raw)))ids[m[0]]=1;
                return ids;
              }
              function scanResponse(value){
                if(resolved)return;
                var ids=ownIds();if(!Object.keys(ids).length)return;
                var seen=[];
                function walk(node,depth){
                  if(resolved||!node||typeof node!=='object'||depth>16||seen.indexOf(node)>=0)return;
                  seen.push(node);
                  var id=String(node.rest_id||node.id_str||node.id||'');
                  var legacy=node.legacy;
                  if(ids[id]&&legacy&&legacy.screen_name){report(String(legacy.screen_name));return;}
                  var core=node.core;
                  if(ids[id]&&core&&core.screen_name){report(String(core.screen_name));return;}
                  var keys=Object.keys(node);
                  for(var i=0;i<keys.length;i++)walk(node[keys[i]],depth+1);
                }
                walk(value,0);
              }
              function captureResponse(response){
                if(resolved||!response||!response.clone)return;
                response.clone().text().then(function(text){
                  if(text&&text.length<8388608)try{scanResponse(JSON.parse(text));}catch(e){}
                }).catch(function(){});
              }
              function csrf(){
                var q=document.cookie.split(';').map(function(x){return x.trim();})
                  .filter(function(x){return x.indexOf('ct0=')===0;})[0]||'';
                return q.slice(4);
              }
              function headerValue(headers,name){
                if(!headers)return '';
                try{if(headers.get)return headers.get(name)||headers.get(name.toLowerCase())||'';}catch(e){}
                if(Array.isArray(headers)){
                  for(var i=0;i<headers.length;i++)if(String(headers[i][0]).toLowerCase()===name.toLowerCase())return headers[i][1]||'';
                }
                for(var k in headers)if(k.toLowerCase()===name.toLowerCase())return headers[k]||'';
                return '';
              }
              function identify(auth,send){
                if(requested||!auth||!window.XVerseNative)return;
                var ids=ownIds(),id=Object.keys(ids)[0];
                if(!id)return;
                requested=true;
                var c=csrf();
                var headers={authorization:auth,'x-twitter-active-user':'yes','x-twitter-auth-type':'OAuth2Session'};
                if(c)headers['x-csrf-token']=decodeURIComponent(c);
                var variables=encodeURIComponent(JSON.stringify({userId:id,withSafetyModeUserFields:true}));
                send('/i/api/graphql/xvmVfRLmnr1alc5f2dib0Q/UserByRestId?variables='+variables+'&features=%7B%7D',{credentials:'include',headers:headers})
                  .then(function(r){return r.json();})
                  .then(function(j){scanResponse(j);})
                  .catch(function(){requested=false;});
              }
              var nativeFetch=window.fetch;
              if(nativeFetch){
                window.fetch=function(input,init){
                  var auth=headerValue(init&&init.headers,'authorization');
                  if(!auth&&input&&input.headers)auth=headerValue(input.headers,'authorization');
                  identify(auth,nativeFetch);
                  var response=nativeFetch.apply(this,arguments);
                  response.then(captureResponse).catch(function(){});
                  return response;
                };
              }
              var proto=XMLHttpRequest&&XMLHttpRequest.prototype;
              if(proto){
                var setHeader=proto.setRequestHeader,send=proto.send;
                proto.setRequestHeader=function(name,value){
                  if(String(name).toLowerCase()==='authorization')this.__xvAccountAuth=value;
                  return setHeader.apply(this,arguments);
                };
                proto.send=function(){
                  identify(this.__xvAccountAuth,nativeFetch);
                  this.addEventListener('load',function(){
                    if(this.responseType&&this.responseType!=='text')return;
                    try{if(this.responseText&&this.responseText.length<8388608)scanResponse(JSON.parse(this.responseText));}catch(e){}
                  },{once:true});
                  return send.apply(this,arguments);
                };
              }
            })();
        """.trimIndent()

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
            // URL 归一化：photo/N、video/N、mediaViewer 等媒体子页一律指向帖子整页（历史点击回到帖子页，而非放大图）。
            // 大小写不敏感：x.com 实际生成 /mediaviewer（小写），与原生 writeHistory 的归一化保持一致，
            // 避免「点击路径记整页 URL + onPageFinished 停留路径记 mediaviewer URL」双写去重失败。
            function xvNorm(h) {
              return (h || '').replace(/\/photo\/\d+$/i, '').replace(/\/video\/\d+$/i, '').replace(/\/mediaviewer.*$/i, '');
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
                // 预览图：优先帖子图片直链；视频帖取海报帧（ext_tw_video_thumb / amplify_video_thumb，非 /media/）；
                // GIF 帖取 tweet_video_thumb 海报帧（pbs.twimg.com jpg）。video 无 src 只有 poster，先取 poster。
                // 过滤 video.twimg.com 的 mp4 直链（GIF 视频本体，非缩略图）。
                var img = art.querySelector('img[src*="pbs.twimg.com/media/"]');
                if (!img || !img.src) {
                  var vp = art.querySelector('img[src*="ext_tw_video_thumb/"], img[src*="amplify_video_thumb/"], img[src*="tweet_video_thumb/"]');
                  img = vp || null;
                }
                if ((!img || !img.src) && art.querySelector('video[poster]')) {
                  img = art.querySelector('video[poster]');
                }
                if (img && (img.poster || img.src)) {
                  var base2 = (img.poster || img.src).split('?')[0];
                  if (base2.indexOf('/video.twimg.com/') < 0) m = base2 + '?format=jpg&name=small';
                }
              }
              return {text:t, name:n, mediaUrl:m};
            }
            document.addEventListener('click', function(e){
              var a = e.target && e.target.closest ? e.target.closest('a[href*="/status/"]') : null;
              if (!a) {
                // 兜底：点击帖子卡片主体（正文）进入详情。
                // x.com 首页正文是独立 div 不在 <a> 内，只有图片/视频/时间戳是链接；
                // 点正文 SPA 路由跳转、整页不重载，onPageFinished 的 1.5s 停留不触发，
                // 只能在此补记录。排除 action 栏（不导航）与用户主页/hashtag 导航，避免误记。
                var art = e.target && e.target.closest ? e.target.closest('article[data-testid="tweet"]') : null;
                if (!art) return;
                if (e.target.closest('[data-testid="caret"],[data-testid="share"],[data-testid^="reply"],[data-testid^="retweet"],[data-testid^="like"],[data-testid^="bookmark"]')) return;
                // 点击落在链接上但非帖子详情（用户主页/hashtag/外部/analytics）→ 不记
                var cl = e.target.closest('a');
                if (cl && !/\/status\/\d+$/i.test(cl.getAttribute('href') || '')) return;
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

            // 竖屏刷视频模式：/status/<id>/mediaViewer?currentTweet=<id> 用 history.replaceState
            // 切换视频（无点击、无 article 元素、无整页重载），上面的事件监听不会触发，
            // 历史只记入口视频。这里 patch replaceState + 轮询 currentTweet 变化。
            //
            // 缩略图时序问题：滑动瞬间 video.poster 还是上一条视频的旧海报（React 尚未提交
            // 新 DOM），此刻读「正在播放的视频」会连续几条同图。方案——
            // 核心：mediaViewer 的每个 video 元素向上 15 层必带 cellInnerDiv-tweet-<id> 锚点，
            // poster 与 tweetId 的归属由 DOM 结构决定，与播放状态 / currentTweet 竞态无关。
            // 预加载又保证下一条视频的 poster 通常在切换前就已可读。
            //  1. 每轮轮询扫描所有 video，建立 posterForTweet[tweetId] = poster 映射表；
            //  2. 快速滑动离开时，延迟写先落历史（可能无图，原生按 tweetId 从 GraphQL
            //     缓存补齐——mediaViewer 下缓存常空，只保记录）；
            //  3. 已写但缺图的 tweetId，poster 一在映射表出现就带图重写（REPLACE 更新
            //     缩略图，后台把缩略图存完——用户思路）。
            // 入口视频已由点击/停留路径记录（带完整正文），只锚定不覆盖。
            // 防重 guard：同一 JS 上下文可能被注入多份（reload 后旧实例未清理 +
            // onPageFinished 重复触发），多实例会各跑 setInterval / 各 patch
            // replaceState → 一次滑动双写历史。用 window 级标记保证同上下文
            // 只有一份活跃 tracker，重复注入直接退出，杜绝多实例叠加。
            if (window.__xvMediaTracker) return;
            var xvEntry = '';
            var xvCurrent = '';
            var xvSince = 0;
            var xvUsers = {};
            var xvPosters = {};
            var xvWritten = {};   // 已调用过 recordHistory 的 tweetId（防重复写）
            var xvPending = {};   // 已进入延迟写流程的 tweetId
            var xvWithPoster = {}; // 已带 poster 写过的 tweetId（防重复带图重写）
            // 扫 DOM 建立 tweetId → poster 映射：每个 video 元素向上找 cellInnerDiv-tweet-<id>
            // 锚点拿到自己的 tweetId，poster 归属结构确定，不会张冠李戴。同一条 tweet 可能
            // 有多个 video（多图/轮播），只记第一个。
            function xvScanPosters() {
              var vids = document.querySelectorAll('video');
              for (var i = 0; i < vids.length; i++) {
                var v = vids[i];
                if (!v || !v.poster) continue;
                var base = v.poster.split('?')[0];
                if (!base) continue;
                var tid = '';
                var el = v;
                for (var d = 0; d < 15 && el && !tid; d++) {
                  var t = el.getAttribute && el.getAttribute('data-testid');
                  if (t && t.indexOf('cellInnerDiv-tweet-') === 0) tid = t.slice('cellInnerDiv-tweet-'.length);
                  el = el.parentElement;
                }
                if (tid && !xvPosters[tid]) {
                  // poster 可能是 video.twimg.com 完整图（无需 format），或 pbs.twimg.com
                  // amplify_video_thumb（需加 format=jpg&name=small 取缩略图）
                  xvPosters[tid] = base.indexOf('/video.twimg.com/') < 0
                    ? base + '?format=jpg&name=small' : base;
                }
              }
            }
            // 写一条历史：mediaUrl 用该条视频的 poster（映射表捕获）；没捕获到留空，
            // 原生 writeHistory 按 tweetId 从 GraphQL 缓存补齐。fromMedia 标记告诉原生
            // 这是竖屏滑动记录（mediaViewer 无 tweetText/article），跳过读页面 DOM——
            // 否则 fallback 读到的第一个 article 海报会张冠李戴，连续几条同图。
            // force 为 true 时允许带图重写（补上延迟出现的缩略图）；首次写记录置 xvWritten。
            function xvWrite(tweetId, force) {
              var u = xvUsers[tweetId];
              if (!u || !window.XVerseNative) return;
              if (xvWritten[tweetId] && !force) return;
              xvWritten[tweetId] = true;
              if (xvPosters[tweetId]) xvWithPoster[tweetId] = true;
              var url = location.origin + '/' + u + '/status/' + tweetId;
              XVerseNative.call('recordHistory', JSON.stringify({
                url: url, text: '', name: '', mediaUrl: xvPosters[tweetId] || '',
                fromMedia: true
              }));
            }
            // 离开一条视频：还没落库的话记 pending，延迟后补写（后台把缩略图存完）。
            // 海报从映射表查——快速滑动时预加载视频的海报可能已在表中，即使当时还没
            // 预载出来，也等最后一轮再查（xvScanPosters 持续建表），绝不用「当前播放」。
            function xvLeave(tweetId) {
              if (tweetId === xvEntry || xvPending[tweetId]) return;
              xvPending[tweetId] = true;
              setTimeout(function() {
                // 写前刷新映射表：滑动后 poster 通常已就绪，让首次写就带图，
                // 避免「无图写 + 后台补图重写」的双写（REPLACE 去重但会重复下载缩略图）
                xvScanPosters();
                // 首次写（无图也写，保证每条记录都有）；poster 已就绪则带图
                xvWrite(tweetId, false);
              }, 800);
            }
            var xvTrackMedia = function() {
              xvScanPosters();
              var p = new URLSearchParams(location.search);
              var t = p.get('currentTweet');
              if (!t) {
                // 退出竖屏模式：最后一条还没落库的视频补写
                if (xvCurrent && xvCurrent !== xvEntry) xvLeave(xvCurrent);
                xvEntry = ''; xvCurrent = ''; xvUsers = {}; xvPosters = {}; xvWritten = {}; xvPending = {}; xvWithPoster = {};
                return;
              }
              var user = p.get('currentTweetUser');
              if (!user) {
                var mU = location.pathname.match(/^\/([^\/]+)\//);
                user = mU ? mU[1] : '';
              }
              if (user) xvUsers[t] = user;
              if (!xvCurrent) {
                // 首个检测 = 入口视频（点击路径已记录完整信息），只锚定不覆盖
                xvCurrent = t; xvEntry = t; xvSince = Date.now(); return;
              }
              if (t !== xvCurrent) {
                // 滑到下一条：上一条还没落库的话延迟补写（后台存缩略图）
                var leaving = xvCurrent;
                xvCurrent = t; xvSince = Date.now();
                if (leaving !== xvEntry) xvLeave(leaving);
                return;
              }
              // 稳定停留 ≥1s：此刻视口内播放的就是本条视频，poster 已在映射表 → 落库
              if (Date.now() - xvSince >= 1000) {
                if (xvPosters[t] && !xvWritten[t] && t !== xvEntry) xvWrite(t, false);
              }
              // 后台补图：已离开(pending)、已写过但当时没图、现在 poster 出现了 →
              // 带图重写（REPLACE 更新缩略图，用户思路：划走后把缩略图存完）
              var keys = Object.keys(xvPending);
              for (var i = 0; i < keys.length; i++) {
                var tid = keys[i];
                if (xvPending[tid] && xvPosters[tid] && !xvWithPoster[tid]) xvWrite(tid, true);
              }
            };
            try {
              var xvHistoryPatch = history.replaceState.bind(history);
              history.replaceState = function() { xvHistoryPatch.apply(this, arguments); xvTrackMedia(); };
              var xvHistoryPush = history.pushState.bind(history);
              history.pushState = function() { xvHistoryPush.apply(this, arguments); xvTrackMedia(); };
            } catch (e) {}
            // 兜底轮询：replaceState 补丁捕获不到的场景（如直接改 location）也能跟上
            var xvMediaTimer = setInterval(xvTrackMedia, 400);
            window.__xvMediaTracker = {
              dispose: function(){ clearInterval(xvMediaTimer); }
            };
            window.addEventListener('pagehide', function(){
              if (window.__xvMediaTracker) window.__xvMediaTracker.dispose();
            }, {once:true});
        """.trimIndent()

        /** 页面元数据读取（停留路径被动回读）：返回 JSON 字符串 */
        private val META_READER_SCRIPT = """
            (function(){
              var art = document.querySelector('article');
              var t = '', n = '', m = '';
              if (art) {
                var text = art.querySelector('[data-testid="tweetText"]');
                t = text ? text.innerText.trim().slice(0, 200) : '';
                // 昵称：User-Name 内第一行（换行前）即显示名，与点击通道 xvMeta 一致
                var nameEl = art.querySelector('[data-testid="User-Name"]');
                n = nameEl ? nameEl.innerText.split(String.fromCharCode(10))[0].trim() : '';
                // 预览图：优先帖子图片直链；视频帖取海报帧（ext_tw_video_thumb / amplify_video_thumb）；
                // GIF 帖取 tweet_video_thumb 海报帧（pbs.twimg.com 的 jpg，非 video.twimg.com 的 mp4 直链）。
                // video 元素无 src 属性、只有 poster（海报图），先取 poster；img 元素取 src。
                // 过滤 video.twimg.com 的 mp4 直链：那是 GIF 视频本体，不是缩略图（拉了也白拉/解码失败）。
                var img = art.querySelector('img[src*="pbs.twimg.com/media/"]');
                if (!img || !img.src) {
                  var vp = art.querySelector(
                    'img[src*="ext_tw_video_thumb/"], img[src*="amplify_video_thumb/"], img[src*="tweet_video_thumb/"]');
                  img = vp || null;
                }
                if ((!img || !img.src) && art.querySelector('video[poster]')) {
                  img = art.querySelector('video[poster]');
                }
                if (img && (img.poster || img.src)) {
                  var base = (img.poster || img.src).split('?')[0];
                  if (base.indexOf('/video.twimg.com/') < 0) m = base + '?format=jpg&name=small';
                }
              }
              return JSON.stringify({text:t, name:n, mediaUrl:m});
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

    }
}
