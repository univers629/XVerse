package com.xverse.app.core.webview

import android.content.Context
import android.webkit.WebStorage
import android.webkit.WebView
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore

/**
 * 一次性站点状态迁移。
 *
 * 背景：0.7.0 及更早版本的 UA 带 `; wv`，x.com 据此下发不支持 XChat(E2EE) 的客户端，
 * 而这份响应连同站点状态被 WebView 缓存了下来。升级到修正 UA 的版本后浏览器仍拿缓存里的
 * 旧客户端渲染，所以**覆盖升级**（应用数据保留）的用户依旧看不到 E2EE PIN 页，
 * 只有卸载重装（数据被清空）才会恢复。
 *
 * 这里在修正 UA 的版本首次启动、首次加载页面前后各清一趟旧站点状态：
 *   第一趟（加载前，原生）：HTTP 缓存 + DOM storage；
 *   第二趟（首屏加载后，JS）：Service Worker / CacheStorage / IndexedDB，随后重载一次页面。
 * **Cookie 全程不碰**，因此用户不需要重新登录，也不需要卸载重装。
 */
object WebViewStateMigration {

    private const val PREFS_NAME = "xverse_webview_migration"
    private const val KEY_DONE_VERSION = "site_state_purged_version"

    /** 站点状态清理版本；UA 判定依据再有变化时递增即可。 */
    private const val TARGET_VERSION = 1

    /** 本进程内第二趟是否待执行 */
    @Volatile
    private var secondPassPending = false

    private fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_DONE_VERSION, 0) >= TARGET_VERSION

    private fun markDone(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DONE_VERSION, TARGET_VERSION)
            .apply()
    }

    /**
     * WebView 创建时调用（早于任何 loadUrl）：清 HTTP 缓存与 DOM storage。
     * 使用同步 API，保证首屏请求不会命中旧缓存。
     */
    fun beforeFirstLoad(context: Context, webView: WebView) {
        if (isDone(context)) return
        runCatching {
            // 清磁盘 + 内存资源缓存（API 37 起 clearCache 是 WebView 实例方法）
            webView.clearCache(true)
            WebStorage.getInstance().deleteAllData()
        }.onFailure {
            LogStore.log(LogCategory.WEBVIEW, "Site state purge failed: ${it.message}")
        }
        secondPassPending = true
        LogStore.log(
            LogCategory.WEBVIEW,
            "Site state purge: HTTP cache + DOM storage cleared (cookies kept)",
        )
    }

    /**
     * 首屏加载完成后调用：待迁移且当前页在 x.com 时注入第二趟脚本。
     * 调用方（XWebView）负责在稍后重载一次页面，让客户端干净重新拉取。
     *
     * @return true 表示本次触发了第二趟
     */
    fun afterFirstLoad(context: Context, webView: WebView, url: String): Boolean {
        if (!secondPassPending) return false
        if (!url.contains("x.com")) return false
        secondPassPending = false
        // 先落标记：重载后不再重复清理
        markDone(context)
        runCatching {
            webView.evaluateJavascript(SECOND_PASS_JS) { result ->
                LogStore.log(LogCategory.WEBVIEW, "Site state purge: sw/caches/indexedDB -> $result")
            }
        }.onFailure {
            LogStore.log(LogCategory.WEBVIEW, "Site state purge inject failed: ${it.message}")
        }
        return true
    }

    /**
     * 第二趟脚本：注销 Service Worker、清 CacheStorage、删 IndexedDB。
     * 保守起见全部包 try/catch，任一步失败都不影响其它步骤。
     */
    private val SECOND_PASS_JS = """
        (function () {
          var jobs = [];
          try {
            if (navigator.serviceWorker) {
              jobs.push(navigator.serviceWorker.getRegistrations().then(function (regs) {
                return Promise.all(regs.map(function (r) { return r.unregister(); }));
              }));
            }
          } catch (e) {}
          try {
            if (window.caches) {
              jobs.push(caches.keys().then(function (keys) {
                return Promise.all(keys.map(function (k) { return caches.delete(k); }));
              }));
            }
          } catch (e) {}
          try {
            if (window.indexedDB && indexedDB.databases) {
              jobs.push(indexedDB.databases().then(function (dbs) {
                return Promise.all((dbs || []).map(function (db) {
                  return new Promise(function (resolve) {
                    try {
                      var req = indexedDB.deleteDatabase(db.name);
                      req.onsuccess = req.onerror = req.onblocked = function () { resolve(); };
                    } catch (e) { resolve(); }
                  });
                }));
              }));
            }
          } catch (e) {}
          return Promise.all(jobs).then(
            function () { return 'ok'; },
            function () { return 'partial'; }
          );
        })()
    """.trimIndent()
}
