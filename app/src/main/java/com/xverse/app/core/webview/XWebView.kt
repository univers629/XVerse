package com.xverse.app.core.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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

    /** 页面进度回调（0-100） */
    var onProgress: ((Int) -> Unit)? = null

    /** 页面加载完成回调 */
    var onPageFinished: ((String) -> Unit)? = null

    /** 页面标题回调 */
    var onTitle: ((String) -> Unit)? = null

    /** 导航拦截：返回 true 表示已消费（交给外部处理） */
    var onShouldOverrideUrl: ((String) -> Boolean)? = null

    /** 资源拦截：返回非 null 表示由外部提供响应（扩展资源服务：/xv-ext/ 同源中继） */
    var onShouldInterceptUrl: ((String) -> android.webkit.WebResourceResponse?)? = null

    /** 脚本注入器（页面事件自动驱动） */
    val injector: JsInjector by lazy { JsInjector(this) }

    init {
        configure()
        LogStore.log(LogCategory.WEBVIEW, "XWebView 初始化，WebView 版本: ${WebView.getCurrentWebViewPackage()?.versionName ?: "未知"}")
    }

    private fun configure() {
        setBackgroundColor(Color.BLACK)
        val s = settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.loadWithOverviewMode = false
        s.useWideViewPort = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        // 使用 WebView 真机默认 UA（含真实 Android 版本/设备型号），不硬编码假 UA。
        // 历史教训：硬编码 "Android 10; K" 曾用于伪装 Chrome 移动版，反而可能被
        // x.com 按旧设备路径渲染；真机 UA 与系统 WebView 一致，兼容性最好。
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
            LogStore.log(LogCategory.WEBVIEW, "启用 WebAuthn 失败: ${e.message}")
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // 非 http/https 协议：交给系统浏览器（twitter:// 等）
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    onShouldOverrideUrl?.invoke(url) ?: return false
                    return true
                }
                return onShouldOverrideUrl?.invoke(url) ?: false
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): android.webkit.WebResourceResponse? {
                val url = request.url.toString()
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
                LogStore.log(LogCategory.WEBVIEW, "页面开始: $url")
                injector.onPageStarted(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                LogStore.log(LogCategory.WEBVIEW, "页面完成: $url")
                injector.onPageFinished(url)
                onPageFinished?.invoke(url)
            }
        }

        // 进度回调走 webChromeClient
    }

    /** 提供给外部的 WebChromeClient 设置 */
    fun setChromeClient(client: android.webkit.WebChromeClient) {
        webChromeClient = client
    }

    /** 主线程读取 Cookie（供后台线程经 UiExecutor 调用） */
    fun getCookiesFor(url: String): String = CookieManager.getInstance().getCookie(url) ?: ""

    /** 判断是否已登录 x.com（有 auth_token 即视为已登录） */
    fun isLoggedIn(): Boolean {
        val cookies = getCookiesFor(Constants.HOME_URL)
        return cookies.contains("auth_token=") || cookies.contains("ct0=")
    }

    override fun destroy() {
        LogStore.log(LogCategory.WEBVIEW, "XWebView destroy")
        super.destroy()
    }
}
