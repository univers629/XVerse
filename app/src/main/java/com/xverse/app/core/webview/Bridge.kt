package com.xverse.app.core.webview

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import com.xverse.app.core.util.UiExecutor
import org.json.JSONObject

/**
 * JS ↔ 原生通信桥。
 *
 * 工程规范 #2（JS 回调协议）：
 *  - JS 侧调用 `XVerseNative.call(type, payloadJson)`，原生处理后，
 *    用 `evaluateJavascript("window.__gmCallbacks.$cbId(resultJson)")` 回传。
 *  - 回调在 JS 侧注册为 `window.__gmCallbacks[cbId] = fn`，勿把 JS 函数作为 Kotlin 参数。
 *  - 通讯 JSON 协议 `{type, payload}`，原生侧 [Bridge] 单例分发。
 *
 * 回调 ID 由 JS 侧生成；原生回传时原样带回调 ID。
 */
class Bridge(private val webView: WebView) {

    private val handlers = LinkedHashMap<String, (JSONObject, (JSONObject) -> Unit) -> Unit>()

    /** 注册原生处理函数，type 唯一 */
    fun register(type: String, handler: (JSONObject, (JSONObject) -> Unit) -> Unit) {
        handlers[type] = handler
    }

    /** JS 注入点：向页面暴露桥对象 */
    fun expose() {
        // 主线程
        webView.addJavascriptInterface(this, "XVerseNative")
    }

    @JavascriptInterface
    fun call(type: String, payload: String) {
        val handler = handlers[type]
        if (handler == null) {
            LogStore.log(LogCategory.WEBVIEW, "Bridge 收到未注册 type: $type")
            return
        }
        val json = try {
            JSONObject(payload)
        } catch (e: Exception) {
            JSONObject()
        }
        // 从 payload 提取回调 ID，用于回传
        val cbId = json.optString("_cb")
        val reply: (JSONObject) -> Unit = { result ->
            postResult(cbId, result)
        }
        try {
            handler(json, reply)
        } catch (e: Exception) {
            LogStore.error("Bridge 处理 $type 异常", e)
            reply(JSONObject().put("ok", false).put("error", e.message))
        }
    }

    /** 主动向 JS 推送事件（无回调） */
    fun emit(type: String, payload: JSONObject) {
        val json = JSONObject().put("type", type).put("payload", payload)
        postJs("window.__gmCallbacks && window.__gmCallbacks._onEvent && " +
            "window.__gmCallbacks._onEvent(${json.toString()});")
    }

    private fun postResult(cbId: String, result: JSONObject) {
        if (cbId.isBlank()) return
        val json = result.toString()
        // JSON 字符串需转义为 JS 字符串字面量
        val escaped = json.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        postJs("window.__gmCallbacks && window.__gmCallbacks['$cbId'] && " +
            "window.__gmCallbacks['$cbId'](\"$escaped\");")
    }

    private fun postJs(script: String) {
        UiExecutor.post {
            webView.evaluateJavascript(script, null)
        }
    }
}
