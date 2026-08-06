// 过滤第 1 层：CSS 注入
// 加载时隐藏推广帖、趋势、推荐关注、侧栏，首屏即干净。
// 工程规范：内联 IIFE、行注释、不用 eval。
(function () {
  'use strict';
  try {
    var style = document.createElement('style');
    style.id = 'xverse-filter-css';
    style.textContent = [
      // 推广帖：含 placementTracking 标记的推文整条隐藏
      'article[data-testid="tweet"]:has(div[data-testid="placementTracking"]){display:none!important;}',
      'article[data-testid="tweet"] div[data-testid="placementTracking"]{display:none!important;}',
      // 趋势区
      'div[aria-label="Timeline: Trending now"]{display:none!important;}',
      // 推荐关注
      'aside[aria-label="Who to follow"]{display:none!important;}',
      // 右侧边栏
      'div[data-testid="sidebarColumn"]{display:none!important;}'
    ].join('\n');
    (document.head || document.documentElement).appendChild(style);
  } catch (e) {}
})();
