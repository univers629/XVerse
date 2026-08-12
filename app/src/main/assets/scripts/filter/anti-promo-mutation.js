// 过滤第 2 层：MutationObserver 增量监听
// 监听新节点，命中推广规则即隐藏（不移除 DOM，保滚动布局稳定）。
// 2026-08-08 v3：隐藏方式 display:none → visibility:hidden。
// display:none 会塌陷广告高度 → 整条时间线回流（广告插入/隐藏 → 下方内容被吸上来）
// → 返回首页/滚动时肉眼可见跳动。浏览器 scroll anchoring 在 SPA popstate 恢复期
// 被抑制（WebView 实测 overflow-anchor:auto 但恢复期仍跳），补偿不可靠。
// visibility:hidden 保留广告占位（布局高度不变），零回流，彻底消除跳动。
// 代价：时间线留广告大小的空白占位（可接受，远优于跳动）。
// 2026-08-08 v4：占位卡片可点击验证。
// 点卡片 → 恢复显示该推文原文（用于确认是否真的广告，防误屏蔽）→ 卡片变「恢复屏蔽」按钮。
// 再点按钮 → 重新遮罩。共享工具 window.__xvFilterCard 供屏蔽词脚本复用（见 userRuleScript）。
// 2026-08-08 v5：实时贴片广告检测。
// 变现视频（X 前贴片插广告、创作者分成，如 ET/ESPN/billboard）无 Ad 徽标、DOM 与普通视频一致，
// 只有播放期瞬时的「广告后播放」文案可区分。高频轮询播放器文本，出现即遮罩。
// 只匹配实测扫到的两条文案（「视频将在广告后播放」/「video will play after ad」），不广撒网防误伤。
// 普通 GIF/视频永不显示该文案 → 零误伤。
// 2026-08-08 v6：带字幕（CC）视频过滤（广告过滤子项）。
// 检测信号 video.textTracks.length > 0，接在播放器轮询内。开关标记 __xvFilterCc 由
// FilterScript 注入（默认 true 过滤），开关热更新只改标记、无需 reload。
// 2026-08-08 v7：AI 生成标签过滤（广告过滤子项，开关在 CC 行下方）。
// 检测信号：叶子 span 自身文本 === 'Made with AI'（精确匹配，零误伤）。
// 接在 scan() 内（observer + 初始扫描 + 3s 轮询覆盖），开关热更新标记 __xvFilterAi。
// 2026-08-11 v8：strip 模式的漏网广告使用专属 data 标记 + CSS !important 固化隐藏状态。
// React 重绘即使覆盖 inline style，也不能让已识别广告重新出现；主路径仍由 GraphQL 层直接删除。
(function () {
  'use strict';
  if (window.__xvAdMutationInstalled) return;
  window.__xvAdMutationInstalled = true;

  // ---- 共享卡片工具：供本层与屏蔽词脚本复用 ----
  // 两种遮罩状态机，互不干扰（独立 dataset 标记）：
  //   hidden（xverseHidden=1）→ revealed（xverseRevealed=1）→ hidden……
  // 只认本「owner」的标记，revealed 时不移除、不重复遮罩。
  window.__xvFilterCard = window.__xvFilterCard || {
    // 建占位卡片（遮罩中）。label 文案 + owner 标记键。
    make: function (article, label, owner) {
      article.style.position = 'relative';
      var card = document.createElement('div');
      card.className = 'xverse-filter-card';
      card.setAttribute('data-testid', owner + 'Card');
      card.textContent = label;
      card.style.cssText =
        'position:absolute;left:0;right:0;top:0;bottom:0;margin:auto;' +
        'width:72%;height:52px;border-radius:14px;' +
        'background:rgba(128,128,128,0.12);color:#6b7280;' +
        'display:flex;align-items:center;justify-content:center;' +
        'font-size:13px;letter-spacing:0.02em;' +
        'cursor:pointer;visibility:visible;';
      // 点卡片 → 恢复显示原文，验证是否误屏蔽
      card.addEventListener('click', function () { reveal(article, owner); });
      article.appendChild(card);
      return card;
    },
    // 隐藏原文（保留占位），并把卡片挂在 article 上
    hide: function (article, label, owner) {
      article.dataset[owner] = '1';
      article.style.visibility = 'hidden';
      article.dataset.xverseHiddenTxt = label;
      if (this._needsCard(article, owner)) {
        var card = this.make(article, label, owner);
        article.__xvCard = card;
      }
    },
    // 遮罩中（owner 的 hidden 标记存在且未 reveal）则建卡。
    // 兼容 xverseRevealed（验证中）→ 不再重复建卡，原文保持可见。
    _needsCard: function (article, owner) {
      if (!article.dataset[owner]) return false;
      if (article.dataset.xverseRevealed) return false;
      return !article.__xvCard;
    }
  };

  // article 上的卡片点击回调：hidden → revealed。
  // 移除卡片 + 原文可见 + 生成「恢复屏蔽」按钮（同层级，覆盖在原文之上）。
  // 长条竖排圆角按钮，固定在帖子左侧中间，不遮右上角「更多」/正文；点击恢复遮罩。
  function reveal(article, owner) {
    article.dataset.xverseRevealed = '1';
    article.style.visibility = 'visible';
    if (article.__xvCard) { article.__xvCard.remove(); article.__xvCard = null; }
    var btn = document.createElement('button');
    btn.className = 'xverse-filter-rehide';
    btn.setAttribute('data-testid', owner + 'Rehide');
    btn.textContent = '恢复屏蔽';
    btn.title = '隐藏';
    btn.style.cssText =
      'position:absolute;left:6px;top:50%;transform:translateY(-50%);z-index:10;' +
      'writing-mode:vertical-rl;text-orientation:upright;' +
      'padding:12px 7px;border-radius:999px;border:none;' +
      'background:rgba(128,128,128,0.18);color:#6b7280;' +
      'font-size:12px;line-height:1.5;letter-spacing:1px;cursor:pointer;';
    btn.addEventListener('click', function () { rehide(article, owner); });
    article.appendChild(btn);
    article.__xvRehide = btn;
  }

  // 「×」回调：revealed → hidden，恢复遮罩 + 卡片
  function rehide(article, owner) {
    delete article.dataset.xverseRevealed;
    article.style.visibility = 'hidden';
    if (article.__xvRehide) { article.__xvRehide.remove(); article.__xvRehide = null; }
    var card = __xvFilterCard.make(article, article.dataset.xverseHiddenTxt || '已屏蔽', owner);
    article.__xvCard = card;
  }

  window.__xvFilterCard.reveal = reveal;
  window.__xvFilterCard.rehide = rehide;

  // AD 徽标精确文本（576427 参考：扫描后代元素自身文本 === 精确匹配，
  // 避免 ^ 锚定开头漏掉渲染在用户名右上角的徽标，=== 也不误伤 Adobe/Adidas）
  // Boosted：付费推广帖徽标（广告主投流，实测叶子 span 精确文本 "Boosted"，
  // 位置在帖子左上角用户名旁，与 Ad/推广 同属推广标识 → 归入广告过滤，不加新行）
  var AD_LABELS = { 'Ad': 1, '广告': 1, '推广': 1, '赞助': 1, 'Promoted': 1, 'Boosted': 1 };

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

  // AI 生成标签（x.com 在 AI 生成图片/视频帖左下角渲染 AI 徽标）。
  // 检测信号：叶子 span 自身文本去空白后包含标签（命中带空格变体）。
  // 实测页面渲染英文「Made with AI」（普通空格）与中文「由 AI 生成」；
  // 精确匹配曾漏掉中文变体 → 文本与标签都去空白后比对（label 一律无空格形式）。
  var AI_LABELS = ['madewithai', '由ai生成'];
  function hasAiLabel(article) {
    var els = article.getElementsByTagName('span');
    var i, el, t;
    for (i = 0; i < els.length; i++) {
      el = els[i];
      if (el.children.length) continue;
      t = (el.textContent || '').toLowerCase();
      // 去空白：普通空格 / 不换行空格 / 制表 / 换行，统一后与无空格 label 比对
      t = t.replace(/[  \t\n\r]+/g, '');
      for (var k = 0; k < AI_LABELS.length; k++) {
        if (t.indexOf(AI_LABELS[k]) >= 0) return true;
      }
    }
    return false;
  }

  function isPromo(article) {
    // 已隐藏过则跳过
    if (article.dataset.xverseHidden) return false;
    // 排除 status 详情页主推文：保留豁免逻辑（详见 0.3.0 修复，防主推文被误判隐藏）。
    // 注意：placementTracking 是 x.com 所有视频/GIF 帖的播放器跟踪组件，与广告无关，
    // 不作为广告信号（曾导致时间线上纯视频/GIF 帖被误判为广告而遮罩）。
    if (isStatusMainTweet(article)) return false;
    // 命中标记：Ad/推广 徽标（任意位置精确匹配）。真广告必有「Ad」徽标。
    return hasAdLabel(article);
  }
  // status 详情页（/user/status/id）里的第一个 article 即主推文
  function isStatusMainTweet(article) {
    if (!/^https:\/\/x\.com\/[A-Za-z0-9_]+\/status\/\d+/.test(location.href)) return false;
    var m = document.querySelector('div[data-testid="primaryColumn"] article[data-testid="tweet"]');
    return m === article;
  }

  // 广告遮罩：visibility:hidden 保留占位（布局高度不变，零回流）+ 中央可点击提示卡片。
  // 不用 display:none —— 塌陷广告高度会拉动下方内容，造成返回/滚动跳动。
  // strip 模式（__xvFilterMode === 'strip'，由 anti-promo-strip.js 设置）：完全不加载广告，
  // 漏网广告（如变现视频）直接 display:none 移除（不留占位、无可点击验证，信任过滤结果）。
  // strip 模式下防跳动由数据层删除承担，mutation 兜底的 display:none 仅处理数据层漏网的极少数。
  var PLACEHOLDER_TXT = '检测到广告 · 点击查看';
  function hideArticle(article) {
    if (article.dataset.xverseHidden) return;
    if (window.__xvFilterMode === 'strip') {
      article.dataset.xverseHidden = '1';
      article.dataset.xverseStripAdHidden = '1';
      article.style.display = 'none';
      return;
    }
    __xvFilterCard.hide(article, PLACEHOLDER_TXT, 'xverseHidden');
  }

  // CC 视频独立遮罩：用独立 owner 标记 xverseCcHidden（区别于广告 xverseHidden），
  // 以便开关关闭时精确恢复「只被 CC 过滤的帖子」，不动广告帖。
  var CC_PLACEHOLDER_TXT = '带字幕（CC）视频 · 点击查看';
  function hideCcArticle(article) {
    if (article.dataset.xverseCcHidden) return;
    if (window.__xvFilterMode === 'strip') {
      article.dataset.xverseCcHidden = '1';
      article.style.display = 'none';
      return;
    }
    __xvFilterCard.hide(article, CC_PLACEHOLDER_TXT, 'xverseCcHidden');
  }

  // CC 过滤开关关闭时调用：恢复所有 xverseCcHidden 帖（清标记 + 复原样式 + 移除卡片）。
  // 只动 CC 自己的标记，广告帖（xverseHidden）不受影响。
  function revealCcArticles() {
    var arts = document.querySelectorAll('article[data-testid="tweet"]');
    var i, a;
    for (i = 0; i < arts.length; i++) {
      a = arts[i];
      if (!a.dataset.xverseCcHidden) continue;
      delete a.dataset.xverseCcHidden;
      if (window.__xvFilterMode === 'strip') {
        a.style.display = '';
      } else {
        a.style.visibility = '';
        if (a.__xvCard) { a.__xvCard.remove(); a.__xvCard = null; }
      }
      if (a.__xvRehide) { a.__xvRehide.remove(); a.__xvRehide = null; }
      delete a.dataset.xverseRevealed;
    }
  }
  // 暴露给原生侧开关热更新（applyCcFilterSetting(false) 时 evaluate 调用）
  window.__xvFilterCard.revealCc = revealCcArticles;

  // AI 生成标签帖独立遮罩：独立 owner 标记 xverseAiHidden（区别于广告/CC），
  // 开关关闭时精确恢复「只被 AI 过滤的帖子」，不动广告帖。
  var AI_PLACEHOLDER_TXT = 'AI 生成内容 · 点击查看';
  function hideAiArticle(article) {
    if (article.dataset.xverseAiHidden) return;
    if (window.__xvFilterMode === 'strip') {
      article.dataset.xverseAiHidden = '1';
      article.style.display = 'none';
      return;
    }
    __xvFilterCard.hide(article, AI_PLACEHOLDER_TXT, 'xverseAiHidden');
  }

  // AI 过滤开关关闭时调用：恢复所有 xverseAiHidden 帖。
  function revealAiArticles() {
    var arts = document.querySelectorAll('article[data-testid="tweet"]');
    var i, a;
    for (i = 0; i < arts.length; i++) {
      a = arts[i];
      if (!a.dataset.xverseAiHidden) continue;
      delete a.dataset.xverseAiHidden;
      if (window.__xvFilterMode === 'strip') {
        a.style.display = '';
      } else {
        a.style.visibility = '';
        if (a.__xvCard) { a.__xvCard.remove(); a.__xvCard = null; }
      }
      if (a.__xvRehide) { a.__xvRehide.remove(); a.__xvRehide = null; }
      delete a.dataset.xverseRevealed;
    }
  }
  // 暴露给原生侧开关热更新（applyAiFilterSetting(false) 时 evaluate 调用）
  window.__xvFilterCard.revealAi = revealAiArticles;

  function scan(container) {
    var articles = (container || document).querySelectorAll
      ? container.querySelectorAll('article[data-testid="tweet"]')
      : [];
    var i, article;
    for (i = 0; i < articles.length; i++) {
      article = articles[i];
      if (article.dataset.xverseHidden) continue;
      // AI 生成标签过滤（首次安装默认关）：开启后命中「Made with AI」标签 → 独立 owner 遮罩。
      // 与广告并存时广告优先（广告已遮罩则跳过，避免覆盖广告标记/卡片）。
      if (window.__xvFilterAi !== false && !article.dataset.xverseAiHidden &&
          !article.dataset.xverseCcHidden && !article.dataset.xverseRevealed &&
          hasAiLabel(article) && !isStatusMainTweet(article)) {
        hideAiArticle(article);
        continue;
      }
      if (!isPromo(article)) continue;
      hideArticle(article);
    }
  }

  function install() {
    // 主列未就绪（onPageStarted 时 body 常为 null）则重试，
    // 否则 observer.observe(null) 抛错、整个脚本中止（曾实测广告不隐藏）。
    var root = document.querySelector('div[data-testid="primaryColumn"]') || document.body;
    if (!root) return false;

    // strip 模式的 DOM 兜底：网络层漏网广告一旦识别，即使 React 后续重绘样式也不能复现。
    // data 标记只由 strip 分支写入，不影响 mask 模式的可点击占位卡片。
    if (window.__xvFilterMode === 'strip' && !document.getElementById('xverse-strip-ad-lock')) {
      var lockStyle = document.createElement('style');
      lockStyle.id = 'xverse-strip-ad-lock';
      lockStyle.textContent = 'article[data-xverse-strip-ad-hidden="1"]{display:none!important;}';
      (document.head || document.documentElement).appendChild(lockStyle);
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
    observer.observe(root, { childList: true, subtree: true });

    // 初始扫描
    scan(document);

    // 轮询兜底（虚拟列表可能绕过 observer 卸载节点）
    var iv = setInterval(function () {
      if (document.querySelector('div[data-testid="primaryColumn"]')) scan(document);
    }, 3000);

    // 实时贴片广告检测：视频播放器左下角出现「视频将在广告后播放」→ 该帖是变现视频
    // （X 在前贴片插广告、与创作者分成）。这类帖 DOM 结构与普通视频完全相同（amplify 海报、
    // 无 Ad 徽标），只有播放期瞬时的「广告后播放」文案可区分 → 必须高频轮询播放器文本。
    // 只匹配实测扫到的两条文案（中/英），不广撒网，避免误伤正常视频：
    //  中文「视频将在广告后播放」（ESPN 等实测）、英文「video will play after ad」（用户切英实测）。
    // 大小写不敏感（i），故 "Video will play after ad" 亦命中；"video will play after ad" 作为
    // 子串天然覆盖 "The video will play after ad" 等前缀变体。
    var ADPLAY_RE = /(视频将在广告后播放|video will play after ad)/i;
    var playerIv = setInterval(function () {
      var vids = document.querySelectorAll('article[data-testid="tweet"] video');
      var i, v, art, root, els, j, e, t;
      for (i = 0; i < vids.length; i++) {
        v = vids[i];
        art = v.closest ? v.closest('article[data-testid="tweet"]') : null;
        if (!art || art.dataset.xverseHidden || art.dataset.xverseUserHidden || art.dataset.xverseAiHidden || art.dataset.xverseRevealed) continue;
        // 带字幕（CC）视频过滤（广告过滤子项，设置「过滤带字幕（CC）视频」开关）：
        // 检测信号 video.textTracks.length > 0 —— 普通视频无字幕轨，CC 视频
        // （如译制/字幕视频）在播放器初始化后即有 subtitles/captions 轨。
        // 不依赖播放状态（播放器 [Hide captions] 按钮只在播放中渲染，textTracks 始终在）。
        // 开关标记 __xvFilterCc 默认过滤（undefined）；关闭时原生热更新标记 + revealCc()
        // 恢复已隐藏帖。已隐藏（xverseCcHidden）帖跳过重复遮罩。
        // 详情页主推文豁免：用户在详情页正观看该视频时不应被遮罩。
        if (window.__xvFilterCc !== false && !art.dataset.xverseCcHidden) {
          var tt = null;
          try { tt = v.textTracks; } catch (e) {}
          if (tt && tt.length > 0 && !isStatusMainTweet(art)) {
            hideCcArticle(art);
            continue;
          }
        }
        root = art.querySelector('div[data-testid="videoPlayer"]') || art;
        els = root.querySelectorAll('div,span');
        for (j = 0; j < els.length; j++) {
          e = els[j];
          if (e.children.length) continue;
          t = (e.textContent || '').trim();
          if (!t || t.length > 40) continue;
          if (ADPLAY_RE.test(t)) {
            hideArticle(art);
            break;
          }
        }
      }
    }, 800);

    // 页面卸载时断开 observer 并清定时器，避免 WebView 页面进入缓存后仍持有整棵 DOM。
    window.addEventListener('pagehide', function () {
      observer.disconnect();
      clearInterval(iv);
      clearInterval(playerIv);
    }, { once: true });
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
