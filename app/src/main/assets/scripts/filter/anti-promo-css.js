// 过滤第 1 层：CSS 注入
// 加载时隐藏趋势、推荐关注、侧栏，首屏即干净。
// 2026-08-08：不再用 :has(placementTracking) 隐藏广告帖——CSS 无法判断视口，
// 会把视野外广告也隐藏，其高度塌陷导致返回首页时布局跳动。
// 广告帖改由 mutation 层统一管理（visibility:hidden 保留占位，零回流，见 anti-promo-mutation.js）。
// 工程规范：内联 IIFE、行注释、不用 eval。
(function () {
  'use strict';
  try {
    // 常量规则：趋势 / 推荐关注 / 右侧边栏（与 URL 无关，常驻生效）
    var STATIC_RULES = [
      'div[aria-label="Timeline: Trending now"]{display:none!important;}',
      'aside[aria-label="Who to follow"]{display:none!important;}',
      'div[data-testid="sidebarColumn"]{display:none!important;}'
    ];
    var style = document.createElement('style');
    style.id = 'xverse-filter-css';
    style.textContent = STATIC_RULES.join('\n');
    (document.head || document.documentElement).appendChild(style);
  } catch (e) {}
})();
