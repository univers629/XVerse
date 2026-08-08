// 过滤第 4 层：GraphQL 响应拦截删除广告（最彻底，参考 XTwitter 去除页面广告脚本 576075）
// document_start 注入，patch window.fetch / XMLHttpRequest，在 X 前端消费 GraphQL
// 时间线响应前，检测广告标记并直接从数据中删除广告条目。
// 只删除广告条目，绝不改写用户数据；工程规范 #1 内联 IIFE，不用 eval。
(function () {
  'use strict';
  if (window.__xvAdStripHooked) return;
  window.__xvAdStripHooked = true;
  // 标记过滤模式：strip=完全不加载广告。mutation 层读到后对漏网广告只隐藏不建卡片
  // （见 anti-promo-mutation.js hideArticle：strip 模式下无占位卡片、无可点击验证）。
  window.__xvFilterMode = 'strip';

  // 时间线/探索/搜索等 GraphQL 端点（与下载 hook 分开，独立匹配；TweetDetail 留给媒体上报 hook）
  // 包含所有可能携带时间线条目（含广告）的端点；PinnedTimelines 也参与（冷启动会先拉它）。
  // 注意：不包含 TweetResultByRestId —— 详情页按 ID 拉取主推文数据的端点，不是广告流；
  // 参与广告过滤会把主推文当条目删掉，导致帖子消失、评论区顶替（历史页回跳出现过的 bug）。
  var TIMELINE_RE = /\/graphql\/[A-Za-z0-9_-]+\/(?:HomeTimeline|HomeLatestTimeline|SearchTimeline|UserTweets|UserMedia|UserTweetsAndReplies|ListLatestTweetsTimeline|ExploreTimeline|Bookmarks|NotificationsTimeline|PinnedTimelines|TimelineShowAlert|MutesTimeline|BlockedAccountsTimeline|ListMembershipsTimeline|ListPinsTimeline|PeopleDiscoveryModule|HomeTimelineQuery|FavoriteTimeline)(?:\?|$)/;
  var MAX_BYTES = 8388608; // 8MB 守卫

  var LOG_KEY = '[XV] ad strip';

  // 广告判定启发式（综合 XTwitter 576075 + BetterX 588748）：
  //  - entryId / item.entryId 含 promoted
  //  - clientEventInfo.component 含 promoted / promotion
  //  - tweetDisplayType 含 Promoted
  //  - 任意层级出现 promotedMetadata / promoted_metadata
  //  - source 字符串含 Ads Manager / Twitter Ads / X Ads
  //  - socialContext.text 含 广告/推广/赞助/Ad/Promoted/Sponsored
  // 引用/转推推文排除（作者明言保护，不被误删）
  var AD_LABEL_RE = /(?:广告|推广|赞助|Ad\b|Promoted|Sponsored)/i;
  var SOURCE_RE = /(?:Ads Manager|Twitter Ads|X Ads)/i;

  function isQuoteRelated(node, seen) {
    if (!node || typeof node !== 'object') return false;
    seen = seen || new WeakSet();
    if (seen.has(node)) return false;
    seen.add(node);
    if (node.__typename && /(?:Quote|Quoted)/.test(node.__typename)) return true;
    var keys = Object.keys(node);
    for (var i = 0; i < keys.length && i < 60; i++) {
      var k = keys[i];
      if (k.indexOf('quoted') >= 0 || k.indexOf('Quote') >= 0) return true;
      var v = node[k];
      if (v && typeof v === 'object' && isQuoteRelated(v, seen)) return true;
    }
    return false;
  }

  function isPromotedEntry(obj) {
    if (!obj || typeof obj !== 'object') return false;
    // 强信号：结构层面确定是广告——不因引用关系保留（广告条目标记在自身 result 层，
    // 与引用的普通内容无关；hasPromotedDeep 已跳过 quoted_status_result）
    var tn = obj.__typename;
    if (typeof tn === 'string' && tn.indexOf('Promoted') >= 0) return true;
    if (obj.advertiserAccount) return true;
    if (obj.promotedMetadata || obj.promoted_metadata) return true;
    var eid = obj.entryId;
    if (typeof eid === 'string' &&
        (eid.indexOf('promoted-tweet-') >= 0 || eid.toLowerCase().indexOf('promoted') >= 0)) return true;
    var item = obj.item;
    if (item && typeof item.entryId === 'string' &&
        (item.entryId.indexOf('promoted-tweet-') >= 0 ||
         item.entryId.toLowerCase().indexOf('promoted') >= 0)) return true;
    // 深层广告标记：entry.content.itemContent.tweet_results.result.promotedMetadata 等。
    // hasPromotedDeep 跳过 legacy/quoted_status_result，只匹配条目自身的广告元数据，
    // 因此必须先于 isQuoteRelated 判定——否则带引用内容的广告会因"引用保护"漏网。
    var keys = Object.keys(obj);
    for (var i = 0; i < keys.length && i < 40; i++) {
      var k = keys[i];
      if (SKIP_KEYS[k]) continue;
      var v = obj[k];
      if (v && typeof v === 'object' && hasPromotedDeep(v)) return true;
    }
    // 引用/转推保护：仅对弱信号生效，避免把带引用关系的普通帖误删
    if (isQuoteRelated(obj)) return false;
    var cei = obj.clientEventInfo;
    if (cei && cei.component) {
      var c = String(cei.component).toLowerCase();
      if (c.indexOf('promoted') >= 0 || c.indexOf('promotion') >= 0) return true;
    }
    var tdt = obj.tweetDisplayType;
    if (typeof tdt === 'string' && tdt.indexOf('Promoted') >= 0) return true;
    // socialContext 文本广告标记
    var sc = obj.socialContext;
    if (sc && typeof sc.text === 'string' && AD_LABEL_RE.test(sc.text)) return true;
    var src = obj.source;
    if (typeof src === 'string' && SOURCE_RE.test(src)) return true;
    return false;
  }

  // 深层探测：递归查找 promotedMetadata / advertiserAccount / __typename=Promoted* /
  // socialContext.text(广告/推广/Ad 等) / source(Ads Manager 等)，
  // 深度守卫 + 跳过内容键，绝不触碰用户数据。找到即判定广告。
  function hasPromotedDeep(node, depth, seen) {
    if (!node || typeof node !== 'object' || depth > 12) return false;
    if (seen && seen.has(node)) return false;
    if (!seen) seen = new WeakSet();
    seen.add(node);
    if (node.promotedMetadata || node.promoted_metadata) return true;
    if (node.advertiserAccount) return true;
    var tn2 = node.__typename;
    if (typeof tn2 === 'string' && tn2.indexOf('Promoted') >= 0) return true;
    // socialContext 文本广告标记（广告 label "Ad"/"广告" 常挂在 result 层 socialContext）
    var sc2 = node.socialContext;
    if (sc2 && typeof sc2.text === 'string' && AD_LABEL_RE.test(sc2.text)) return true;
    var src2 = node.source;
    if (typeof src2 === 'string' && SOURCE_RE.test(src2)) return true;
    if (Array.isArray(node)) {
      for (var i = 0; i < node.length; i++) {
        if (hasPromotedDeep(node[i], depth + 1, seen)) return true;
      }
      return false;
    }
    var keys = Object.keys(node);
    for (var j = 0; j < keys.length; j++) {
      var k = keys[j];
      if (SKIP_KEYS[k]) continue;
      var v = node[k];
      if (v && typeof v === 'object' && hasPromotedDeep(v, depth + 1, seen)) return true;
    }
    return false;
  }

  // 递归清洗：删除数组中的广告条目；对象里的广告字段删除。
  // 深度守卫 + seen 守卫，防堆栈溢出与循环引用（参考 XTwitter 分层遍历策略）。
  // 跳过内容键（legacy/entities/media/quoted_status_result/extended_entities）——不触碰用户数据。
  var SKIP_KEYS = { legacy: 1, entities: 1, media: 1, quoted_status_result: 1, extended_entities: 1 };

  function sanitize(value, depth, seen) {
    if (!value || typeof value !== 'object' || depth > 24) return value;
    if (seen.has(value)) return value;
    seen.add(value);
    if (Array.isArray(value)) {
      for (var i = 0; i < value.length; i++) {
        var e = value[i];
        if (isPromotedEntry(e)) {
          value.splice(i, 1);
          i--;
        } else {
          sanitize(e, depth + 1, seen);
        }
      }
      return value;
    }
    var keys = Object.keys(value);
    for (var j = 0; j < keys.length; j++) {
      var k = keys[j];
      if (SKIP_KEYS[k]) continue;
      var v = value[k];
      if (isPromotedEntry(v)) {
        delete value[k];
      } else {
        sanitize(v, depth + 1, seen);
      }
    }
    return value;
  }

  // 定位时间线结构：递归找所有 `entries` 数组（叶子推文条目），对每个数组做 sanitize。
  // 注意：只处理 entries，不对 instructions 整体判定——避免把含正常推文的指令块误删。
  function pruneTimeline(root) {
    var removed = false;
    try {
      (function walk(o, depth) {
        if (!o || typeof o !== 'object' || depth > 14) return;
        if (Array.isArray(o.entries)) {
          var before = o.entries.length;
          sanitize(o.entries, 0, new WeakSet());
          if (o.entries.length < before) removed = true;
        }
        var keys = Object.keys(o);
        for (var i = 0; i < keys.length; i++) {
          var v = o[keys[i]];
          if (v && typeof v === 'object') walk(v, depth + 1);
        }
      })(root, 0);
    } catch (e) {}
    return removed;
  }

  function shouldRewrite(url) {
    return typeof url === 'string' && TIMELINE_RE.test(url);
  }

  // 重写响应体：删除广告条目后返回新文本（原样保留非广告内容）；无广告则返回原文本
  function rewriteBody(text) {
    if (!text || text.length < 200 || text.length > MAX_BYTES) return text;
    try {
      var j = JSON.parse(text);
      if (j && j.data && pruneTimeline(j.data)) {
        var out = JSON.stringify(j);
        console.log(LOG_KEY + ' 删除广告条目 ' + text.length + '->' + out.length);
        return out;
      }
      return text;
    } catch (e) {
      return text;
    }
  }

  // ---- patch XMLHttpRequest：readystatechange 时同步改写 responseText ----
  // 与 GRAPHQL_HOOK 共用同一对 open/send patch 链；注入顺序依赖 XWebView 的
  // addEarly 顺序，且需在 fetch/XHR 就绪后 patch，故用 retry+watchdog 兜底
  // （x.com 在 document_start 后不久才创建 XHR 构造函数场景，稍后自愈重装）。
  var installed = false;
  function install() {
    try {
      if (installed) return;
      if (!(window.fetch && window.XMLHttpRequest)) return false;
      if (window.__xvXhrAdHooked && window.__xvFetchAdHooked) {
        installed = true;
        return true;
      }
      var origFetch = window.fetch;
      if (origFetch && !window.__xvFetchAdHooked) {
        window.fetch = function (input, init) {
          var u = typeof input === 'string' ? input : (input && input.url) || '';
          var p = origFetch.apply(this, arguments);
          if (!shouldRewrite(u)) return p;
          return p.then(function (res) {
            return res.clone().text().then(function (body) {
              var n = rewriteBody(body);
              if (n === body) return res;
              try {
                // 构造合成 Response：保留状态/头部，替换 body
                return new Response(n, {
                  status: res.status,
                  statusText: res.statusText,
                  headers: res.headers
                });
              } catch (e) {
                return res;
              }
            });
          }).catch(function () { return p; });
        };
        window.__xvFetchAdHooked = true;
      }
      var origOpen = XMLHttpRequest.prototype.open;
      var origSend = XMLHttpRequest.prototype.send;
      if (origSend && !window.__xvXhrAdHooked) {
        XMLHttpRequest.prototype.open = function (m, url) {
          this.__xvAdUrl = url;
          return origOpen.apply(this, arguments);
        };
        XMLHttpRequest.prototype.send = function () {
          try {
            var xhr = this;
            xhr.addEventListener('readystatechange', function () {
              try {
                if (xhr.readyState !== 4 || xhr.status !== 200) return;
                var xurl = xhr.__xvAdUrl;
                if (!shouldRewrite(xurl)) return;
                var text = xhr.responseText;
                if (!text || text.length < 200 || text.length > MAX_BYTES) return;
                var n = rewriteBody(text);
                if (n !== text) {
                  Object.defineProperty(xhr, 'responseText', {
                    value: n, writable: false, configurable: true
                  });
                }
              } catch (e) {}
            });
          } catch (e) {}
          return origSend.apply(this, arguments);
        };
        window.__xvXhrAdHooked = true;
      }
      installed = true;
      console.log(LOG_KEY + ' ok');
      return true;
    } catch (e) {
      return false;
    }
  }

  // document_start 时 fetch/XHR 可能未就绪：重试等待 + watchdog 兜底（参照 GRAPHQL_HOOK 防御）
  var tries = 0;
  (function retry() {
    if (installed) return;
    if ((window.fetch || window.XMLHttpRequest) && tries < 25) {
      if (install()) return;
      tries++;
      setTimeout(retry, 200);
    } else if (tries < 25) {
      tries++;
      setTimeout(retry, 200);
    }
  })();
  // 周期自愈：页面 SPA 重建 / 原型被替换后恢复 patch
  setInterval(function () {
    if (!installed) install();
    else if (!window.__xvFetchAdHooked || !window.__xvXhrAdHooked) {
      installed = false;
      install();
    }
  }, 5000);
})();
