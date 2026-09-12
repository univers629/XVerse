package com.xverse.app.core.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.AttributeSet
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import androidx.core.net.toUri
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import com.xverse.app.core.util.Constants

/**
 * X 专用 WebView 封装：移动 UA、Cookie 持久化、WebAuthn、混合内容、深链。
 * 工程规范 #3：所有 settings / CookieManager 操作仅主线程。
 */
@SuppressLint("SetJavaScriptEnabled")
class XWebView(context: Context, attrs: AttributeSet? = null) : WebView(context, attrs) {

    private val viewportRepair = Runnable {
        if (!isAttachedToWindow || url.isNullOrBlank() || url == "about:blank") return@Runnable
        evaluateJavascript(VIEWPORT_STALE_PROBE) { result ->
            if (result != "true" || !isAttachedToWindow) return@evaluateJavascript
            LogStore.log(LogCategory.WEBVIEW, "Reloading page to repair stale viewport after resize")
            injector.prepareForNavigation()
            reload()
        }
    }

    /** 站点状态迁移第二趟（SW/缓存/IndexedDB）注入完成后，重载一次让客户端干净重新拉取。 */
    private val purgeReload = Runnable {
        if (!isAttachedToWindow) return@Runnable
        LogStore.log(LogCategory.WEBVIEW, "Site state purge: reloading once for clean client fetch")
        injector.prepareForNavigation()
        reload()
    }

    /** 页面加载完成回调 */
    var onPageFinished: ((String) -> Unit)? = null

    /** 资源拦截：返回非 null 表示由外部提供响应（扩展资源服务：/xv-ext/ 同源中继） */
    var onShouldInterceptUrl: ((String) -> android.webkit.WebResourceResponse?)? = null

    /** 脚本注入器（页面事件自动驱动） */
    val injector: JsInjector by lazy { JsInjector(this) }

    init {
        configure()
        LogStore.log(LogCategory.WEBVIEW, "XWebView initialized, WebView version: ${WebView.getCurrentWebViewPackage()?.versionName ?: "Unknown"}")
    }

    private fun configure() {
        installServiceWorkerBlocker()
        setBackgroundColor(Color.BLACK)
        val s = settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.loadWithOverviewMode = false
        s.useWideViewPort = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        // UA：系统 WebView 默认 UA 带三处「这是 WebView」的特征 —— `; wv`、`Version/4.0 `、
        // `Build/xxx`，部分站点（含 x.com 的聊天端点）会据此走降级或拒绝路径。
        // 这里只从系统默认 UA 派生、只做减法：删掉这三处特征，保留真实的 Android 版本、
        // 设备型号与 Chrome 版本，不硬编码、不伪装成桌面 UA。
        // 历史教训：曾硬编码 "Android 10; K" 冒充 Chrome 移动版，被 x.com 按旧设备路径渲染，
        // 所以一律以系统默认 UA 为基准。CHROME_LIKE_UA=false 即完全恢复原行为。
        //
        // 实测（2026-09-11，真机 Android WebView，XVerse 0.7.0）：
        //   改前：点底部栏聊天图标 → "there was an issue loading all of your messages"
        //         + 反复弹 disconnected，且不会出现 E2EE PIN 页；
        //   改后：直接弹出端到端加密 PIN 页，输入 PIN 后会话列表正常加载、消息可收发。
        // 即 x.com 是依据 UA 中的 `wv` 标记识别 WebView 并降级/拒绝 XChat（E2EE）客户端的，
        // 与 TLS、crypto.subtle、WASM 或 XVerse 自身的注入/过滤都无关。
        if (CHROME_LIKE_UA) {
            val ua = chromeLikeUserAgent(s.userAgentString)
            s.userAgentString = ua
            LogStore.log(LogCategory.WEBVIEW, "User agent: $ua")
            // UA 修正后的首次启动：清掉带 `wv` 时期缓存的站点状态，否则覆盖升级的用户
            // 仍会被旧缓存喂回降级客户端（Cookie 不动，无需重新登录）。见 WebViewStateMigration。
            WebViewStateMigration.beforeFirstLoad(context, this)
        }
        s.setSupportMultipleWindows(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // 媒体自动播放：允许无用户手势播放（竖屏刷视频模式需要）。
        // 否则 x.com 滑动到新视频后调用 video.play() 会被手势策略拦截，
        // 停在暂停态，必须手动点播放才能继续刷。
        s.mediaPlaybackRequiresUserGesture = false
        // Google OAuth 跨域 Cookie 需要第三方 Cookie
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        CookieManager.getInstance().setAcceptCookie(true)

        // WebAuthn（系统 WebView 原生支持，需显式开启；Passkey 走 Custom Tab 通道）
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
                WebSettingsCompat.setWebAuthenticationSupport(s, WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP)
            }
        } catch (e: Exception) {
            LogStore.log(LogCategory.WEBVIEW, "Enable WebAuthn failed: ${e.message}")
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // 非 http/https 协议：交给系统浏览器（twitter:// 等）
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    }.onFailure {
                        LogStore.log(LogCategory.WEBVIEW, "Cannot open external URL: $url")
                    }
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): android.webkit.WebResourceResponse? {
                val url = request.url.toString()
                AdNetworkBlocker.intercept(request)?.let { return it }
                // 扩展资源服务：同源中继 https://<页面origin>/xv-ext/<id>/<path> → 本地扩展目录。
                // 同源路径用于绕过 x.com CSP（script-src/connect-src 白名单无自定义 scheme，
                // 但含 'self'），让扩展内容脚本的 import()/fetch() 动态加载可用。
                if (url.contains("/xv-ext/")) {
                    val resp = onShouldInterceptUrl?.invoke(url)
                    if (resp != null) return resp
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                LogStore.log(LogCategory.WEBVIEW, "Page started: $url")
                injector.onPageStarted(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                LogStore.log(LogCategory.WEBVIEW, "Page finished: $url")
                injector.onPageFinished(url)
                // 一次性站点状态迁移第二趟：SW/CacheStorage/IndexedDB 清完后重载一页。
                if (WebViewStateMigration.afterFirstLoad(context, view, url)) {
                    postDelayed(purgeReload, PURGE_RELOAD_DELAY_MS)
                }
                onPageFinished?.invoke(url)
            }
        }

        // 进度回调走 webChromeClient
    }

    /**
     * 把 WebView 默认 UA 收敛成等价的 Chrome for Android UA（只做减法，幂等）：
     *   Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UQ1A.240105.004; wv) AppleWebKit/537.36
     *     (KHTML, like Gecko) Version/4.0 Chrome/140.0.0.0 Mobile Safari/537.36
     *   → Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36
     *     (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36
     * 删除的三处都是 WebView 独有的标记，不改变设备型号 / Android 版本 / Chrome 版本。
     * 默认 UA 读取失败时回退系统默认值，不抛异常。
     */
    private fun chromeLikeUserAgent(ua: String?): String {
        val base = ua?.takeIf { it.isNotBlank() } ?: WebSettings.getDefaultUserAgent(context)
        return base
            .replace("; wv", "")
            .replace(BUILD_TOKEN, "")
            .replace("Version/4.0 ", "")
    }

    /** 提供给外部的 WebChromeClient 设置 */
    fun setChromeClient(client: android.webkit.WebChromeClient) {
        webChromeClient = client
    }

    /** 主线程读取 Cookie（供后台线程经 UiExecutor 调用） */
    fun getCookiesFor(url: String): String = CookieManager.getInstance().getCookie(url) ?: ""

    /** 判断是否已登录 x.com（ct0 访客同样存在，只有 auth_token 才代表真实登录）。 */
    fun isLoggedIn(): Boolean {
        val cookies = getCookiesFor(Constants.HOME_URL)
        return cookies.contains("auth_token=")
    }

    /** 广告过滤总开关；同时作用于普通 WebView 请求与 Service Worker 请求。 */
    fun setAdNetworkBlocking(
        enabled: Boolean,
        stripMode: Boolean = false,
        rules: List<com.xverse.app.core.data.db.FilterRule> = emptyList(),
        extensionAllowedHosts: Set<String> = emptySet(),
        extensionBlockedHosts: Set<String> = emptySet(),
    ) {
        AdNetworkBlocker.configure(
            enabled,
            stripMode,
            rules,
            extensionAllowedHosts,
            extensionBlockedHosts,
        )
    }

    /**
     * 同步 X 网页主题。X 在启动时读取 night_mode Cookie 创建 React 调色板，
     * 因此 Cookie 变化后调用方需要重载当前页面。
     */
    @Suppress("DEPRECATION")
    fun setDarkTheme(dark: Boolean, onApplied: (cookieChanged: Boolean) -> Unit) {
        val mode = if (dark) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings, mode)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, dark)
            }
        }.onFailure { e ->
            LogStore.log(LogCategory.WEBVIEW, "Set WebView color mode failed: ${e.message}")
        }

        val cookieManager = CookieManager.getInstance()
        val cookieValue = if (dark) "2" else "0"
        val targets = listOf(
            "https://x.com" to ".x.com",
            "https://twitter.com" to ".twitter.com",
        ).filter { (url, _) ->
            readCookie(cookieManager.getCookie(url), "night_mode") != cookieValue
        }
        if (targets.isEmpty()) {
            onApplied(false)
            return
        }

        var remaining = targets.size
        targets.forEach { (url, domain) ->
            cookieManager.setCookie(
                url,
                "night_mode=$cookieValue; Domain=$domain; Path=/; Max-Age=31536000; Secure; SameSite=Lax",
            ) {
                remaining -= 1
                if (remaining == 0) {
                    cookieManager.flush()
                    onApplied(true)
                }
            }
        }
    }

    /**
     * Some WebView builds retain the landscape visual viewport after returning to portrait.
     * Wait for the Android view to finish resizing, then reload only when Chromium still
     * reports a clipped or offset viewport and no editable element owns focus.
     */
    fun repairViewportAfterResize() {
        removeCallbacks(viewportRepair)
        requestLayout()
        postInvalidateOnAnimation()
        postDelayed(viewportRepair, VIEWPORT_REPAIR_DELAY_MS)
    }

    override fun destroy() {
        removeCallbacks(viewportRepair)
        removeCallbacks(purgeReload)
        setAdNetworkBlocking(false)
        injector.clear()
        stopLoading()
        onPageFinished = null
        onShouldInterceptUrl = null
        removeJavascriptInterface("XVerseNative")
        webChromeClient = android.webkit.WebChromeClient()
        webViewClient = WebViewClient()
        loadUrl("about:blank")
        clearHistory()
        removeAllViews()
        LogStore.log(LogCategory.WEBVIEW, "XWebView destroy")
        super.destroy()
    }

    companion object {
        private const val VIEWPORT_REPAIR_DELAY_MS = 300L

        /** 站点状态迁移第二趟注入后，等 SW 注销/缓存删除落地再重载。 */
        private const val PURGE_RELOAD_DELAY_MS = 2500L
        private val VIEWPORT_STALE_PROBE = """
            (function() {
              var vv = window.visualViewport;
              if (!vv) return false;
              var active = document.activeElement;
              var editing = active && (
                /^(INPUT|TEXTAREA|SELECT)$/.test(active.tagName) || active.isContentEditable
              );
              if (editing) return false;
              var layoutHeight = Math.max(
                window.innerHeight || 0,
                document.documentElement ? document.documentElement.clientHeight : 0
              );
              if (!layoutHeight) return false;
              var clipped = layoutHeight - vv.height > Math.max(48, layoutHeight * 0.2);
              var offset = Math.abs(vv.offsetTop || 0) > 48;
              return clipped || offset;
            })()
        """.trimIndent()

        @Volatile
        private var serviceWorkerBlockerInstalled = false

        private fun installServiceWorkerBlocker() {
            if (serviceWorkerBlockerInstalled) return
            synchronized(this) {
                if (serviceWorkerBlockerInstalled) return
                ServiceWorkerController.getInstance().setServiceWorkerClient(
                    object : ServiceWorkerClient() {
                        override fun shouldInterceptRequest(request: WebResourceRequest) =
                            AdNetworkBlocker.intercept(request)
                    }
                )
                serviceWorkerBlockerInstalled = true
                LogStore.log(LogCategory.FILTER, "Service Worker ad request blocker installed")
            }
        }

        private fun readCookie(cookies: String?, name: String): String? = cookies
            ?.split(';')
            ?.asSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.substringBefore('=', missingDelimiterValue = "") == name }
            ?.substringAfter('=', missingDelimiterValue = "")

        /** UA 修正总开关：false 时完全使用系统默认 WebView UA（含 `; wv`）。 */
        private const val CHROME_LIKE_UA = true

        /** WebView 默认 UA 里的构建号片段，如 ` Build/UQ1A.240105.004`。 */
        private val BUILD_TOKEN = Regex("""\s+Build/[^;)\s]+""")
    }
}
