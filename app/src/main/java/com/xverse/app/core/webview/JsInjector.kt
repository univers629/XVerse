package com.xverse.app.core.webview

import android.webkit.WebView
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import com.xverse.app.core.util.UiExecutor

/**
 * 脚本注入管理。
 *
 * 工程规范 #1：一律以**内联 IIFE 文本**注入，禁止 `new Function`/`eval` 包装
 * （x.com CSP 无 `unsafe-eval`）。
 * 工程规范 #4：含 URL/路径的注释一律行注释，禁块注释（规避块注释意外闭合）。
 *
 * 注入时机：
 *  - [addEarly]：`document_start` 拦截类脚本（CSS + Redux store 覆写），
 *    在 WebViewClient.onPageStarted 时执行，尽可能早于 React 挂载。
 *  - [addLate]：`document_idle` 增强类脚本（下载按钮、历史上报），
 *    在 onPageFinished 时执行。
 *
 * 注：WebView.evaluateJavascript 由内核直接执行，不受页面 CSP 的 script-src 限制；
 * 脚本内部再自行遵守 CSP（不用 new Function/eval）。
 */
class JsInjector(private val webView: WebView) {

    private val earlyScripts = mutableListOf<String>()
    private val lateScripts = mutableListOf<String>()

    /**
     * 扩展内容脚本提供者：每次整页加载时**重建** bundle（返回 null 表示无扩展）。
     * 重建而非预生成字符串，是为了让 GM 存储缓存（内联 GMCACHE）随每次落盘刷新——
     * 用户脚本 GM_setValue 后 reload，首帧同步读到的值必须是最新的。
     */
    private var extProvider: (() -> Pair<String, String>?)? = null

    /** 注册 document_start 拦截脚本 */
    fun addEarly(script: String) {
        if (script !in earlyScripts) earlyScripts += script
    }

    /** 注册 document_idle 增强脚本 */
    fun addLate(script: String) {
        if (script !in lateScripts) lateScripts += script
    }

    /** 清空全部已注册脚本（探针模式切换/重建时调用，下一整页加载即零注入） */
    fun clear() {
        earlyScripts.clear()
        lateScripts.clear()
        extProvider = null
    }

    /** 设置扩展注入提供者（early/late bundle）。每次整页加载调用一次，重建注入文本 */
    fun setExtensionScripts(provider: () -> Pair<String, String>?) {
        extProvider = provider
    }

    /** 页面开始加载：注入拦截脚本 */
    fun onPageStarted(url: String) {
        LogStore.log(LogCategory.FILTER, "注入 early 脚本 x${earlyScripts.size} → $url")
        earlyScripts.forEach { evaluate(wrapIife(it)) }
        val p = extProvider?.invoke()
        if (p != null) {
            LogStore.log(LogCategory.FILTER, "注入扩展 early bundle → $url")
            evaluate(p.first)
        }
    }

    /** 页面加载完成：注入增强脚本 */
    fun onPageFinished(url: String) {
        LogStore.log(LogCategory.FILTER, "注入 late 脚本 x${lateScripts.size} → $url")
        lateScripts.forEach { evaluate(wrapIife(it)) }
        val p = extProvider?.invoke()
        if (p != null) {
            LogStore.log(LogCategory.FILTER, "注入扩展 late bundle → $url")
            evaluate(p.second)
        }
        // 注入 JS 桥事件监听（让页面可接收原生推送）
        evaluate(BRIDGE_BOOTSTRAP)
    }

    /** 立即在已加载页面执行（evaluateJavascript 通道） */
    fun evaluate(script: String) {
        UiExecutor.post {
            try {
                webView.evaluateJavascript(script, null)
            } catch (e: Exception) {
                LogStore.error("evaluateJavascript 失败", e)
            }
        }
    }

    companion object {
        /** 将脚本包装为内联 IIFE 文本（工程规范 #1） */
        fun wrapIife(script: String): String = "(function(){\n'use strict';\n$script\n})();"

        /** 桥事件引导：在页面建立 __gmCallbacks 事件通道 */
        private val BRIDGE_BOOTSTRAP = """
            (function(){
              'use strict';
              window.__gmCallbacks = window.__gmCallbacks || {};
              window.__gmCallbacks._onEvent = window.__gmCallbacks._onEvent || function(e){};
            })();
        """.trimIndent()
    }
}
