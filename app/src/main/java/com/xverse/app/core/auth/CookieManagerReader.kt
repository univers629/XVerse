package com.xverse.app.core.auth

import android.webkit.CookieManager
import com.xverse.app.core.util.UiExecutor
import com.xverse.app.core.webview.XWebView

/**
 * CookieManager 主线程安全封装。
 * 工程规范 #3：CookieManager / Settings 仅主线程访问 —— 所有调用方必须
 * 经 UiExecutor 主线程回调，本类不自行切线程，仅集中读/清逻辑。
 */
object CookieManagerReader {

    /** 读取指定域名的 Cookie 串 */
    fun cookiesFor(url: String): String =
        CookieManager.getInstance().getCookie(url) ?: ""

    /** 后台线程（IO）读取 Cookie：经 UiExecutor 切主线程同步读，避免在 IO 线程直接访问 */
    suspend fun cookiesForFromBackground(url: String): String =
        UiExecutor.postAndWait { CookieManager.getInstance().getCookie(url) ?: "" } ?: ""

    /** 清空全部 Cookie（登出）；若持有 WebView 一并通知其刷新页面 */
    fun clearAll(webView: XWebView? = null) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        webView?.reload()
    }
}
