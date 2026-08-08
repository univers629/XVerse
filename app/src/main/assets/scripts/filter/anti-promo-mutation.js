// 过滤第 2 层：MutationObserver 增量监听
// 监听新节点，命中推广规则即隐藏（不移除 DOM，保滚动布局稳定）。
// 2026-08-08 v3：隐藏方式 display:none → visibility:hidden。
// display:none 会塌陷广告高度 → 整条时间线回流（广告插入/隐藏 → 下方内容被吸上来）
// → 返回首页/滚动时肉眼可见跳动。浏览器 scroll anchoring 在 SPA popstate 恢复期
// 被抑制（WebView 实测 overflow-anchor:auto 但恢复期仍跳），补偿不可靠。
// visibility:hidden 保留广告占位（布局高度不变），零回流，彻底消除跳动。
// 代价：时间线留广告大小的空白占位（可接受，远优于跳动）。
(function () {
  'use strict';
  if (window.__xvAdMutationInstalled) return;
  window.__xvAdMutationInstalled = true;
  // AD 徽标精确文本（576427 参考：扫描后代元素自身文本 === 精确匹配，
  // 避免 ^ 锚定开头漏掉渲染在用户名右上角的徽标，=== 也不误伤 Adobe/Adidas）
  var AD_LABELS = { 'Ad': 1, '广告': 1, '推广': 1, '赞助': 1, 'Promoted': 1 };

  function hasAdLabel(article) {
    var els = article.getElementsByTagName('span');
    var i, el, t;
    for (i = 0; i < els.length; i++) {
      el = els[i];
      if (el.children.length) continue; // 只看叶子 span 自身文本，排除容器聚合
      t = (el.textContent || '').trim();
      if (AD_LABELS[t]) return true;
    }
    return false;
  }

  function isPromo(article) {
    // 已隐藏过则跳过
    if (article.dataset.xverseHidden) return false;
    // 排除 status 详情页主推文：视频帖主推文带 placementTracking（视频播放器跟踪组件），
    // 会被误判为广告隐藏（主贴消失、评论区顶上来）；只在详情页跳过主推文，时间线广告照常过滤。
    if (isStatusMainTweet(article)) return false;
    // 命中标记：placementTracking 或 Ad/推广 徽标（任意位置精确匹配）
    if (article.querySelector('div[data-testid="placementTracking"]')) return true;
    if (hasAdLabel(article)) return true;
    return false;
  }

  // status 详情页（/user/status/id）里的第一个 article 即主推文
  function isStatusMainTweet(article) {
    if (!/^https:\/\/x\.com\/[A-Za-z0-9_]+\/status\/\d+/.test(location.href)) return false;
    var m = document.querySelector('div[data-testid="primaryColumn"] article[data-testid="tweet"]');
    return m === article;
  }

  // 隐藏广告：visibility:hidden 保留占位（布局高度不变，零回流）。
  // 不用 display:none —— 塌陷广告高度会拉动下方内容，造成返回/滚动跳动。
  // 占位中央叠加提示卡片（绝对定位，不参与文章流，不破坏布局）。
  var PLACEHOLDER_TXT = '检测到广告，已屏蔽';
  function hideArticle(article) {
    if (article.dataset.xverseHidden) return;
    article.style.visibility = 'hidden';
    article.dataset.xverseHidden = '1';
    // 提示卡片：父级 visibility:hidden 会连带隐藏子元素，卡片需自身 visibility:visible 显回。
    // position:absolute 定位到占位中央，不参与文章流、不改变布局高度。
    // pointer-events:none 不拦截点击（占位内原本的广告交互无意义，滚动不受影响）。
    article.style.position = 'relative';
    var card = document.createElement('div');
    card.className = 'xverse-ad-placeholder';
    card.setAttribute('data-testid', 'xverseAdPlaceholder');
    var label = document.createElement('span');
    label.textContent = PLACEHOLDER_TXT;
    card.appendChild(label);
    article.appendChild(card);
    // 卡片样式：浅色圆角、半透明、居中，深浅主题下均柔和
    var css = 'position:absolute;left:0;right:0;top:0;bottom:0;margin:auto;' +
      'width:72%;height:52px;border-radius:14px;' +
      'background:rgba(128,128,128,0.12);color:#6b7280;' +
      'display:flex;align-items:center;justify-content:center;' +
      'font-size:13px;letter-spacing:0.02em;pointer-events:none;visibility:visible;';
    card.setAttribute('style', css);
  }

  function scan(container) {
    var articles = (container || document).querySelectorAll
      ? container.querySelectorAll('article[data-testid="tweet"]')
      : [];
    var i, article;
    for (i = 0; i < articles.length; i++) {
      article = articles[i];
      if (article.dataset.xverseHidden || !isPromo(article)) continue;
      hideArticle(article);
    }
  }

  function install() {
    // 主列未就绪（onPageStarted 时 body 常为 null）则重试，
    // 否则 observer.observe(null) 抛错、整个脚本中止（曾实测广告不隐藏）。
    var root = document.querySelector('div[data-testid="primaryColumn"]') || document.body;
    if (!root) return false;

    // 监听动态加载
    var observer = new MutationObserver(function (mutations) {
      var i, m, j, node;
      for (i = 0; i < mutations.length; i++) {
        m = mutations[i];
        if (!m.addedNodes || !m.addedNodes.length) continue;
        for (j = 0; j < m.addedNodes.length; j++) {
          node = m.addedNodes[j];
          if (node.nodeType !== 1) continue;
          if (node.matches && node.matches('article[data-testid="tweet"]')) {
            scan(node);
          } else if (node.querySelector) {
            var hit = node.querySelector('article[data-testid="tweet"]');
            if (hit) scan(hit);
          }
        }
      }
    });
    observer.observe(root, { childList: true, subtree: true });

    // 初始扫描
    scan(document);

    // 轮询兜底（虚拟列表可能绕过 observer 卸载节点）
    var iv = setInterval(function () {
      if (document.querySelector('div[data-testid="primaryColumn"]')) scan(document);
    }, 3000);
    // 页面卸载时清定时器，避免泄漏
    window.addEventListener('pagehide', function () { clearInterval(iv); });
    return true;
  }

  // 就绪重试：body/主列未出现则 200ms 重试，上限 50 次（10s），超时放弃
  var readyTries = 0;
  (function waitReady() {
    if (install()) return;
    readyTries++;
    if (readyTries > 50) return;
    setTimeout(waitReady, 200);
  })();
})();
