package com.xverse.app.core.extensions

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.xverse.app.core.download.MediaItem
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import com.xverse.app.core.webview.Bridge
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * 扩展运行时：内容脚本打包注入 + chrome.* 兼容层 + 同源中继资源服务 + 配置页 WebView。
 *
 * 平台限制：WebView 无法运行后台脚本（MV3 service worker / MV2 background page），
 * v1 面向「内容脚本 + 样式 + 配置页 + storage」类扩展（如暗色注入类）。
 *
 * 工程规范：注入一律内联 IIFE 文本；含 URL/路径的注释一律行注释。
 *
 * 配置页时序：shim 必须同步先于页面脚本执行。做法是在 serveResource 把 shim
 * 内联为 <head> 首行 <script>，随 options.html 一起返回；若页面缺 <head> 则包一层
 * <head><script>…</script></head>。绝不能依赖 onPageStarted 异步注入——TM 这类
 * 扩展在页面脚本顶层同步捕获 chrome 快照，异步注入必触发 `ke.* is undefined` 竞态。
 */
class ExtensionRuntime(
    private val context: Context,
    private val repo: ExtensionRepo,
    /** GM_download 落盘通道（DownloadController.enqueueDownloadBytes） */
    private val downloadController: com.xverse.app.core.download.DownloadController,
) {

    /**
     * 已注册扩展存储 Bridge 的弱引用集合。
     *
     * 配置页和主页面分别运行在不同 WebView 中，storage.onChanged 必须经原生层广播，
     * 才能让配置页写入后主页面立即收到变更。弱引用避免已销毁 WebView 被运行时长期持有。
     */
    private val storageBridges: MutableSet<Bridge> = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Bridge, Boolean>())
    )

    /**
     * GM_download blob:/data: 分块传输会话表（key = fileName）。
     * 页面按 512KB 分块经 extDownloadChunk 桥到达，这里累积 base64 解码字节，
     * last=true 时组装落库。文件名即天然键（并发下载不同文件互不干扰）。
     */
    private val chunkSessions = java.util.concurrent.ConcurrentHashMap<String, ChunkSession>()
    private val chunkCleanupJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** GM.xmlHttpRequest 原生 HTTPS 通道：绕过网页 CORS，供用户自行导入的翻译脚本调用。 */
    private val userScriptHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 一次分块下载会话：累积 base64 分块 → 最后组装完整字节 */
    private class ChunkSession(
        val fileName: String,
        val url: String,
    ) {
        private val buf = java.io.ByteArrayOutputStream()
        private var total = 0L
        /** 最后活动时间戳（超时清理兜底：分块中断后不会常驻） */
        @Volatile var lastActive: Long = System.currentTimeMillis()

        @Synchronized
        fun append(chunk: String): Boolean {
            lastActive = System.currentTimeMillis()
            if (chunk.isNotEmpty()) {
                val bytes = runCatching {
                    android.util.Base64.decode(chunk, android.util.Base64.DEFAULT)
                }.getOrElse { return false }
                if (total + bytes.size > MAX_CHUNK_SESSION_BYTES) return false
                buf.write(bytes)
                total += bytes.size.toLong()
            }
            return true
        }

        @Synchronized
        fun toBytes(): ByteArray? {
            lastActive = System.currentTimeMillis()
            return try { buf.toByteArray() } catch (e: Exception) { null }
        }

        private companion object {
            const val MAX_CHUNK_SESSION_BYTES = 32L * 1024 * 1024
        }
    }

    /**
     * 配置页 WebView 资源基域：每个扩展一个子域（https://<extId>.appassets.androidplatform.net/）。
     * 扩展 ID 放在 host 而非路径段，与 Chrome 的 chrome-extension://<id>/ 一致——
     * 相对路径（含 ../../）解析时 . 不会逃出扩展作用域（../../ 在 host 边界被夹断）。
     */
    private fun assetBase(extId: String) = "https://$extId.appassets.androidplatform.net"

    /** 扩展解压根目录 */
    val extRoot: File
        get() = File(context.filesDir, "extensions").apply { mkdirs() }

    /** 与扩展目录解耦的设置页原生过滤规则。 */
    private val integratedFilterPackRoot: File
        get() = File(context.filesDir, "filter_packs").apply { mkdirs() }

    fun extDir(extId: String): File = File(extRoot, extId)

    /** 已从商店过滤扩展中剥离出的集成规则摘要（设置页只读轻量元数据）。 */
    fun filterPackSummaries(): List<ExtensionFilterPackStore.Summary> {
        ExtensionFilterPackStore.migrateLegacyPacks(extRoot, integratedFilterPackRoot)
        return ExtensionFilterPackStore.summaries(integratedFilterPackRoot)
    }

    /** 加载设置页集成规则；仅在广告过滤启用且对应子开关开启时调用。 */
    fun loadFilterPacks(disabledGroupKeys: Set<String> = emptySet()): List<ExtensionFilterPackStore.Pack> {
        ExtensionFilterPackStore.migrateLegacyPacks(extRoot, integratedFilterPackRoot)
        return ExtensionFilterPackStore.loadAll(integratedFilterPackRoot, disabledGroupKeys)
    }

    /** 读取扩展内文件文本（不存在返回 null） */
    fun readExtFile(extId: String, relPath: String): String? {
        val f = resolveExtFile(extId, relPath) ?: return null
        return try { f.readText() } catch (e: Exception) { null }
    }

    /** 校验并解析扩展内相对路径（拒绝穿越），返回 File */
    private fun resolveExtFile(extId: String, relPath: String): File? {
        if (!isValidId(extId)) return null
        val rel = sanitizeRel(relPath) ?: return null
        val f = File(extDir(extId), rel)
        return if (f.isFile) f else null
    }

    private fun isValidId(id: String): Boolean = Regex("^[0-9a-f]{32}$").matches(id)

    /** 相对路径安全化：拒绝 .. / 绝对路径；返回 null 表示非法 */
    private fun sanitizeRel(p: String): String? {
        var n = p.replace('\\', '/')
        if (n.startsWith("/") || n.startsWith("file:") || n.contains("://")) return null
        val parts = n.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** 解析后的消息 JSON（导入时存盘），供 shim i18n */
    fun messagesJson(extId: String): JSONObject? {
        val f = File(extDir(extId), "_xv_messages.json")
        return try { JSONObject(f.readText()) } catch (e: Exception) { null }
    }

    /**
     * 读取 @require 外部库拼接文本（require/ 目录，按 manifest.requires 顺序）。
     * 旧版导入（无 requires 字段）：脚本引用 JSZip 则注入内置资产 assets/extensions/jszip.min.js，
     * 保证「导入即用」，不依赖重装或设备网络（jsdelivr 部分地区不可达）。
     */
    private fun readRequireLibs(ext: ExtensionEntity): String {
        try {
            val manifest = JSONObject(File(extDir(ext.id), "manifest.json").readText())
            val arr = manifest.optJSONArray("requires")
            if (arr != null) {
                val out = StringBuilder()
                for (i in 0 until arr.length()) {
                    val rel = arr.optString(i)
                    if (rel.isBlank()) continue
                    val f = File(extDir(ext.id), rel)
                    if (f.isFile) out.append(f.readText()).append('\n')
                }
                return out.toString()
            }
        } catch (e: Exception) {
        }
        // 旧版导入（无 requires 清单）：脚本引用 JSZip → 注入内置资产
        val js = readExtFile(ext.id, "userscript.js") ?: return ""
        if (js.contains("JSZip") || js.contains("jszip")) {
            return try {
                context.assets.open("extensions/jszip.min.js")
                    .readBytes().toString(Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
        }
        return ""
    }

    /**
     * 对已导入的用户脚本做运行时补丁（纯文本替换，不解析执行）。
     * 目的是在脚本自身适配新版 X 之前，由我们代为兼容，保持「导入即用」：
     *
     * X-Vault 兼容补丁（2026-08）：
     *  X 的 HomeTimeline GraphQL 把用户信息从 result.legacy 迁移到 result.core 扁平对象，
     *  脚本还在读已不存在的 result.legacy → 博主名拿不到 → 首页 fallback 成 pathname /home → "home"。
     *  - ① core 解析同时兼容新旧结构（result.legacy 老 / result.core 新扁平）。
     *    新结构里 name/screen_name/profile_image_url_https 都是扁平字段，恰好与后续三行用法一致。
     *  - ② handle 兜底拦掉非博主路径段（home/explore/notifications/...），改从页面推文链接抓真 handle。
     */
    private fun patchUserScript(js: String): String {
        var out = js
        out = out.replace(
            "let core = tr.core?.user_results?.result?.legacy;",
            "let core = tr.core?.user_results?.result?.legacy || tr.core?.user_results?.result?.core;",
        )
        out = out.replace(
            "let handleFallback = core?.screen_name || window.location.pathname.split('/')[1] || 'unknown';",
            "let handleFallback = core?.screen_name || (function(){ try { var a=document.querySelector('a[href*=\"/status/\"]'); var m=a&&a.href.match(/(x|twitter)\\.com\\/([^\\/]+)/); if(m && ['home','explore','notifications','messages','compose'].indexOf(m[1])<0) return m[1]; } catch(e){} return undefined; })() || window.location.pathname.split('/')[1] || 'unknown';",
        )
        // WebView 兼容：blob→Image→canvas→toBlob 回调在 WebView 不触发（quality<1.0 画质压缩时卡死）。
        // 加 2.5s 超时兜底：超时直接 resolve 原始 rawBlob（放弃压缩但保证下载成功）。
        // 用单行锚点规避 CRLF 换行差异。
        out = out.replace(
            "canvas.toBlob((blob) => {",
            "let __xbdTimer = setTimeout(() => { try { URL.revokeObjectURL(imgUrl); } catch(e){} try { canvas.remove(); } catch(e){} resolve(rawBlob); }, 2500);\ncanvas.toBlob((blob) => {",
        )
        out = out.replace(
            "URL.revokeObjectURL(imgUrl); canvas.remove(); resolve(blob);",
            "if (__xbdTimer) clearTimeout(__xbdTimer); URL.revokeObjectURL(imgUrl); canvas.remove(); resolve(blob);",
        )
        return out
    }

    /**
     * 按扩展构建注入 bundle（内容脚本 + shim）。
     * @return (earlyJs, lateJs)：document_start 组与 document_idle/end 组
     */
    fun bundlesFor(ext: ExtensionEntity): Pair<String, String> {
        val m = messagesJson(ext.id)
        val manifestObj = JSONObject()
            .put("name", ext.name)
            .put("version", ext.version)
            .put("manifest_version", ext.manifestVersion)
        // 预读扩展存储：GM_getValue 等同步 API 需要启动即得值（异步桥赶不上脚本首帧）
        val gmCache = loadStorage(ext.id).toString()
        val shim = shimScript(ext.id, manifestObj.toString(), m?.toString() ?: "{}", gmCache)

        val earlyParts = mutableListOf(shim)
        val lateParts = mutableListOf(shim)
        // @require 外部库（如 JSZip）：置于 shim 后、用户脚本前，IIFE 包裹挂全局
        //（库内 `var JSZip` 在 IIFE 作用域，需显式挂 window.JSZip 供 loadJSZip() 全局检查）
        val requireJs = readRequireLibs(ext)
        if (requireJs.isNotBlank()) {
            val libIife = "(function(){\n'use strict';\n" + requireJs +
                "\nwindow.JSZip = typeof JSZip !== 'undefined' ? JSZip : (typeof unsafeWindow !== 'undefined' ? unsafeWindow.JSZip : undefined);\n})();"
            earlyParts.add(libIife)
            lateParts.add(libIife)
        }
        val specs = parseContentScripts(ext.contentScriptsJson)
        specs.forEach { spec ->
            // 宿主最小匹配：WebView 只加载 x.com，@match 不含 x.com（<all_urls>/x.com/* 等）的脚本不注入
            val ms = spec.matches
            if (ms.isNotEmpty() && ms.none { UserScriptParser.matchesXCom(it) }) return@forEach
            val group = if (spec.runAt == "document_start") earlyParts else lateParts
            if (spec.css.isNotEmpty()) {
                val cssTexts = spec.css.mapNotNull { readExtFile(ext.id, it) }
                if (cssTexts.isNotEmpty()) {
                    group.add(cssBundle(cssTexts))
                }
            }
            if (spec.js.isNotEmpty()) {
                // 用户脚本运行时补丁：兼容新版 X 数据结构的文本替换
                //（只对 X-Vault 的目标行生效，其他脚本不含这些字面量则原样通过）
                val jsTexts = spec.js.mapNotNull { rel ->
                    readExtFile(ext.id, rel)?.let { source ->
                        // 商店 CRX 必须原样运行，不能做按插件内容匹配的文本魔改。
                        // 历史兼容补丁只允许作用于明确标记为 USERSCRIPT 的油猴脚本。
                        if (ext.source == "USERSCRIPT") patchUserScript(source) else source
                    }
                }
                if (jsTexts.isNotEmpty()) {
                    group.add("(function(){\n'use strict';\n" + jsTexts.joinToString("\n") + "\n})();")
                }
            }
        }
        return (earlyParts.joinToString("\n") to lateParts.joinToString("\n"))
    }

    private fun parseContentScripts(json: String): List<ContentScriptSpec> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val e = arr.optJSONObject(i)
                ContentScriptSpec(
                    matches = e.optJSONArray("matches")?.toStringList() ?: emptyList(),
                    js = e.optJSONArray("js")?.toStringList() ?: emptyList(),
                    css = e.optJSONArray("css")?.toStringList() ?: emptyList(),
                    runAt = e.optString("run_at", "document_idle"),
                    allFrames = e.optBoolean("all_frames", false),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** CSS 文件 → 注入 <style>（不依赖 file:// 直读） */
    private fun cssBundle(cssTexts: List<String>): String {
        val joined = cssTexts.joinToString("\n")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return ("(function(){\n'use strict';\n" +
            "var s = document.createElement('style');\n" +
            "s.setAttribute('data-xv-ext','1');\n" +
            "s.textContent = \"$joined\";\n" +
            "(document.head || document.documentElement).appendChild(s);\n})();")
    }

    /**
     * chrome.* 兼容层（实用级）：内联到内容脚本之前，同 IIFE。
     *  - runtime.id / getURL / getManifest
     *  - runtime.onMessage / sendMessage → no-op 打日志
     *  - i18n.getMessage（内联消息 JSON）
     *  - storage.local.get/set/remove → Bridge 往返原生落盘
     *  - 合并进已有 window.chrome（保留 WebView 自带 chrome.webview），再挂 browser 别名
     */
    private fun shimScript(
        extId: String,
        manifestJson: String,
        messagesJson: String,
        gmCacheJson: String = "{}",
        assetUrlPrefix: String = "",
        isOpts: Boolean = false,
    ): String {
        val m = messagesJson.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c")
        val mm = manifestJson.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c")
        val gmc = gmCacheJson.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c")
        // assetUrlPrefix 非空时（配置页 WebViewAssetLoader 场景），runtime.getURL 返回
        // https://appassets... 标准 https 域（子资源可正常加载）；否则返回同源中继地址
        // https://<页面origin>/xv-ext/<id>/<path>。为什么不用自定义 scheme：
        // 主页面 x.com 的 CSP 白名单（script-src/connect-src）只含 https://x.com 等，
        // 没有自定义 scheme，扩展内容脚本用 import()/fetch() 动态加载
        // getURL 出的资源会被 CSP 拦（如沉浸式翻译 content_guard 加载 content_main.js）。
        // 同源地址在 CSP 'self' 白名单内、同源 fetch 无 CORS，XWebView 拦截 /xv-ext/ 前缀
        // 服务扩展目录，从而让动态模块加载真正可用。
        val getUrlExpr = if (assetUrlPrefix.isNotBlank()) {
            "function(p){ return '$assetUrlPrefix' + String(p || '').replace(/^\\//,''); }"
        } else {
            "function(p){ return (location.origin || 'https://x.com') + '/xv-ext/' + EXT_ID + '/' + String(p || '').replace(/^\\//,''); }"
        }
        return """
            (function(){
              'use strict';
              // 扩展运行时兼容层：chrome.* 的子集（无后台脚本能力，见 XVerse 文档）
              var EXT_ID = '$extId';
              var MANIFEST = JSON.parse("$mm");
              var MESSAGES = JSON.parse("$m");
              // 用户脚本 GM_* 存储缓存：启动即同步读（异步桥赶不上脚本首帧），写入时同步更新缓存并异步落盘
              var GMCACHE = JSON.parse("$gmc");
              // __gmCallbacks 可能早于 BRIDGE_BOOTSTRAP 注入，此处补建
              window.__gmCallbacks = window.__gmCallbacks || {};
              var storageChangeListeners = [];
              var gmValueListeners = {};
              var gmValueListenerSeq = 0;
              var previousNativeEventHandler = window.__gmCallbacks._onEvent;
              window.__gmCallbacks._onEvent = function(event) {
                if (typeof previousNativeEventHandler === 'function') {
                  try { previousNativeEventHandler(event); } catch(e) {}
                }
                if (!event || event.type !== 'extStorageChanged' || !event.payload || event.payload.extId !== EXT_ID) return;
                var changes = event.payload.changes || {};
                var areaName = event.payload.areaName || 'local';
                Object.keys(changes).forEach(function(key) {
                  var change = changes[key] || {};
                  if (Object.prototype.hasOwnProperty.call(change, 'newValue')) GMCACHE[key] = change.newValue;
                  else delete GMCACHE[key];
                  Object.keys(gmValueListeners).forEach(function(id) {
                    var item = gmValueListeners[id];
                    if (!item || item.key !== key) return;
                    try { item.listener(key, change.oldValue, change.newValue, true); }
                    catch(e) { console.error('[XV-EXT] GM value listener', e); }
                  });
                });
                storageChangeListeners.slice().forEach(function(listener) {
                  try { listener(changes, areaName); } catch(e) { console.error('[XV-EXT] storage.onChanged listener', e); }
                });
              };
              function extCall(type, payload, cb) {
                var cbId = cb ? ('_xve' + (window.__xvExtSeq = (window.__xvExtSeq || 0) + 1)) : '';
                if (cb && window.__gmCallbacks) {
                  window.__gmCallbacks[cbId] = function(res){ try { cb(JSON.parse(res)); } catch(e){} };
                }
                if (window.XVerseNative) {
                  var pl = payload || {};
                  if (cbId) pl._cb = cbId;
                  try { XVerseNative.call(type, JSON.stringify(pl)); } catch(e) {}
                } else if (cb) {
                  window.__gmCallbacks[cbId] && (window.__gmCallbacks[cbId] = null);
                }
              }
              function makeStorage() {
                function nextId() { return '_xve' + (window.__xvExtSeq = (window.__xvExtSeq || 0) + 1); }
                function storageChangedEvent() {
                  return {
                    addListener: function(listener) {
                      if (typeof listener === 'function' && storageChangeListeners.indexOf(listener) < 0) storageChangeListeners.push(listener);
                    },
                    removeListener: function(listener) {
                      var i = storageChangeListeners.indexOf(listener);
                      if (i >= 0) storageChangeListeners.splice(i, 1);
                    },
                    hasListener: function(listener) { return storageChangeListeners.indexOf(listener) >= 0; }
                  };
                }
                var onChanged = storageChangedEvent();
                var local = {
                  get: function(keys, cb) {
                    // Chrome 支持 get(callback)；此时第一个参数就是回调而不是 keys。
                    if (typeof keys === 'function') { cb = keys; keys = null; }
                    var defaults = (keys && !Array.isArray(keys) && typeof keys === 'object') ? keys : null;
                    var want = null;
                    if (typeof keys === 'string') want = [keys];
                    else if (Array.isArray(keys)) want = keys;
                    else if (keys && typeof keys === 'object') want = Object.keys(keys);
                    function withDefaults(data) {
                      if (!defaults) return data || {};
                      var out = {};
                      Object.keys(defaults).forEach(function(k){ out[k] = defaults[k]; });
                      Object.keys(data || {}).forEach(function(k){ out[k] = data[k]; });
                      return out;
                    }
                    // 无回调 → Promise 模式（现代扩展用 chrome.storage.local.get(...).then()）
                    var hasCb = typeof cb === 'function';
                    if (hasCb) { doGet(want, cb); return; }
                    if (!window.XVerseNative) { return Promise.resolve({}); }
                    return new Promise(function(resolve){
                      var id = nextId();
                      window.__gmCallbacks[id] = function(res){
                        window.__gmCallbacks[id] = null;
                        try { var o = JSON.parse(res); resolve(withDefaults(o && o.data ? o.data : {})); }
                        catch(e){ resolve({}); }
                      };
                      fireGet(want, id);
                    });
                    function doGet(wantKeys, cbFn) {
                      if (!window.XVerseNative) { cbFn({}); return; }
                      var id = nextId();
                      window.__gmCallbacks[id] = function(res){
                        window.__gmCallbacks[id] = null;
                        try { var o = JSON.parse(res); cbFn(withDefaults(o && o.data ? o.data : {})); } catch(e){ cbFn(withDefaults({})); }
                      };
                      fireGet(wantKeys, id);
                    }
                    function fireGet(wantKeys, id) {
                      if (!window.XVerseNative) return;
                      var pl = {extId: EXT_ID, _cb: id};
                      if (wantKeys) pl.keys = wantKeys;
                      try { XVerseNative.call('extStorageGet', JSON.stringify(pl)); } catch(e) {}
                    }
                  },
                  set: function(items, cb) {
                    var hasCb = typeof cb === 'function';
                    if (hasCb) { fireSet(cb); return; }
                    return new Promise(function(resolve){
                      var id = nextId();
                      window.__gmCallbacks[id] = function(){ window.__gmCallbacks[id] = null; resolve(); };
                      fireSet(null, id);
                    });
                    function fireSet(cbFn, id) {
                      if (!window.XVerseNative) { cbFn && cbFn(); return; }
                      var pl = {extId: EXT_ID, items: items};
                      if (id) pl._cb = id;
                      else {
                        var cid = nextId();
                        pl._cb = cid;
                        window.__gmCallbacks[cid] = function(){ window.__gmCallbacks[cid] = null; cbFn(); };
                      }
                      try { XVerseNative.call('extStorageSet', JSON.stringify(pl)); } catch(e) { cbFn && cbFn(); }
                    }
                  },
                  remove: function(keys, cb) {
                    var hasCb = typeof cb === 'function';
                    if (hasCb) { fireRemove(cb); return; }
                    return new Promise(function(resolve){
                      var id = nextId();
                      window.__gmCallbacks[id] = function(){ window.__gmCallbacks[id] = null; resolve(); };
                      fireRemove(null, id);
                    });
                    function fireRemove(cbFn, id) {
                      if (!window.XVerseNative) { cbFn && cbFn(); return; }
                      var pl = {extId: EXT_ID, keys: (typeof keys === 'string' ? [keys] : keys)};
                      if (id) pl._cb = id;
                      else {
                        var cid = nextId();
                        pl._cb = cid;
                        window.__gmCallbacks[cid] = function(){ window.__gmCallbacks[cid] = null; cbFn(); };
                      }
                      try { XVerseNative.call('extStorageRemove', JSON.stringify(pl)); } catch(e) { cbFn && cbFn(); }
                    }
                  }
                };
                // 新版 Chrome 同时提供 storage.onChanged 与 StorageArea.onChanged。
                local.onChanged = onChanged;
                // chrome.storage.local（标准）；sync/managed 也落到同一份本地存储
                return { local: local, sync: local, managed: local, onChanged: onChanged };
              }
              function makeGmApi() {
                // GM_setValue 落盘走 Bridge（原生 extStorageSet 需 _cb 回执，fire-and-forget 也发一个）
                function persist() {
                  try {
                    var cbId = '_xve' + (window.__xvExtSeq = (window.__xvExtSeq || 0) + 1);
                    window.__gmCallbacks[cbId] = function(){ window.__gmCallbacks[cbId] = null; };
                    var pl = {extId: EXT_ID, items: GMCACHE, _cb: cbId};
                    XVerseNative.call('extStorageSet', JSON.stringify(pl));
                  } catch(e) {}
                }
                // GM.xmlHttpRequest 统一走原生 HTTPS 通道，绕过页面 CORS。
                // 同时兼容回调式 GM_xmlhttpRequest 与 Promise 式 GM.xmlHttpRequest。
                function gmXhr(details) {
                  details = details || {};
                  var aborted = false;
                  var rejectPromise;
                  var promise = new Promise(function(resolve, reject) {
                    rejectPromise = reject;
                    var data = details.data;
                    if (data != null && typeof data !== 'string') {
                      try { data = JSON.stringify(data); } catch(e) { data = String(data); }
                    }
                    extCall('extHttp', {
                      extId: EXT_ID,
                      url: String(details.url || ''),
                      method: String(details.method || 'GET'),
                      headers: details.headers || {},
                      data: data == null ? '' : data,
                      responseType: String(details.responseType || 'text')
                    }, function(nativeResult) {
                      if (aborted) return;
                      var body = nativeResult && typeof nativeResult.body === 'string' ? nativeResult.body : '';
                      var response = body;
                      if (details.responseType === 'json') {
                        try { response = JSON.parse(body); } catch(e) { response = null; }
                      }
                      var r = {
                        readyState: 4,
                        status: Number(nativeResult && nativeResult.status || 0),
                        statusText: String(nativeResult && nativeResult.statusText || ''),
                        responseHeaders: String(nativeResult && nativeResult.responseHeaders || ''),
                        responseText: body,
                        response: response,
                        finalUrl: String(nativeResult && nativeResult.finalUrl || details.url || '')
                      };
                      if (nativeResult && nativeResult.ok) {
                        if (typeof details.onload === 'function') { try { details.onload(r); } catch(e){} }
                        resolve(r);
                      } else {
                        r.error = String(nativeResult && nativeResult.error || 'network error');
                        if (typeof details.onerror === 'function') { try { details.onerror(r); } catch(e){} }
                        reject(r);
                      }
                    });
                  });
                  promise.abort = function() {
                    aborted = true;
                    if (rejectPromise) { try { rejectPromise({ error: 'aborted' }); } catch(e){} }
                    if (typeof details.onabort === 'function') { try { details.onabort(); } catch(e){} }
                  };
                  return promise;
                }
                var api = {
                  info: {
                    script: { name: MANIFEST.name || 'Unnamed', version: MANIFEST.version || '0.0' },
                    scriptMetaStr: '',
                    scriptHandler: 'XVerse',
                    scriptVersion: MANIFEST.version || '0.0',
                    // 完整源码不回传（内存与日志压力），保持缺省
                    scriptSource: '',
                    scriptUpdateURL: '',
                    scriptWillUpdate: false,
                    version: '0.1.0'
                  },
                  // GM_download：统一走原生下载，绕开 X-Vault 的 fetch 拦截器。
                  //  - http/https 直链 → 原生 OkHttp 下载（extDownloadUrl），页面零 Blob 转换
                  //  - blob:/data: URL（X-Vault 图片/ZIP 用 URL.createObjectURL）→ 页面 XHR 读 Blob，
                  //    base64 分块经桥（extDownloadChunk）传原生组装写公共目录，绕 Binder 1MB 限
                  //    注意：被 X-Vault 覆盖过的 window.fetch 走它自己的管线，这里必须用 XMLHttpRequest
                  download: function(details) {
                    function fail(err) {
                      if (details && details.onerror) { try { details.onerror(err || {}); } catch(e){} }
                      else if (typeof details === 'string') {
                        // details 是 URL 字符串（某些脚本直接传 url）
                        details = { url: details };
                        try { GM_download(details); } catch(e) {}
                      }
                    }
                    function isHttp(u) {
                      return typeof u === 'string' && (u.indexOf('http://') === 0 || u.indexOf('https://') === 0);
                    }
                    function sendChunks(blob, fileName, cb, done) {
                      // base64 分块：每块 512KB，经 extDownloadChunk 桥传原生，绕 Binder 1MB 限
                      var CH = 512 * 1024;
                      var i = 0;
                      function next() {
                        var slice = blob.slice(i, Math.min(i + CH, blob.size));
                        var fr = new FileReader();
                        fr.onload = function() {
                          var b64 = String(fr.result).split(',')[1] || '';
                          i += slice.size;
                          var isLast = i >= blob.size;
                          try {
                            XVerseNative.call('extDownloadChunk', JSON.stringify({
                              fileName: fileName,
                              url: (details && details.url) || '',
                              extId: EXT_ID,
                              index: cb,
                              chunk: b64,
                              total: blob.size,
                              last: isLast
                            }));
                          } catch(e) { fail(); return; }
                          if (isLast) { if (done) done(); } else { next(); }
                        };
                        fr.onerror = function() { fail(); };
                        try { fr.readAsDataURL(slice); } catch(e) { fail(); }
                      }
                      next();
                    }
                    try {
                      if (!details) { fail(); return; }
                      if (typeof details === 'string') details = { url: details, saveAs: false };
                      var fileName = details.name || 'download';
                      // http/https 直链：原生 OkHttp 直接下载（页面零 Blob 转换，最快最稳）
                      if (isHttp(details.url)) {
                        try {
                          if (window.XVerseNative) {
                            XVerseNative.call('extDownloadUrl', JSON.stringify({
                              url: details.url,
                              fileName: fileName,
                              extId: EXT_ID
                            }));
                            if (details.onload) { try { details.onload(); } catch(e){} }
                            return;
                          }
                        } catch(e) { fail(); return; }
                        // 无原生桥：<a download> 兜底
                        try {
                          var a = document.createElement('a');
                          a.href = details.url;
                          a.download = fileName;
                          document.body.appendChild(a); a.click(); document.body.removeChild(a);
                        } catch(e) {}
                        return;
                      }
                      // blob:/data: URL：页面读 Blob → 分块经桥
                      var x = new XMLHttpRequest();
                      x.open('GET', details.url, true);
                      x.responseType = 'blob';
                      x.onload = function() {
                        var ok = x.response && (x.status >= 200 && x.status < 300 || x.status === 0);
                        if (ok && window.XVerseNative) {
                          sendChunks(x.response, fileName, 0, details.onload || null);
                        } else if (ok && !window.XVerseNative) {
                          // 无原生桥：<a download> 兜底
                          try {
                            var url = URL.createObjectURL(x.response);
                            var a2 = document.createElement('a');
                            a2.href = url; a2.download = fileName;
                            document.body.appendChild(a2); a2.click(); document.body.removeChild(a2);
                            setTimeout(function(){ try { URL.revokeObjectURL(url); } catch(e){} }, 60000);
                            if (details.onload) { try { details.onload(); } catch(e){} }
                          } catch(e) { fail(); }
                        } else {
                          fail({ status: x.status });
                        }
                      };
                      x.onerror = function() { fail(); };
                      try { x.send(); } catch(e) { fail(); }
                    } catch(e) { fail(); }
                  },
                  // 无 saveAs 的 GM_download 走 <a download>：交给 WebView 下载监听（工程规范 #4 行注释）
                  // 有 saveAs 的走原生 extDownload 桥：ZIP 包/需要指定文件名时用
                  //（原生侧受限：BLOB base64 上限 8MB，图片/短视频可直传，大视频交给 WebView 下载监听）
                  // GM_download 挂到 window：X-Vault 顶层 / 其它用户脚本可能直接调用全局
                  getValue: function(key, def) { return Object.prototype.hasOwnProperty.call(GMCACHE, key) ? GMCACHE[key] : def; },
                  setValue: function(key, value) { GMCACHE[key] = value; persist(); },
                  deleteValue: function(key) { delete GMCACHE[key]; persist(); },
                  listValues: function() { return Object.keys(GMCACHE); },
                  addValueChangeListener: function(key, listener) {
                    var id = ++gmValueListenerSeq;
                    gmValueListeners[id] = { key: String(key), listener: listener };
                    return id;
                  },
                  removeValueChangeListener: function(id) { delete gmValueListeners[id]; },
                  addElement: function(parent, tagName, attrs) {
                    if (typeof parent === 'string') { attrs = tagName; tagName = parent; parent = null; }
                    var el = document.createElement(String(tagName || 'div'));
                    var values = attrs || {};
                    Object.keys(values).forEach(function(key) {
                      var value = values[key];
                      if (key === 'textContent') el.textContent = String(value);
                      else if (key === 'innerHTML') el.innerHTML = String(value);
                      else if (key === 'class') el.className = String(value);
                      else if (key === 'style' && value && typeof value === 'object') Object.assign(el.style, value);
                      else try { el.setAttribute(key, String(value)); } catch(e) {}
                    });
                    (parent && parent.appendChild ? parent : (document.body || document.documentElement)).appendChild(el);
                    return el;
                  },
                  addStyle: function(css) {
                    var s = document.createElement('style');
                    s.setAttribute('data-xv-gm', '1');
                    if (typeof css === 'string') s.textContent = css;
                    (document.head || document.documentElement).appendChild(s);
                    return s;
                  },
                  log: function() {
                    var parts = Array.prototype.slice.call(arguments);
                    parts.unshift('[GM]');
                    if (typeof console !== 'undefined' && console.log) console.log.apply(console, parts);
                  },
                  notification: function(details) {
                    if (details && typeof details.text !== 'undefined') console.log('[GM] notification: ' + details.text);
                  },
                  registerMenuCommand: function(){},
                  unregisterMenuCommand: function(){},
                  openInTab: function(url) {
                    try { window.open(String(url || ''), '_blank'); } catch(e) { location.href = String(url || ''); }
                    return { close: function(){}, closed: false, onclose: null };
                  },
                  setClipboard: function(text) {
                    try {
                      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text);
                    } catch(e) {}
                  },
                  xmlhttpRequest: gmXhr,
                  xmlHttpRequest: gmXhr
                };
                // 同时暴露到 window（无 chrome.* 命名空间时脚本直接调用 GM_*）
                window.GM_info = api.info;
                window.GM_getValue = api.getValue;
                window.GM_setValue = api.setValue;
                window.GM_deleteValue = api.deleteValue;
                window.GM_listValues = api.listValues;
                window.GM_addValueChangeListener = api.addValueChangeListener;
                window.GM_removeValueChangeListener = api.removeValueChangeListener;
                window.GM_addElement = api.addElement;
                window.GM_addStyle = api.addStyle;
                window.GM_log = api.log;
                window.GM_notification = api.notification;
                window.GM_registerMenuCommand = api.registerMenuCommand;
                window.GM_unregisterMenuCommand = api.unregisterMenuCommand;
                window.GM_openInTab = api.openInTab;
                window.GM_setClipboard = api.setClipboard;
                window.GM_xmlhttpRequest = api.xmlHttpRequest;
                window.GM_download = api.download;
                return api;
              }
              // 用户脚本 GM_* shim：unsafeWindow 直通页面 window（顶层引用 origin fetch 等的脚本依赖）
              window.unsafeWindow = window;
              function makeNoop(names) {
                var o = {};
                names.forEach(function(n){ o[n] = function(){ console.log('[XV-EXT] chrome.' + n + ' 未实现（XVerse 无后台脚本能力）'); }; });
                return o;
              }
              function makeEvent() { return { addListener: function(){}, removeListener: function(){}, hasListener: function(){ return false; } }; }
              var runtimeApi = {
                id: EXT_ID,
                getURL: $getUrlExpr,
                getManifest: function(){
                  // Chrome 原生 getManifest() 返回已解析 __MSG_*__ 的字段；对齐该行为
                  var out = {};
                  for (var k in MANIFEST) {
                    if (!MANIFEST.hasOwnProperty(k)) continue;
                    var v = MANIFEST[k];
                    if (typeof v === 'string') {
                      var m = /^__MSG_([^_]+)__$/.exec(v);
                      out[k] = m ? (MESSAGES[m[1]] ? MESSAGES[m[1]].message : v) : v;
                    } else {
                      out[k] = v;
                    }
                  }
                  return out;
                },
                getPlatformInfo: function(cb){ cb && cb({ os: 'android', arch: 'arm', nacl_arch: 'arm' }); },
                getContexts: function(opts, cb){ cb && cb([]); },
                setUninstallURL: function(){},
                lastError: undefined,
                onMessage: makeEvent(),
                onMessageExternal: makeEvent(),
                onInstalled: makeEvent(),
                onUpdateAvailable: makeEvent(),
                onConnect: makeEvent(),
                onConnectExternal: makeEvent(),
                onUserScriptConnect: makeEvent(),
                onUserScriptMessage: makeEvent(),
                // 后台依赖信号：页面调 runtime.sendMessage 说明它向后台 service worker 要数据。
                // WebView 无法运行 MV3 后台脚本，配置页要么白屏要么只能静态渲染。
                // 置 window.__xvNeedsBg，shim 自带的边界守护据此注入「配置页暂不可用」提示。
                sendMessage: function(msg, opts, cb){
                  window.__xvNeedsBg = true;
                  console.log('[XV-EXT] runtime.sendMessage 未实现（配置依赖后台 service worker，WebView 不支持）');
                  if (typeof opts === 'function') opts({}); else if (typeof cb === 'function') cb({});
                },
                connect: function(){
                  var port = {
                    name: '',
                    sender: null,
                    postMessage: function(){ console.log('[XV-EXT] port.postMessage 未实现'); },
                    disconnect: function(){},
                    onMessage: makeEvent(),
                    onDisconnect: makeEvent()
                  };
                  return port;
                }
              };
              var storageApi = makeStorage();
              var i18nApi = {
                getMessage: function(key) {
                  var msg = (MESSAGES && MESSAGES[key]) ? MESSAGES[key].message : null;
                  if (msg === null) return '';
                  // 支持简单 $1 占位替换
                  for (var i = 1; i < arguments.length; i++) {
                    msg = msg.split('$' + i).join(arguments[i]);
                  }
                  return msg;
                },
                getUILanguage: function(){ return navigator.language || 'en'; },
                // 双模式：回调（Chrome 旧式）与 Promise（现代扩展 await 式）都支持
                getAcceptLanguages: function(cb) {
                  var langs = [navigator.language || 'en'];
                  if (typeof cb === 'function') { cb(langs); return; }
                  return Promise.resolve(langs);
                }
              };
              var tabsApi = {
                query: function(q, cb){ cb && cb([]); },
                get: function(id, cb){ cb && cb(null); },
                getCurrent: function(cb){ cb && cb(null); },
                create: function(props, cb){ cb && cb(null); },
                update: function(){},
                remove: function(){},
                highlight: function(){},
                executeScript: function(){ console.log('[XV-EXT] tabs.executeScript 未实现'); },
                sendMessage: function(){},
                onUpdated: makeEvent(),
                onRemoved: makeEvent(),
                onActivated: makeEvent(),
                onReplaced: makeEvent()
              };
              var windowsApi = {
                getAll: function(opts, cb){ cb && cb([]); },
                get: function(id, cb){ cb && cb(null); },
                create: function(opts, cb){ cb && cb(null); },
                update: function(){}
              };
              var permissionsApi = {
                request: function(perms, cb){ cb && cb(false); },
                contains: function(perms, cb){ cb && cb(false); },
                getAll: function(cb){ cb && cb([]); },
                remove: function(perms, cb){ cb && cb(false); },
                onAdded: makeEvent(),
                onRemoved: makeEvent()
              };
              var extensionApi = {
                isAllowedFileSchemeAccess: function(cb){ cb && cb(true); },
                inIncognitoContext: false
              };
              var cookiesApi = {
                getAll: function(opts, cb){ cb && cb([]); },
                set: function(){ console.log('[XV-EXT] cookies.set 未实现'); },
                remove: function(){ console.log('[XV-EXT] cookies.remove 未实现'); }
              };
              var webNavigationApi = {
                onReferenceFragmentUpdated: makeEvent(),
                onHistoryStateUpdated: makeEvent(),
                onCommitted: makeEvent(),
                onBeforeNavigate: makeEvent(),
                onCompleted: makeEvent()
              };
              var base = {
                runtime: runtimeApi,
                storage: storageApi,
                i18n: i18nApi,
                tabs: tabsApi,
                windows: windowsApi,
                extension: extensionApi,
                cookies: cookiesApi,
                contextMenus: makeNoop(['create','remove','update']),
                commands: { onCommand: makeEvent() },
                webNavigation: webNavigationApi,
                action: makeNoop(['onClicked','setBadgeText','setTitle','setIcon']),
                browserAction: makeNoop(['onClicked','setBadgeText','setTitle','setIcon']),
                permissions: permissionsApi,
                notifications: makeNoop(['create']),
                webRequest: makeNoop(['onBeforeRequest']),
                declarativeNetRequest: makeNoop(['updateDynamicRules','updateSessionRules','getSessionRules']),
                gm: makeGmApi()
              };
              // 油猴现代 Promise API 使用全局 GM；旧式 GM_* 已由 makeGmApi 同步挂载。
              window.GM = base.gm;
              // 合并进已有 window.chrome（保留 WebView 自带 chrome.webview 等），否则新建
              if (window.chrome && typeof window.chrome === 'object') {
                for (var k in base) {
                  if (base.hasOwnProperty(k)) window.chrome[k] = base[k];
                }
                var wc = window.chrome;
                if (typeof wc.runtime !== 'object' || !wc.runtime) wc.runtime = runtimeApi;
                if (typeof wc.storage !== 'object' || !wc.storage) wc.storage = storageApi;
                if (typeof wc.i18n !== 'object' || !wc.i18n) wc.i18n = i18nApi;
                if (typeof wc.tabs !== 'object' || !wc.tabs) wc.tabs = tabsApi;
              } else {
                window.chrome = base;
              }
              // browser 别名（Firefox 风格 API 的扩展）
              if (!window.browser) {
                window.browser = window.chrome;
              } else if (window.browser && typeof window.browser === 'object') {
                window.browser.runtime = window.browser.runtime || runtimeApi;
                window.browser.storage = window.browser.storage || storageApi;
                window.browser.i18n = window.browser.i18n || i18nApi;
                window.browser.tabs = window.browser.tabs || tabsApi;
                window.browser.permissions = window.browser.permissions || permissionsApi;
                window.browser.extension = window.browser.extension || extensionApi;
              }
              // 配置页后台依赖边界守护（仅 options 页）：页面调过 runtime.sendMessage
              // （依赖后台 service worker 取数据）且 body 迟迟未渲染出实质内容 → 判定白屏，
              // 注入「配置页暂不可用」覆盖层代替白屏。正常渲染出内容的页面不受打扰。
              // 原生侧轮询改为 shim 内自检：检测与注入同在一个 JS world，无时序竞态。
              if ($isOpts) {
                var __xvBoundaryInjected = false;
                function __xvMaybeInjectBoundary() {
                  if (__xvBoundaryInjected) return;
                  var needBg = !!window.__xvNeedsBg;
                  var bodyLen = document.body ? document.body.innerHTML.length : 0;
                  if (bodyLen >= 5000) return; // 已渲染出实质内容，不打扰
                  if (!needBg) return;
                  var d = document;
                  var box = d.createElement('div');
                  box.id = 'xv-options-boundary';
                  box.style.cssText = 'position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483647;background:#fff;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:32px;box-sizing:border-box;font-family:-apple-system,Roboto,sans-serif;text-align:center;';
                  var title = d.createElement('div');
                  title.textContent = '配置页暂不可用';
                  title.style.cssText = 'font-size:20px;font-weight:600;color:#111;margin-bottom:16px;';
                  var sub = d.createElement('div');
                  sub.textContent = '该扩展的配置页依赖后台服务（Service Worker），当前 WebView 环境无法运行后台脚本，因此无法展示设置界面。';
                  sub.style.cssText = 'font-size:15px;line-height:1.6;color:#555;max-width:420px;margin-bottom:24px;';
                  var note = d.createElement('div');
                  note.textContent = '扩展的过滤 / 翻译 / 注入等功能不受影响，可直接在 X 页面使用。';
                  note.style.cssText = 'font-size:13px;line-height:1.6;color:#888;max-width:420px;';
                  box.appendChild(title); box.appendChild(sub); box.appendChild(note);
                  (d.body || d.documentElement).appendChild(box);
                  __xvBoundaryInjected = true;
                }
                var __xvTryCount = 0;
                var __xvTick = setInterval(function(){
                  __xvTryCount++;
                  __xvMaybeInjectBoundary();
                  if (__xvBoundaryInjected || __xvTryCount > 15) clearInterval(__xvTick);
                }, 1000);
                if (document.readyState !== 'loading') setTimeout(__xvMaybeInjectBoundary, 0);
              }
            })();
        """.trimIndent()
    }

    /**
     * 扩展资源拦截（shouldInterceptUrl 用）：
     *  - /xv-ext/<id>/<path>：同源中继。主页面 x.com 的 CSP 白名单不含自定义 scheme，
     *    扩展内容脚本用 import()/fetch() 动态加载 getURL 出的资源会被拦；同源路径在 CSP
     *    'self' 白名单内且无 CORS，shim 的 runtime.getURL 改为返回 location.origin + 该前缀。
     * 路径校验：id 须为 32 位 hex、禁止穿越；mime 按扩展名映射。
     * HTML 文件会把 chrome.* shim 内联成 <head> 首行 <script>（配置页时序依赖）。
     * @return 可读文件（无则 null → WebView 报 404）
     */
    fun serveResource(url: String): WebResourceResponse? {
        val relPath: String = when {
            url.contains("/xv-ext/") -> url.substringAfter("/xv-ext/")
            else -> return null
        }
        val slash = relPath.indexOf('/')
        if (slash <= 0) return null
        val extId = relPath.substring(0, slash)
        if (!isValidId(extId)) return null
        val rel = sanitizeRel(relPath.substring(slash + 1)) ?: return null
        val f = File(extDir(extId), rel)
        if (!f.isFile || f.length() == 0L) return null
        val mime = mimeFor(rel)
        return try {
            val input = if (mime == "text/html") htmlWithShim(extId, f) else FileInputStream(f)
            // WebResourceResponse 的 inputStream 由内核消费后自动关闭；缺尺寸时传 -1
            WebResourceResponse(mime, "utf-8", input)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * options.html 响应：把 chrome shim 内联为 <head> 首行 <script>，保证 shim 同步先执行。
     * 真实 manifest 直接从扩展目录 manifest.json 读（serveResource 同步态，repo 查询是挂起函数用不了）。
     * 配置页文件大时重读一次可接受（仅 options 页，量小）。
     */
    private fun htmlWithShim(extId: String, f: File): InputStream {
        val raw = f.readText()
        val manifestObj = try {
            JSONObject(File(extDir(extId), "manifest.json").readText())
        } catch (e: Exception) {
            JSONObject()
        }
        val shim = shimScript(
            extId, manifestObj.toString(), messagesJson(extId)?.toString() ?: "{}",
            loadStorage(extId).toString(), assetUrlPrefix = "${assetBase(extId)}/", isOpts = true,
        )
        val scriptTag = "<script>\n$shim\n</script>"
        // 注入点选择 <head> 首行（head 缺失则包一层）
        return ByteArrayInputStream(
            if (raw.contains("<head", ignoreCase = true)) {
                val idx = raw.indexOf("<head", ignoreCase = true)
                val headEnd = raw.indexOf('>', idx)
                raw.substring(0, headEnd + 1) + "\n" + scriptTag + raw.substring(headEnd + 1)
            } else {
                "<html><head>" + scriptTag + "</head><body>" + raw + "</body></html>"
            }.toByteArray()
        )
    }

    private fun mimeFor(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "js" -> "application/javascript"
        "css" -> "text/css"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "svg" -> "image/svg+xml"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        else -> "application/octet-stream"
    }

    /**
     * 创建配置页 WebView（options 覆盖层用）：
     * JS 开 + 每扩展一子域（https://<extId>.appassets.androidplatform.net/）服务扩展目录
     * + extStorage 三件套 Bridge。
     *
     * 为什么用子域而非路径段：Chrome 的扩展资源域名是 chrome-extension://<id>/，扩展 ID 在
     * host。相对路径（含 ../../）解析时只能向上爬路径段，在 host 边界被夹断，永远逃不出扩展
     * 目录。若把 ID 放路径段（/extensions/<id>/...），AdGuard 的 options.html 里的
     * ../../assets/... 会解析成 /extensions/assets/...（ID 丢失）而 404 白屏——这正是上一版
     * 沉浸式能渲染、AdGuard 白屏的原因。子域方案把 ID 还原到 host，与 Chrome 语义一致，
     * 任意深度的 ../ 都正确解析回本扩展目录。
     *
     * 不用 WebViewAssetLoader：其 PathHandler 只收 path 不收子域，无法区分请求属于哪个扩展，
     * 故自定义 shouldInterceptRequest 解析 host 子域。appassets.androidplatform.net 是保留域
     * （公共 DNS 不可解析、WebView 优先交给 app 拦截），拦截它无歧义、不误伤真实站点。
     * chrome shim 由 serveAssetUrl 内联进 options.html，同步先于页面脚本执行（时序依赖）。
     *
     * 配置页边界：依赖后台 service worker 的扩展（配置页通过 runtime.sendMessage 向后台要
     * 数据，如 AdGuard）在 WebView 里拿不到数据，页面会白屏。shim 会在调用 sendMessage 时
     * 置 window.__xvNeedsBg；shim 内自检轮询：若标记被置且 body 未渲染出实质内容，注入
     * 「配置页暂不可用」覆盖层代替白屏，提示主要功能不受影响。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun newOptionsWebView(ctx: Context, ext: ExtensionEntity): WebView {
        val wv = WebView(ctx)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowFileAccess = false
        wv.setBackgroundColor(android.graphics.Color.WHITE)
        wv.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: android.webkit.WebResourceRequest,
            ): WebResourceResponse? {
                return serveAssetUrl(request.url)
            }
        }
        // 后台依赖边界判定：在 chrome shim 内自检（sendMessage 调用置 __xvNeedsBg，
        // 自检轮询检测到标记且 body 未渲染时注入「配置页暂不可用」覆盖层），
        // 检测与注入同在一个 JS world，无原生侧时序竞态。
        // extStorage 三件套（配置页专用，选项页与内容脚本共用同一存储）
        val bridge = Bridge(wv)
        registerStorageHandlers(bridge)
        bridge.expose()
        val page = ext.optionsPage.ifBlank { "index.html" }
        wv.loadUrl("${assetBase(ext.id)}/$page")
        return wv
    }

    /** appassets 保留域后缀：子域部分即扩展 ID */
    private val ASSET_HOST_SUFFIX = ".appassets.androidplatform.net"

    /**
     * 按 https://<extId>.appassets.androidplatform.net/<rel> 解析并服务扩展资源。
     * 子域即扩展 ID；host 非 appassets 保留域直接放行（不拦截真实站点）。
     * HTML 内联 chrome shim（配置页时序依赖）。
     */
    private fun serveAssetUrl(url: android.net.Uri): WebResourceResponse? {
        val host = url.host ?: return null
        if (!host.endsWith(ASSET_HOST_SUFFIX)) return null
        val extId = host.removeSuffix(ASSET_HOST_SUFFIX)
        if (!isValidId(extId)) return null
        val rel = sanitizeRel(url.path?.trimStart('/') ?: return null) ?: return null
        val f = File(extDir(extId), rel)
        if (!f.isFile || f.length() == 0L) return null
        val mime = mimeFor(rel)
        return try {
            val input = if (mime == "text/html") htmlWithShim(extId, f) else FileInputStream(f)
            WebResourceResponse(mime, "utf-8", input)
        } catch (e: Exception) {
            null
        }
    }

    /** 主页面 Bridge 注册扩展存储三件套（由 BrowserViewModel 调用，传主页面 bridge） */
    fun registerStorageHandlers(bridge: Bridge) {
        storageBridges.add(bridge)
        bridge.register("extStorageGet") { payload, reply ->
            val extId = payload.optString("extId")
            val keys = payload.optJSONArray("keys")
            val cbId = payload.optString("_cb")
            if (cbId.isBlank()) { reply(JSONObject().put("ok", false).put("error", "no cb")); return@register }
            val data = loadStorage(extId)
            val result = JSONObject()
            if (keys != null) {
                for (i in 0 until keys.length()) {
                    val k = keys.optString(i)
                    val v = data.opt(k)
                    if (v != null && v != JSONObject.NULL) result.put(k, v)
                }
            } else {
                data.keys().forEach { k -> result.put(k, data.get(k)) }
            }
            // 回调协议：window.__gmCallbacks[cbId]("json")
            reply(JSONObject().put("ok", true).put("data", result))
        }
        bridge.register("extStorageSet") { payload, reply ->
            val extId = payload.optString("extId")
            val items = payload.optJSONObject("items")
            val cbId = payload.optString("_cb")
            if (cbId.isBlank()) { reply(JSONObject().put("ok", false).put("error", "no cb")); return@register }
            val changes = JSONObject()
            if (items != null) {
                val data = loadStorage(extId)
                items.keys().forEach { k ->
                    val change = JSONObject()
                    if (data.has(k)) change.put("oldValue", data.get(k))
                    change.put("newValue", items.get(k))
                    changes.put(k, change)
                    data.put(k, items.get(k))
                }
                saveStorage(extId, data)
            }
            reply(JSONObject().put("ok", true))
            if (changes.length() > 0) broadcastStorageChanges(extId, changes)
        }
        bridge.register("extStorageRemove") { payload, reply ->
            val extId = payload.optString("extId")
            val keys = payload.optJSONArray("keys")
            val cbId = payload.optString("_cb")
            if (cbId.isBlank()) { reply(JSONObject().put("ok", false).put("error", "no cb")); return@register }
            val changes = JSONObject()
            if (keys != null) {
                val data = loadStorage(extId)
                for (i in 0 until keys.length()) {
                    val key = keys.optString(i)
                    if (data.has(key)) {
                        changes.put(key, JSONObject().put("oldValue", data.get(key)))
                        data.remove(key)
                    }
                }
                saveStorage(extId, data)
            }
            reply(JSONObject().put("ok", true))
            if (changes.length() > 0) broadcastStorageChanges(extId, changes)
        }
        // 用户脚本跨域请求：仅允许 HTTPS，异步 OkHttp 执行，避免阻塞 WebView 主线程。
        bridge.register("extHttp") { payload, reply ->
            val url = payload.optString("url")
            val method = payload.optString("method", "GET").uppercase()
            if (!url.startsWith("https://", ignoreCase = true)) {
                reply(JSONObject().put("ok", false).put("error", "only https is allowed"))
                return@register
            }
            if (method !in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")) {
                reply(JSONObject().put("ok", false).put("error", "unsupported method"))
                return@register
            }
            val headers = payload.optJSONObject("headers") ?: JSONObject()
            val data = payload.optString("data")
            val contentType = headers.optString("Content-Type")
                .ifBlank { headers.optString("content-type") }
                .ifBlank { "application/json; charset=utf-8" }
            val request = runCatching {
                val builder = Request.Builder().url(url)
                headers.keys().forEach { name ->
                    if (!name.equals("host", true) && !name.equals("content-length", true)) {
                        builder.header(name, headers.optString(name))
                    }
                }
                val body = if (method == "GET" || method == "HEAD") {
                    null
                } else {
                    data.toRequestBody(contentType.toMediaTypeOrNull())
                }
                builder.method(method, body).build()
            }.getOrElse { error ->
                reply(JSONObject().put("ok", false).put("error", error.message ?: "invalid request"))
                return@register
            }
            val requestHost = runCatching { request.url.host }.getOrDefault("unknown")
            userScriptHttpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    LogStore.log(LogCategory.FILTER, "UserScript network error: $method $requestHost (${e.message})")
                    reply(JSONObject().put("ok", false).put("error", e.message ?: "network error"))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        val body = runCatching { res.body.string() }.getOrElse { "" }
                        val responseHeaders = buildString {
                            res.headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                        }
                        reply(
                            JSONObject()
                                .put("ok", true)
                                .put("status", res.code)
                                .put("statusText", res.message)
                                .put("responseHeaders", responseHeaders)
                                .put("body", body)
                                .put("finalUrl", res.request.url.toString()),
                        )
                    }
                }
            })
        }
        // GM_download http/https 直链 → 原生 OkHttp 下载 → MediaStore 公共目录（相册可见）。
        // 新 shim 的 GM_download 该调用不带 _cb（fire-and-forget，onload 由 JS 侧同步触发），
        // 因此这里不要求 _cb，空则静默（postResult 本身对空 cbId 也是 no-op）。
        bridge.register("extDownloadUrl") { payload, reply ->
            val url = payload.optString("url")
            val fileName = payload.optString("fileName")
            if (url.isBlank() || fileName.isBlank()) {
                LogStore.log(LogCategory.FILTER, "extDownloadUrl missing params: url=$url name=$fileName")
                reply(JSONObject().put("ok", false).put("error", "missing url/name")); return@register
            }
            val media = extensionMediaItem(url, fileName)
            runtimeScope.launch {
                val ok = downloadController.enqueueDownloadUrl(url, fileName, media)
                reply(JSONObject().put("ok", ok).put("error", if (ok) "" else "enqueue failed"))
            }
        }
        // GM_download blob:/data: → 页面分块 base64 经桥（extDownloadChunk）累积组装，落 MediaStore。
        // 单会话限制 32 MB，避免异常脚本持续推送导致进程内存耗尽；大文件应使用 URL 流式通道。
        // 同样 fire-and-forget：不带 _cb，不要求回调。
        bridge.register("extDownloadChunk") { payload, reply ->
            val fileName = payload.optString("fileName")
            val url = payload.optString("url")
            val chunk = payload.optString("chunk")
            val isLast = payload.optBoolean("last")
            val index = payload.optInt("index", 0)
            if (fileName.isBlank()) {
                reply(JSONObject().put("ok", false).put("error", "missing fileName")); return@register
            }
            // 惰性清理：分块中断（页面刷新/脚本重载）后 session 不再收到分块，10 分钟无活动即回收，
            // 避免未收尾的下载会话常驻内存
            val session = chunkSessions.getOrPut(fileName) {
                ChunkSession(fileName, url).also { created -> scheduleChunkSessionCleanup(fileName, created) }
            }
            if (!session.append(chunk)) {
                chunkSessions.remove(fileName, session)
                chunkCleanupJobs.remove(fileName)?.cancel()
                reply(JSONObject().put("ok", false).put("error", "download data is invalid or exceeds 32 MB"))
                return@register
            }
            if (isLast) {
                chunkSessions.remove(fileName, session)
                chunkCleanupJobs.remove(fileName)?.cancel()
                runtimeScope.launch {
                    val bytes = session.toBytes()
                    if (bytes == null) {
                        reply(JSONObject().put("ok", false).put("error", "chunk assembly failed"))
                        return@launch
                    }
                    val media = extensionMediaItem(url, fileName)
                    val ok = downloadController.enqueueDownloadBytes(bytes, fileName, media)
                    reply(JSONObject().put("ok", ok).put("error", if (ok) "" else "enqueue failed"))
                }
            } else {
                reply(JSONObject().put("ok", true).put("index", index))
            }
        }
    }

    private fun scheduleChunkSessionCleanup(fileName: String, session: ChunkSession) {
        chunkCleanupJobs[fileName]?.cancel()
        chunkCleanupJobs[fileName] = runtimeScope.launch {
            while (true) {
                delay(CHUNK_SESSION_TTL_MS)
                if (System.currentTimeMillis() - session.lastActive < CHUNK_SESSION_TTL_MS) continue
                chunkSessions.remove(fileName, session)
                chunkCleanupJobs.remove(fileName)
                break
            }
        }
    }

    private fun extensionMediaItem(url: String, fileName: String): MediaItem {
        val extension = fileName.substringAfterLast('.', "bin").lowercase()
        val mediaType = when (extension) {
            "jpg", "jpeg", "png", "webp", "heic", "bmp", "avif" -> "photo"
            "gif" -> "gif"
            "mp4", "mov", "webm", "mkv", "avi", "3gp", "m4v" -> "video"
            else -> "file"
        }
        return MediaItem(
            url = url,
            extension = extension,
            mediaType = mediaType,
            fileName = fileName,
        )
    }

    private companion object {
        const val CHUNK_SESSION_TTL_MS = 10 * 60 * 1000L
    }

    /** 把配置页的 storage 写入同步广播给主页面及其他同扩展页面。 */
    private fun broadcastStorageChanges(extId: String, changes: JSONObject) {
        val payload = JSONObject()
            .put("extId", extId)
            .put("areaName", "local")
            .put("changes", changes)
        val bridges = synchronized(storageBridges) { storageBridges.toList() }
        bridges.forEach { it.emit("extStorageChanged", payload) }
    }

    private fun storageFile(extId: String): File {
        val d = extDir(extId)
        d.mkdirs()
        return File(d, "_xv_storage.json")
    }

    private fun loadStorage(extId: String): JSONObject {
        return try {
            JSONObject(storageFile(extId).readText())
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun saveStorage(extId: String, data: JSONObject) {
        try {
            storageFile(extId).writeText(data.toString())
            LogStore.log(LogCategory.FILTER, "Extension storage written: $extId")
        } catch (e: Exception) {
            LogStore.error("Failed to write extension storage", e)
        }
    }

    /** 卸载时只清理扩展目录；已剥离到设置页的集成规则保持不变。 */
    fun deleteExtensionData(extId: String) {
        try {
            val d = extDir(extId)
            if (d.isDirectory) d.deleteRecursively()
            LogStore.log(LogCategory.FILTER, "Extension data cleared: $extId")
        } catch (e: Exception) {
            LogStore.error("Failed to clear extension data", e)
        }
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
}
