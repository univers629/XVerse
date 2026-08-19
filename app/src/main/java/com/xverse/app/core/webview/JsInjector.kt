package com.xverse.app.core.webview

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
    private val keyedEarlyScripts = linkedMapOf<String, String>()
    private val lateScripts = mutableListOf<String>()
    private val nativeDocumentStartSupported =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    private var documentStartHandler: ScriptHandler? = null

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

    /** 注册或替换可随设置变化的 document_start 脚本。 */
    fun setEarly(key: String, script: String) {
        keyedEarlyScripts[key] = script
    }

    /** 注册 document_idle 增强脚本 */
    fun addLate(script: String) {
        if (script !in lateScripts) lateScripts += script
    }

    /** 清空全部已注册脚本（探针模式切换/重建时调用，下一整页加载即零注入） */
    fun clear() {
        earlyScripts.clear()
        keyedEarlyScripts.clear()
        lateScripts.clear()
        extProvider = null
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        UiExecutor.post {
            documentStartHandler?.remove()
            documentStartHandler = null
        }
    }

    /**
     * 在整页导航前把 early 脚本注册到 Chromium 的 document-start 生命周期。
     * 支持时脚本会早于页面自己的 JavaScript 执行；不支持时仍由 onPageStarted 回退注入。
     * 扩展脚本继续按每次导航动态生成，避免 GM 存储缓存被固化在注册时快照中。
     */
    fun prepareForNavigation() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        UiExecutor.post {
            documentStartHandler?.remove()
            documentStartHandler = null
            val scripts = earlyScripts + keyedEarlyScripts.values
            if (scripts.isEmpty()) return@post
            val bundle = scripts.joinToString("\n") { wrapIife(it) }
            documentStartHandler = WebViewCompat.addDocumentStartJavaScript(
                webView,
                bundle,
                X_ORIGIN_RULES,
            )
            LogStore.log(LogCategory.FILTER, "WebView native document_start registered x${scripts.size}")
        }
    }

    /** 设置扩展注入提供者（early/late bundle）。每次整页加载调用一次，重建注入文本 */
    fun setExtensionScripts(provider: () -> Pair<String, String>?) {
        extProvider = provider
    }

    /** 页面开始加载：注入拦截脚本 */
    fun onPageStarted(url: String) {
        if (nativeDocumentStartSupported) {
            LogStore.log(LogCategory.FILTER, "Native document_start executed before page x${earlyScripts.size + keyedEarlyScripts.size} -> $url")
        } else {
            val scripts = earlyScripts + keyedEarlyScripts.values
            LogStore.log(LogCategory.FILTER, "WebView lacks native document_start, falling back to early injection x${scripts.size} -> $url")
            scripts.forEach { evaluate(wrapIife(it)) }
        }
        val p = extProvider?.invoke()
        if (p != null) {
            LogStore.log(LogCategory.FILTER, "Injected extension early bundle -> $url")
            evaluate(p.first)
        }
    }

    /** 页面加载完成：注入增强脚本 */
    fun onPageFinished(url: String) {
        LogStore.log(LogCategory.FILTER, "Injected late scripts x${lateScripts.size} -> $url")
        lateScripts.forEach { evaluate(wrapIife(it)) }
        val p = extProvider?.invoke()
        if (p != null) {
            LogStore.log(LogCategory.FILTER, "Injected extension late bundle -> $url")
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
                LogStore.error("evaluateJavascript failed", e)
            }
        }
    }

    companion object {
        private val X_ORIGIN_RULES = setOf(
            "https://x.com",
            "https://*.x.com",
            "https://twitter.com",
            "https://*.twitter.com",
        )

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
