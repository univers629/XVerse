package com.xverse.app.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xverse.app.AppInstance
import com.xverse.app.core.data.db.HistoryRecord
import com.xverse.app.core.data.repo.HistoryRepo
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import com.xverse.app.core.util.Constants
import com.xverse.app.core.webview.XWebView
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 浏览器页 ViewModel：持有 XWebView 引用，管理历史写入、注入、登录状态。
 */
class BrowserViewModel : ViewModel() {

    var webView: XWebView? = null

    /** WebView 就绪前收到的 URL（冷启动深链等）挂起，就绪后加载 */
    private var pendingUrl: String? = null

    val progress = MutableStateFlow(0)
    val title = MutableStateFlow("XVerse")
    val url = MutableStateFlow("")
    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn

    private var historyJob: Job? = null

    val locator get() = AppInstance.locator

    /** WebView 就绪时由 UI 调用 */
    fun onWebViewReady(view: XWebView) {
        webView = view
        setupBridge(view)
    }

    /** 首次加载：有挂起 URL 则加载之，否则回首页 */
    fun loadInitial() {
        viewModelScope.launch {
            val wv = webView ?: return@launch
            // 过滤组件初始注册须在首导航前完成，否则内置 strip/CSS 对首屏 SPA 无效
            val ready = loadFilterScripts(wv)
            val pending = pendingUrl
            pendingUrl = null
            wv.loadUrl(pending ?: Constants.HOME_URL)
        }
    }

    /** 加载 URL（WebView 未就绪则挂起待命） */
    fun loadUrl(url: String) {
        val wv = webView
        if (wv != null) wv.loadUrl(url) else pendingUrl = url
    }

    private fun setupBridge(view: XWebView) {
        // 注入历史上报脚本
        view.injector.addLate(HISTORY_TRACKING_SCRIPT)
        // 注入下载按钮（M3）+ 原生侧 GraphQL 拦截（X-Vault 思路）
        view.injector.addLate(DOWNLOAD_BUTTON_SCRIPT)
        view.injector.addEarly(GRAPHQL_HOOK_SCRIPT)

        // 过滤脚本（M2）：early 三层防御。
        // 初始注册由 loadInitial 在首导航前完成（必须早于 onPageStarted，否则首屏 SPA 不生效）；
        // 这里只挂规则变更热更新。
        watchFilterRules()

        view.onProgress = { progress.value = it }
        view.onPageFinished = { pageUrl ->
            this.url.value = pageUrl
            refreshLoginState()
            // 推文 URL：启动 3s 停留计时
            if (pageUrl.contains("/status/")) {
                scheduleHistoryWrite(pageUrl)
            }
        }
        view.onTitle = { title.value = it }

        // Bridge：接收 JS 上报
        val bridge = com.xverse.app.core.webview.Bridge(view)
        bridge.register("recordHistory") { payload, _ ->
            val url = payload.optString("url")
            val text = payload.optString("text")
            val displayName = payload.optString("name")
            val mediaUrl = payload.optString("mediaUrl")
            if (url.isNotEmpty()) writeHistory(url, text, displayName, mediaUrl)
        }
        // Bridge：GraphQL TweetDetail 响应 → 原生缓存媒体（按推文 id 隔离，避免串帖）
        bridge.register("mediaResponse") { payload, _ ->
            val json = payload.optString("data")
            val tweetId = payload.optString("tweetId")
            if (json.isNotEmpty()) {
                viewModelScope.launch {
                    locator.mediaParser.cacheFromGraphQL(tweetId, json)
                }
            }
        }
        bridge.register("getState") { payload, reply ->
            reply(org.json.JSONObject().put("ok", true).put("loggedIn", loggedIn.value))
        }
        // M3 下载：解析推文媒体（网络请求，异步回传）
        bridge.register("parseTweet") { payload, reply ->
            val url = payload.optString("url").ifBlank { this.url.value }
            viewModelScope.launch {
                val items = locator.downloadController.parseTweet(url)
                val arr = org.json.JSONArray()
                items.forEach {
                    arr.put(
                        org.json.JSONObject()
                            .put("url", it.url)
                            .put("quality", it.quality)
                            .put("size", it.size)
                            .put("extension", it.extension)
                            .put("fileName", it.fileName ?: "")
                            .put("thumbnailUrl", it.thumbnailUrl)
                    )
                }
                reply(org.json.JSONObject().put("ok", true).put("media", arr))
            }
        }
        // M3 下载：入队下载任务
        bridge.register("enqueueDownload") { payload, reply ->
            val tweetUrl = payload.optString("tweetUrl")
            val m = payload.optJSONObject("media")
            viewModelScope.launch {
                if (m != null) {
                    val item = com.xverse.app.core.download.MediaItem(
                        url = m.optString("url"),
                        quality = m.optString("quality"),
                        size = m.optLong("size"),
                        extension = m.optString("extension", "mp4"),
                        thumbnailUrl = m.optString("thumbnailUrl"),
                    )
                    val ok = locator.downloadController.enqueue(tweetUrl, item)
                    toast(if (ok) "已加入下载队列" else "下载入队失败")
                    reply(org.json.JSONObject().put("ok", ok))
                } else {
                    toast("下载入队失败：媒体数据缺失")
                    reply(org.json.JSONObject().put("ok", false).put("error", "media 缺失"))
                }
            }
        }
        bridge.expose()
    }

    /** 主线程 toast（桥接处理在 UiExecutor 主线程执行，可直接调用） */
    private fun toast(msg: String) {
        android.widget.Toast.makeText(locator.appContext, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * 加载过滤脚本：内置 + 用户规则。
     * [reInject]：规则变更时只重注入用户规则脚本（内置资产已在页面加载时注入，不重复）。
     * @return true=过滤就绪（含未启用）；false=读取失败（调用方仍继续，不阻塞导航）。
     */
    private suspend fun loadFilterScripts(view: XWebView, reInject: Boolean = false): Boolean {
        return try {
            val enabled = locator.settings.filterEnabled.first()
            if (!enabled) {
                LogStore.log(LogCategory.FILTER, "过滤已关闭，跳过注入")
                return true
            }
            val rules = locator.filterRepo.getEnabled()
            // FilterScript 需要 context；AppInstance.locator 内部持有 appContext
            val fs = com.xverse.app.core.webview.FilterScript(locator.appContext)
            if (reInject) {
                // 规则变更：直接执行用户规则 + CSS（幂等，会覆盖旧匹配结果）
                val userScript = fs.userRuleScript(rules)
                if (userScript.isNotBlank()) view.injector.evaluate(userScript)
                val userCss = fs.userCss(rules)
                if (userCss.isNotBlank()) view.injector.evaluate(userCss)
                LogStore.log(LogCategory.FILTER, "过滤规则已热更新：x${rules.size}")
                return true
            }
            fs.buildEarlyScripts(rules).forEach { view.injector.addEarly(it) }
            // Redux 拦截层会改写 React 内部状态，x.com 改版时易引发整页重渲染/反复刷新，
            // 仅当用户显式配置了 STORE 规则时才注入（默认关，CSS + Mutation 两层已够用）。
            val hasStoreRules = rules.any { it.type == com.xverse.app.core.data.db.RuleType.STORE }
            if (hasStoreRules) {
                fs.reduxScript()?.let { view.injector.addEarly(it) }
            } else {
                LogStore.log(LogCategory.FILTER, "无 STORE 规则，跳过 Redux 拦截层")
            }
            LogStore.log(LogCategory.FILTER, "过滤脚本就绪：内置 + 用户规则 x${rules.size}")
            true
        } catch (e: Exception) {
            LogStore.error("加载过滤脚本失败", e)
            false
        }
    }

    /** 过滤规则变化（增删/开关）→ 热更新已加载页面，无需重载 */
    private fun watchFilterRules() {
        viewModelScope.launch {
            locator.filterRepo.observeAll().collect { rules ->
                val wv = webView
                if (wv == null || rules.isEmpty()) return@collect
                // 只在页面已加载后热更新（避免首次空跑）
                loadFilterScripts(wv, reInject = true)
            }
        }
    }
    /** 刷新登录状态（主线程） */
    fun refreshLoginState() {
        val wv = webView ?: return
        _loggedIn.value = wv.isLoggedIn()
        // 同步到 AuthController（供设置页等共享）
        locator.authController.refresh()
    }

    // ---- 登录 ----

    /** 打开登录页：直接在 WebView 内加载，Cookie 写入 WebView 存储 */
    fun startLogin() {
        LogStore.log(LogCategory.AUTH, "在 WebView 内打开登录页")
        loadUrl(Constants.LOGIN_URL)
    }

    /** 已登录点击徽章：确认后登出 */
    fun confirmLogout(context: android.content.Context) {
        val activity = context as? android.app.Activity
        val dialog = android.app.AlertDialog.Builder(activity ?: return)
            .setTitle("登出")
            .setMessage("确定要退出登录吗？将清除 x.com 的登录 Cookie。")
            .setPositiveButton("登出") { _, _ ->
                locator.authController.logout(webView)
                refreshLoginState()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    private fun scheduleHistoryWrite(url: String) {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            delay(3000)
            writeHistory(url, "", "", "")
        }
    }

    /**
     * 停留超 3s / 点击详情时写入历史。
     * [text]/[displayName]/[mediaUrl] 由页面 JS 从 DOM 提取后经 Bridge 上报。
     */
    fun writeHistory(url: String, text: String = "", displayName: String = "", mediaUrl: String = "") {
        viewModelScope.launch {
            // URL 归一化：/photo/N、/video/N 子页一律指向帖子整页（历史点击回到帖子页而非放大图）
            val norm = url.replace(Regex("/photo/\\d+$"), "").replace(Regex("/video/\\d+$"), "")
            val parsed = HistoryRepo.parseTweetUrl(norm) ?: return@launch
            val (username, tweetId) = parsed
            val enabled = locator.settings.historyEnabled.first()
            if (!enabled) return@launch
            // 页面 JS 未上报标题时（非详情点击路径），主动回读页面
            var t = text
            var name = displayName
            var mUrl = mediaUrl
            if (t.isBlank() && mUrl.isBlank()) {
                val meta = queryPageForMetadata()
                t = meta.first
                mUrl = meta.second
            }
            val record = HistoryRecord(
                url = norm,
                tweetId = tweetId,
                username = username,
                displayName = name,
                textPreview = t,
                mediaUrl = mUrl,
                visitedAt = System.currentTimeMillis(),
            )
            locator.historyRepo.upsert(record)
            // 缩略图本地化：落盘到 filesDir/thumb/history-{tweetId}.jpg（重复访问覆盖同一文件）
            if (mUrl.isNotBlank()) {
                val key = "history-$tweetId"
                val thumbPath = com.xverse.app.core.util.ThumbCache.persist(
                    locator.appContext, key, mUrl
                )
                if (thumbPath.isNotBlank()) {
                    locator.historyRepo.upsert(record.copy(thumbPath = thumbPath))
                }
            }
            LogStore.log(LogCategory.HISTORY, "已记录: @$username/$tweetId")
        }
    }

    /** 主线程 evaluateJavascript 读页面元数据（tweetText 摘要 + 媒体缩略图），返回 (text, mediaUrl) */
    private suspend fun queryPageForMetadata(): Pair<String, String> {
        val wv = webView ?: return "" to ""
        return withContext(kotlinx.coroutines.Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript(META_READER_SCRIPT) { r ->
                    val raw = r ?: "null"
                    // evaluateJavascript 对返回值再做一次 JSON 编码：脚本返回 JSON 字符串时，
                    // 回调拿到的是带转义的字符串字面量，先解包再解析
                    val json = try {
                        val first = org.json.JSONTokener(raw).nextValue()
                        if (first is String) org.json.JSONObject(first) else org.json.JSONObject()
                    } catch (_: Exception) {
                        org.json.JSONObject()
                    }
                    cont.resume(json.optString("text") to json.optString("mediaUrl"))
                }
            }
        }
    }

    fun goBack() {
        val wv = webView ?: return
        if (wv.canGoBack()) wv.goBack()
    }

    fun goForward() {
        webView?.takeIf { it.canGoForward() }?.goForward()
    }

    fun reload() {
        webView?.reload()
    }

    fun goHome() {
        webView?.loadUrl("https://x.com")
    }

    /** WebChromeClient 进度回调 */
    fun onProgress(p: Int) {
        progress.value = p
    }

    override fun onCleared() {
        historyJob?.cancel()
        super.onCleared()
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return BrowserViewModel() as T
            }
        }

        /** 历史上报脚本：document_idle 注入，点击详情/媒体链接时上报（含行注释规范）。
         *  同时从 DOM 提取推文正文摘要（tweetText）与媒体缩略图直链。
         */
        private val HISTORY_TRACKING_SCRIPT = """
            // 历史记录：点击推文详情/媒体链接时上报原生
            // URL 归一化：photo/N、video/N 等媒体子页一律指向帖子整页（历史点击回到帖子页，而非放大图）
            function xvNorm(h) {
              return (h || '').replace(/\/photo\/\d+$/, '').replace(/\/video\/\d+$/, '');
            }
            // 元数据从被点击链接所在的 article 提取：
            // 时间线上 querySelector('article') 会取到页面第一个帖子，与点中的那条张冠李戴，
            // 多图帖因此丢失正文/缩略图（记成了别的帖子）。
            function xvMeta(anchor) {
              var t = '', n = '', m = '';
              var art = anchor && anchor.closest ? anchor.closest('article') : null;
              if (!art) art = document.querySelector('article');
              if (art) {
                var text = art.querySelector('[data-testid="tweetText"]');
                t = text ? text.innerText.trim().slice(0, 200) : '';
                var nameEl = art.querySelector('[data-testid="User-Name"]');
                n = nameEl ? nameEl.innerText.split(String.fromCharCode(10))[0].trim() : '';
                var img = art.querySelector('img[src*="pbs.twimg.com/media/"]');
                if (img && img.src) {
                  m = img.src.split('?')[0] + '?format=jpg&name=small';
                }
              }
              return {text:t, name:n, mediaUrl:m};
            }
            document.addEventListener('click', function(e){
              var a = e.target && e.target.closest ? e.target.closest('a[href*="/status/"]') : null;
              if (!a) return;
              var href = xvNorm(a.getAttribute('href') || '');
              if (!href) return;
              var url = href.indexOf('http') === 0 ? href : (location.origin + href);
              if (window.XVerseNative) {
                var meta = xvMeta(a);
                XVerseNative.call('recordHistory', JSON.stringify({
                  url: url, text: meta.text, name: meta.name, mediaUrl: meta.mediaUrl
                }));
              }
            }, true);
        """.trimIndent()

        /** 页面元数据读取（停留路径被动回读）：返回 JSON 字符串 */
        private val META_READER_SCRIPT = """
            (function(){
              var art = document.querySelector('article');
              var t = '', m = '';
              if (art) {
                var text = art.querySelector('[data-testid="tweetText"]');
                t = text ? text.innerText.trim().slice(0, 200) : '';
                var img = art.querySelector('img[src*="pbs.twimg.com/media/"]');
                if (img && img.src) m = img.src.split('?')[0] + '?format=jpg&name=small';
              }
              return JSON.stringify({text:t, mediaUrl:m});
            })();
        """.trimIndent()

        /** GraphQL TweetDetail 响应拦截（X-Vault 思路，document_start 注入）：
         *  patch fetch/XHR，命中 TweetDetail 时把响应 JSON 经 Bridge 上报原生缓存媒体直链。
         *  工程规范 #1：内联 IIFE；#4：含 URL 注释一律行注释。
         *  document_start 时 window.fetch 可能未就绪，用重试等待（最多 5s）。
         */
        private val GRAPHQL_HOOK_SCRIPT = """
            (function(){
              // 低侵入 GraphQL 拦截（参照 BetterX 防御手法）：
              //  - 快速路径预扫描：响应不含 extended_entities 就不 JSON.parse
              //  - 响应大小守卫：>8MB 跳过
              //  - XHR 用 load 一次性监听（once:true，自动移除，不泄漏）
              //  - watchdog 周期重装（SPA 重建 / 原型被替换后恢复）
              //  - 全程 try/catch，绝不抛错，绝不阻塞原请求
              if (window.__xvGraphqlHooked) return;
              window.__xvGraphqlHooked = true;
              var endpointTest = /\/graphql\/[A-Za-z0-9_-]+\/TweetDetail/;
              var MAX_BYTES = 8388608; // 8MB
              // 当前页面推文 id（/user/status/12345 → 12345），用于缓存按推文区分
              var pageTweetId = '';
              var pm = location.pathname.match(/\/status\/(\d+)/);
              if (pm) pageTweetId = pm[1];

              function tryReport(text) {
                // 快速路径：不含媒体标记直接跳过（避免对每个 API 响应做 JSON.parse）
                if (text.indexOf('extended_entities') < 0 &&
                    text.indexOf('"media"') < 0 &&
                    text.indexOf('media_url_https') < 0) return;
                try {
                  var j = JSON.parse(text);
                  var d = j && j.data || {};
                  // 递归找含媒体数组的推文对象（深度上限 20）
                  function hasMediaObj(o) {
                    return o && typeof o === 'object' && o.media_url_https;
                  }
                  function findTweet(obj, depth) {
                    if (!obj || typeof obj !== 'object' || depth > 20) return null;
                    var leg = obj.legacy;
                    if (leg) {
                      var em = leg.extended_entities;
                      if (em && em.media && em.media.length && hasMediaObj(em.media[0])) return obj;
                      if (leg.entities && leg.entities.media && leg.entities.media.length && hasMediaObj(leg.entities.media[0])) return obj;
                    }
                    if (obj.media && obj.media.length && hasMediaObj(obj.media[0])) return obj;
                    var keys = obj instanceof Array ? obj : Object.keys(obj);
                    for (var i = 0; i < keys.length; i++) {
                      var r = findTweet(obj instanceof Array ? obj[i] : obj[keys[i]], depth + 1);
                      if (r) return r;
                    }
                    return null;
                  }
                  var tresult = findTweet(d, 0);
                  if (!tresult) return;
                  var media = (tresult.legacy && tresult.legacy.extended_entities &&
                              tresult.legacy.extended_entities.media &&
                              tresult.legacy.extended_entities.media.length)
                              ? tresult.legacy.extended_entities.media
                              : (tresult.media && tresult.media.length ? tresult.media
                                 : (tresult.legacy && tresult.legacy.entities && tresult.legacy.entities.media
                                    ? tresult.legacy.entities.media : []));
                  console.log('[XV] graphql media=' + media.length);
                  if (window.XVerseNative) {
                    // 只上报主推文的 legacy（不含回复线程），避免把评论区媒体也计入
                    var sendObj = tresult.legacy ? {legacy: tresult.legacy} : {media: media};
                    // 同时上报当前页面推文 id，原生侧按推文隔离缓存
                    var pageId = (tresult.rest_id || tresult.id_str || pageTweetId || '');
                    XVerseNative.call('mediaResponse', JSON.stringify({
                      tweetId: pageId,
                      data: JSON.stringify(sendObj)
                    }));
                  }
                } catch (e) {}
              }

              function onXhrDone(xhr) {
                try {
                  var url = xhr.__xvUrl || '';
                  if (!endpointTest.test(url)) return;
                  var body = xhr.responseText;
                  if (!body && xhr.responseType === 'json' && xhr.response) {
                    body = JSON.stringify(xhr.response);
                  }
                  if (body && body.length > 100 && body.length < MAX_BYTES) tryReport(body);
                } catch (e) {}
              }

              var installed = false;
              function install() {
                try {
                  var origFetch = window.fetch;
                  if (origFetch && !window.__xvFetchHooked) {
                    window.fetch = function(input, init) {
                      var u = typeof input === 'string' ? input : (input && input.url) || '';
                      var p = origFetch.apply(this, arguments);
                      if (endpointTest.test(u)) {
                        p.then(function(res){
                          try {
                            res.clone().text().then(function(body){
                              if (body && body.length > 100 && body.length < MAX_BYTES) tryReport(body);
                            });
                          } catch (e) {}
                        }).catch(function(){});
                      }
                      return p;
                    };
                    window.__xvFetchHooked = true;
                  }
                  var origOpen = XMLHttpRequest.prototype.open;
                  var origSend = XMLHttpRequest.prototype.send;
                  if (origSend && !window.__xvXhrHooked) {
                    XMLHttpRequest.prototype.open = function(m, url) {
                      this.__xvUrl = url;
                      return origOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function() {
                      // 参照 BetterX：load 一次性监听，自动移除，不泄漏
                      try { this.addEventListener('load', function(){ onXhrDone(this); }, {once:true}); }
                      catch (e) {}
                      return origSend.apply(this, arguments);
                    };
                    window.__xvXhrHooked = true;
                  }
                  installed = true;
                  console.log('[XV] graphql hook ok');
                } catch (e) {}
              }

              // document_start 时 fetch/XHR 可能未就绪，重试等待 + watchdog 兜底
              var tries = 0;
              (function retry() {
                if (installed) return;
                if ((window.fetch || window.XMLHttpRequest) && tries < 25) {
                  install();
                } else if (tries < 25) {
                  tries++;
                  setTimeout(retry, 200);
                }
              })();
            })();
        """.trimIndent()

        /** 下载按钮脚本：document_idle 注入，推文页悬浮「下载」按钮 + 解析面板（M3）。
         *  工程规范 #1：内联 IIFE，不使用 new Function/eval；
         *  #2：回调经 window.__gmCallbacks 协议回传；
         *  #4：含 URL 注释一律用行注释。
         */
        private val DOWNLOAD_BUTTON_SCRIPT = """
            (function(){
              // 悬浮下载按钮：常驻注入 + URL 监视显隐。
              // x.com 是 SPA（pushState 导航），onPageFinished 只在整页加载时触发；
              // 因此按钮必须一次性创建，再按 pathname 切换显隐，而不是注入时判断一次。
              // 工程规范 #1：内联 IIFE，不使用 new Function/eval；
              // #2：回调经 window.__gmCallbacks 协议回传；#4：含 URL 注释一律行注释。
              if (window.__xverseDlInjected) return;
              window.__xverseDlInjected = true;

              var cbSeq = 0;
              // 原生回调协议：注册回调 + 调用原生
              function callNative(type, payload, cb) {
                var id = 'dl_' + (++cbSeq);
                window.__gmCallbacks = window.__gmCallbacks || {};
                if (cb) {
                  window.__gmCallbacks[id] = function(raw) {
                    try { cb(JSON.parse(raw)); }
                    catch (e) { cb(null); }
                  };
                }
                var body = payload || {};
                body._cb = id;
                if (window.XVerseNative) {
                  window.XVerseNative.call(type, JSON.stringify(body));
                } else {
                  delete window.__gmCallbacks[id];
                  if (cb) cb({ok:false, error:'native bridge missing'});
                }
              }

              // 样式（position:fixed 的 right/bottom 可能被 x.com 的 transform 祖先破坏，
              // 改用 JS 精确计算 top/left 并加 !important 防覆盖）
              var style = document.createElement('style');
              style.textContent = [
                '.xv-dl-btn{position:fixed !important;z-index:99999 !important;',
                'width:52px;height:52px;border-radius:50%;border:none;cursor:pointer;',
                'display:flex;align-items:center;justify-content:center;',
                'background:#1D9BF0 !important;color:#fff !important;font-size:24px;',
                'box-shadow:0 4px 12px rgba(0,0,0,.35) !important;}',
                '.xv-dl-panel{position:fixed !important;left:10px;right:10px;bottom:90px;z-index:99999;',
                'max-height:55vh;overflow-y:auto;background:#fff;border-radius:16px;',
                'box-shadow:0 8px 30px rgba(0,0,0,.4);padding:10px 12px;font:14px/1.5 -apple-system,Roboto,sans-serif;}',
                '.xv-dl-panel h4{margin:4px 0 8px;font-size:14px;color:#0f1419;}',
                '.xv-dl-item{display:flex;align-items:center;gap:10px;padding:9px 8px;',
                'border-bottom:1px solid #eef1f5;color:#0f1419;}',
                '.xv-dl-item:last-child{border-bottom:none;}',
                '.xv-dl-item .t{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}',
                '.xv-dl-item .go{color:#1D9BF0;font-weight:600;cursor:pointer;}',
                '.xv-dl-close{float:right;border:none;background:none;font-size:18px;color:#536471;cursor:pointer;}',
                '@media (prefers-color-scheme:dark){',
                '.xv-dl-panel{background:#16181c;}',
                '.xv-dl-panel h4,.xv-dl-item{color:#e7e9ea;}',
                '.xv-dl-item{border-color:#2f3336;}',
                '.xv-dl-close{color:#8b98a5;}',
                '}'
              ].join('');

              function ensureCss() { if (!style.parentNode) (document.head||document.documentElement).appendChild(style); }

              // 按钮精确定位：JS 计算，绕开 x.com 对 fixed right/bottom 的干扰
              function positionBtn() {
                var vw = window.innerWidth;
                var vh = window.innerHeight;
                btn.style.left = (vw - 66) + 'px';
                btn.style.top = (vh - 162) + 'px';
                btn.style.right = 'auto';
                btn.style.bottom = 'auto';
              }

              var btn = document.createElement('button');
              btn.type = 'button';
              btn.className = 'xv-dl-btn';
              btn.textContent = '↓';
              btn.title = '下载媒体';
              btn.style.display = 'none'; // 默认隐藏，URL 命中推文页才显示

              var panel = null;
              var panelOpen = false;
              function closePanel() {
                if (panel) { panel.remove(); panel = null; }
                panelOpen = false;
                btn.style.display = '';
              }

              function setPanelHeader(title) {
                var h = document.createElement('h4');
                h.textContent = title;
                var c = document.createElement('button');
                c.type = 'button';
                c.className = 'xv-dl-close';
                c.textContent = '×';
                c.addEventListener('click', closePanel);
                h.appendChild(c);
                panel.appendChild(h);
              }

              function addItem(label, mediaObj) {
                var row = document.createElement('div');
                row.className = 'xv-dl-item';
                var t = document.createElement('span');
                t.className = 't';
                t.textContent = label;
                var go = document.createElement('span');
                go.className = 'go';
                go.textContent = '下载';
                go.dataset.json = JSON.stringify(mediaObj);
                go.addEventListener('click', function(){
                  var m = JSON.parse(this.dataset.json);
                  callNative('enqueueDownload', {tweetUrl: tweetUrl, media: m}, function(r){
                    if (r && r.ok) closePanel();
                  });
                });
                row.appendChild(t);
                row.appendChild(go);
                panel.appendChild(row);
              }

              var tweetUrl = '';

              // DOM 直取路径：页面已渲染的媒体元素 → 直链（GraphQL 缓存为空时的兜底）
              function domExtract() {
                var out = [];
                var mUrl, vEls, i;
                var imgs = document.querySelectorAll('img[src*="pbs.twimg.com/media/"]');
                var seen = {};
                for (i = 0; i < imgs.length; i++) {
                  mUrl = imgs[i].src.split('?')[0];
                  if (!mUrl || seen[mUrl]) continue;
                  seen[mUrl] = 1;
                  var big = mUrl + '?format=jpg&name=orig';
                  out.push({url: big, quality: '原图', size: 0, extension: 'jpg'});
                }
                vEls = document.querySelectorAll('video');
                for (i = 0; i < vEls.length; i++) {
                  var v = vEls[i];
                  var src = (v.currentSrc || v.src || '');
                  if (src && src.indexOf('blob:') !== 0) {
                    if (!seen[src]) {
                      seen[src] = 1;
                      out.push({url: src.split('?')[0], quality: '视频', size: 0, extension: 'mp4'});
                    }
                  }
                  var sources = v.querySelectorAll('source');
                  for (var s = 0; s < sources.length; s++) {
                    var sSrc = sources[s].src || sources[s].getAttribute('src') || '';
                    if (sSrc && sSrc.indexOf('blob:') !== 0 && !seen[sSrc]) {
                      seen[sSrc] = 1;
                      out.push({url: sSrc.split('?')[0], quality: '视频', size: 0, extension: 'mp4'});
                    }
                  }
                }
                return out;
              }

              function isTweetPage() {
                return /\/[^\/]+\/status\/\d+/.test(location.pathname);
              }

              function openPanel() {
                if (panel) { closePanel(); return; }
                ensureCss();
                btn.style.display = 'none';
                panelOpen = true;
                tweetUrl = location.href.split('#')[0];
                console.log('[XV] dl btn click: ' + tweetUrl);
                panel = document.createElement('div');
                panel.className = 'xv-dl-panel';
                setPanelHeader('解析中…');
                (document.body||document.documentElement).appendChild(panel);
                callNative('parseTweet', {url: tweetUrl}, function(res){
                  var media = (res && res.ok && res.media) ? res.media : [];
                  if (!media.length) media = domExtract();
                  if (!media.length) {
                    panel.textContent = '';
                    setPanelHeader('未解析到媒体');
                    btn.style.display = '';
                    return;
                  }
                  panel.textContent = '';
                  setPanelHeader('选择清晰度');
                  var isPhoto = media.every(function(m){ return m.extension === 'jpg'; });
                  media.forEach(function(m, idx){
                    var label = m.quality ? m.quality : '原画';
                    if (m.size) label += ' · ' + fmtSize(m.size);
                    if (isPhoto) label = '图片 ' + (idx + 1) + ' · ' + label;
                    addItem(label, m);
                  });
                });
              }

              function fmtSize(b) {
                if (!b) return '';
                if (b > 1048576) return (b/1048576).toFixed(1) + ' MB';
                return Math.round(b/1024) + ' KB';
              }

              btn.addEventListener('click', openPanel);

              // URL 监视：SPA 导航切换按钮显隐 + 关闭残留面板
              var lastPath = location.pathname;
              setInterval(function(){
                var onTweet = isTweetPage();
                var shown = btn.style.display !== 'none';
                if (lastPath !== location.pathname) {
                  lastPath = location.pathname;
                }
                if (onTweet && !shown && !panelOpen) {
                  btn.style.display = '';
                  positionBtn();
                } else if (!onTweet && shown) {
                  btn.style.display = 'none';
                  closePanel();
                }
                // 面板开着时按钮隐藏是预期的（openPanel 置 none）
                if (onTweet && lastPath !== location.pathname) {
                  lastPath = location.pathname;
                  closePanel();
                }
              }, 800);

              ensureCss();
              (document.body||document.documentElement).appendChild(btn);
              positionBtn();
              // 初始状态按当前 URL 设置
              if (isTweetPage()) btn.style.display = '';
            })();
        """.trimIndent()
    }
}
