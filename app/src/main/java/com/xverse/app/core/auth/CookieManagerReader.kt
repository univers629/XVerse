package com.xverse.app.core.auth

import android.webkit.CookieManager
import com.xverse.app.core.util.UiExecutor
import com.xverse.app.core.webview.XWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

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

    /**
     * 用保存的 x.com Cookie 替换当前会话。CookieManager 必须在主线程操作；逐条写入以兼容
     * getCookie() 返回的标准 Cookie header（name=value; name2=value2）格式。
     */
    suspend fun replaceXCookies(cookieHeader: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val manager = CookieManager.getInstance()
        if (!removeAllCookies(manager)) return@withContext false
        val cookies = cookieHeader.split(';')
            .map { it.trim() }
            .filter { it.contains('=') }
        if (cookies.isEmpty()) return@withContext false
        for (cookie in cookies) {
            if (!setCookie(manager, cookie)) return@withContext false
        }
        manager.flush()
        true
    }

    /** 清空当前会话并等待 WebView CookieManager 完成，供「登录其他账户」使用。 */
    suspend fun clearAllAwait(): Boolean = withContext(Dispatchers.Main.immediate) {
        val manager = CookieManager.getInstance()
        val cleared = removeAllCookies(manager)
        manager.flush()
        cleared
    }

    private suspend fun removeAllCookies(manager: CookieManager): Boolean = suspendCancellableCoroutine { cont ->
        manager.removeAllCookies { ok -> if (cont.isActive) cont.resume(ok) }
    }

    private suspend fun setCookie(manager: CookieManager, value: String): Boolean = suspendCancellableCoroutine { cont ->
        manager.setCookie("https://x.com", "$value; Path=/; Secure; SameSite=None") { ok ->
            if (cont.isActive) cont.resume(ok)
        }
    }
}
