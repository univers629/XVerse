package com.xverse.app.core.auth

import android.content.Context
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import com.xverse.app.core.util.Constants
import com.xverse.app.core.webview.XWebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 登录/登出收口：
 * - 登录：在 WebView 内打开 x.com 登录页（[Constants.LOGIN_URL]），
 *   Cookie 直接写入 WebView 自身的 Cookie 存储 —— 登录态天然保留，
 *   返回后 WebView 自动处于已登录。
 *   （注意：Android WebView 与 Chrome Custom Tab 的 Cookie 存储相互独立，
 *   Custom Tab 登录不会同步到 WebView。）
 * - 登出：清空 WebView 的 Cookie。
 * - 状态：主线程读 Cookie 判断 auth_token / ct0 是否存在。
 *
 * 工程规范 #3：CookieManager 仅主线程访问。
 */
class AuthController(private val context: Context) {

    private val _loggedIn = MutableStateFlow(isLoggedInNow())
    /** 登录态（登出/校验后刷新） */
    val loggedIn: StateFlow<Boolean> = _loggedIn

    /** 刷新登录态并返回 */
    fun refresh(): Boolean {
        val ok = isLoggedInNow()
        _loggedIn.value = ok
        return ok
    }

    /** 登出：清空 WebView Cookie */
    fun logout(webView: XWebView?) {
        LogStore.log(LogCategory.AUTH, "登出：清空 Cookie")
        CookieManagerReader.clearAll(webView)
        _loggedIn.value = false
    }

    /** 当前是否已登录（主线程读取） */
    fun isLoggedIn(): Boolean = isLoggedInNow()

    private fun isLoggedInNow(): Boolean {
        val cookies = CookieManagerReader.cookiesFor(Constants.HOME_URL)
        return cookies.contains("auth_token=") || cookies.contains("ct0=")
    }
}
