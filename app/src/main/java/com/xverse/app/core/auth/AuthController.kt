package com.xverse.app.core.auth

import android.content.Context
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import com.xverse.app.core.util.Constants
import com.xverse.app.core.webview.XWebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SavedAccount(val username: String)

/**
 * 登录/登出收口：
 * - 登录：在 WebView 内打开 x.com 登录页（[Constants.LOGIN_URL]），
 *   Cookie 直接写入 WebView 自身的 Cookie 存储 —— 登录态天然保留，
 *   返回后 WebView 自动处于已登录。
 *   （注意：Android WebView 与 Chrome Custom Tab 的 Cookie 存储相互独立，
 *   Custom Tab 登录不会同步到 WebView。）
 * - 登出：清空 WebView 的 Cookie。
 * - 状态：主线程只以 auth_token 判断。ct0 是访客也会拥有的 CSRF Cookie，不能当登录态。
 *
 * 工程规范 #3：CookieManager 仅主线程访问。
 */
class AuthController(private val context: Context) {

    private val vault = AccountVault(context)
    private val _loggedIn = MutableStateFlow(isLoggedInNow())
    /** 登录态（登出/校验后刷新） */
    val loggedIn: StateFlow<Boolean> = _loggedIn
    private val _username = MutableStateFlow(if (_loggedIn.value) vault.activeUsername() else "")
    /** 当前 x.com 用户名（由已登录页面的导航栏读取；无页面数据时为空）。 */
    val username: StateFlow<String> = _username
    private val _accounts = MutableStateFlow(vault.usernames().map(::SavedAccount))
    /** 已保存的本地账户；会话 Cookie 使用 Android Keystore 加密存储。 */
    val accounts: StateFlow<List<SavedAccount>> = _accounts

    /** 刷新登录态并返回 */
    fun refresh(): Boolean {
        val ok = isLoggedInNow()
        _loggedIn.value = ok
        if (!ok) _username.value = ""
        return ok
    }

    /** 登出：清空 WebView Cookie */
    fun logout(webView: XWebView?) {
        LogStore.log(LogCategory.AUTH, "Logout: cleared cookies")
        _username.value.takeIf { it.isNotBlank() }?.let(vault::remove)
        CookieManagerReader.clearAll(webView)
        _loggedIn.value = false
        _username.value = ""
        refreshAccounts()
    }

    /** 当前是否已登录（主线程读取） */
    fun isLoggedIn(): Boolean = isLoggedInNow()

    fun setUsername(value: String) {
        val username = value.trim().removePrefix("@")
        if (username.isBlank()) return
        _username.value = username
        if (_loggedIn.value && vault.save(username, CookieManagerReader.cookiesFor(Constants.HOME_URL))) {
            refreshAccounts()
            LogStore.log(LogCategory.AUTH, "Saved account: @$username")
        }
    }

    /** 当前账户保留在本地列表中，清 Cookie 后进入网页登录其它账户。 */
    suspend fun prepareAddAccount(): Boolean {
        val cleared = CookieManagerReader.clearAllAwait()
        if (cleared) {
            _loggedIn.value = false
            _username.value = ""
        }
        return cleared
    }

    /** 切换到一个已保存账户的加密 Cookie 会话。 */
    suspend fun switchTo(username: String): Boolean {
        val cookieHeader = vault.session(username) ?: return false
        val restored = CookieManagerReader.replaceXCookies(cookieHeader)
        if (restored) {
            val account = username.trim().removePrefix("@")
            vault.setActive(account)
            _username.value = account
            _loggedIn.value = true
            LogStore.log(LogCategory.AUTH, "Switched account: @$account")
        }
        return restored
    }

    /** 移除本地保存的账户；移除当前账户时一并清空浏览器 Cookie。 */
    suspend fun removeAccount(username: String): Boolean {
        val account = username.trim().removePrefix("@")
        if (account.equals(_username.value, ignoreCase = true)) {
            if (!CookieManagerReader.clearAllAwait()) return false
            _loggedIn.value = false
            _username.value = ""
        }
        vault.remove(account)
        refreshAccounts()
        LogStore.log(LogCategory.AUTH, "Removed account: @$account")
        return true
    }

    private fun refreshAccounts() {
        _accounts.value = vault.usernames().map(::SavedAccount)
    }

    private fun isLoggedInNow(): Boolean {
        val cookies = CookieManagerReader.cookiesFor(Constants.HOME_URL)
        return cookies.contains("auth_token=")
    }
}
