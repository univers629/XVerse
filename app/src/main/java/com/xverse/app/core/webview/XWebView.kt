package com.xverse.app.core.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
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

    /** 返回手势是否应回退页面 */
    fun canGoBackOrExit(): Boolean = canGoBack()

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
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.userAgentString = Constants.CHROME_MOBILE_UA
        s.setSupportMultipleWindows(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
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

    /** 清空 Cookie（登出） */
    fun clearAllCookies() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    /** 下拉刷新：页面顶部无滚动时触发 reload */
    fun refreshIfAtTop() {
        post {
            // 页面滚动位置由 JS 上报；这里保守 reload
            reload()
        }
    }

    override fun destroy() {
        LogStore.log(LogCategory.WEBVIEW, "XWebView destroy")
        super.destroy()
    }
}
