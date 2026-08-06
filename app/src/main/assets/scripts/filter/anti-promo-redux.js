// 过滤第 3 层：Redux 拦截（最彻底，尽力而为）
// document_start 覆写 React Store 的 dispatch，渲染前剔除 Promoted 条目。
// x.com 改版频繁，本层独立 try/catch，失败自动降级回 CSS 层。
(function () {
  'use strict';
  try {
    // 记录是否成功接管
    var ok = false;

    function isPromotedEntry(obj) {
      if (!obj || typeof obj !== 'object') return false;
      var tn = obj.__typename;
      if (typeof tn === 'string' && tn.indexOf('Promoted') >= 0) return true;
      if (obj.advertiserAccount) return true;
      return false;
    }

    // 递归清洗：剔除含 Promoted 且带 advertiserAccount 的条目
    function sanitize(value, depth) {
      if (depth > 6) return value;
      if (Array.isArray(value)) {
        for (var i = 0; i < value.length; i++) {
          var e = value[i];
          if (isPromotedEntry(e)) {
            value.splice(i, 1);
            i--;
          } else {
            sanitize(e, depth + 1);
          }
        }
        return value;
      }
      if (value && typeof value === 'object') {
        Object.keys(value).forEach(function (k) {
          var v = value[k];
          if (isPromotedEntry(v)) {
            delete value[k];
          } else {
            sanitize(v, depth + 1);
          }
        });
        return value;
      }
      return value;
    }

    // 目标：找到 React root 上的 dispatch
    function findDispatch(root) {
      var key = Object.keys(root).find(function (k) {
        return k.indexOf('__reactFiber$') === 0 ||
               k.indexOf('__reactInternalInstance$') === 0;
      });
      if (!key) return null;
      var fiber = root[key];
      var node = fiber;
      // 向上找有 memoizedState / store 的节点
      var guard = 0;
      while (node && guard < 30) {
        var m = node.memoizedState;
        if (m && m.dispatch && m.queue && m.queue.dispatch) {
          return { dispatch: m.queue.dispatch, state: m };
        }
        node = node.return || node.return_memoizedProps || null;
        guard++;
      }
      return null;
    }

    // 接管 dispatch：每次 action 后清洗 store 根 state
    function hijack(root) {
      var ctx = findDispatch(root);
      if (!ctx) return false;
      var original = ctx.dispatch;
      if (original.__xverseHijacked) return true;
      ctx.dispatch = function (action) {
        var result = original(action);
        try {
          var s = ctx.state.memoizedState;
          if (s && s.timeline) sanitize(s.timeline, 0);
          if (s && s.entities) sanitize(s.entities, 0);
        } catch (e) {}
        return result;
      };
      ctx.dispatch.__xverseHijacked = true;
      return true;
    }

    // 轮询寻找 React root（React 挂载有延迟）
    var attempts = 0;
    var iv = setInterval(function () {
      attempts++;
      var app = document.querySelector('#react-root') ||
                document.querySelector('#root') ||
                document.body;
      if (app && hijack(app)) {
        ok = true;
        clearInterval(iv);
        return;
      }
      if (attempts > 30) clearInterval(iv); // 3s 内没接管则放弃，降级 CSS
    }, 100);
  } catch (e) {
    // 本层失败不影响前两层
  }
})();
