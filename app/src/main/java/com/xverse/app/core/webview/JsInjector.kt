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

    /** 注册 document_start 拦截脚本 */
    fun addEarly(script: String) {
        if (script !in earlyScripts) earlyScripts += script
    }

    /** 注册 document_idle 增强脚本 */
    fun addLate(script: String) {
        if (script !in lateScripts) lateScripts += script
    }

    /** 页面开始加载：注入拦截脚本 */
    fun onPageStarted(url: String) {
        LogStore.log(LogCategory.FILTER, "注入 early 脚本 x${earlyScripts.size} → $url")
        earlyScripts.forEach { evaluate(wrapIife(it)) }
    }

    /** 页面加载完成：注入增强脚本 */
    fun onPageFinished(url: String) {
        LogStore.log(LogCategory.FILTER, "注入 late 脚本 x${lateScripts.size} → $url")
        lateScripts.forEach { evaluate(wrapIife(it)) }
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
