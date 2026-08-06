// 过滤第 2 层：MutationObserver 增量监听
// 监听新节点，命中推广规则即 display:none（不移除 DOM，保滚动布局稳定）。
(function () {
  'use strict';
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
    // 命中标记：placementTracking 或 Ad/推广 徽标（任意位置精确匹配）
    if (article.querySelector('div[data-testid="placementTracking"]')) return true;
    if (hasAdLabel(article)) return true;
    return false;
  }

  function scan(container) {
    var articles = (container || document).querySelectorAll
      ? container.querySelectorAll('article[data-testid="tweet"]')
      : [];
    var i, article;
    for (i = 0; i < articles.length; i++) {
      article = articles[i];
      if (!article.dataset.xverseHidden && isPromo(article)) {
        article.style.display = 'none';
        article.dataset.xverseHidden = '1';
      }
    }
  }

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

  // 观察主列，subtree 全监听
  var root = document.querySelector('div[data-testid="primaryColumn"]') || document.body;
  observer.observe(root, { childList: true, subtree: true });

  // 初始扫描
  scan(document);

  // 轮询兜底（虚拟列表可能绕过 observer 卸载节点）
  var iv = setInterval(function () {
    if (document.querySelector('div[data-testid="primaryColumn"]')) scan(document);
  }, 3000);
  // 页面卸载时清定时器，避免泄漏
  window.addEventListener('pagehide', function () { clearInterval(iv); });
})();
