package com.xverse.app.core.webview

/** 由原生外观设置控制的网页样式。 */
object WebAppearanceScript {

    private const val STYLE_ID = "xverse-hide-x-bottom-bar"

    /**
     * X 的移动端底栏和桌面侧栏复用 AppTabBar 测试标记，必须用手机视口媒体查询隔离。
     * 手机横屏时宽度可能超过 767px，因此同时按横屏低高度识别。
     * 脚本幂等：开启时创建或更新样式，关闭时立即移除。
     */
    fun hideXBottomBar(hidden: Boolean): String = """
        var styleId = '$STYLE_ID';
        if (${hidden.toString()}) {
          var mountTries = 0;
          (function mountStyle() {
            var oldStyle = document.getElementById(styleId);
            var style = oldStyle || document.createElement('style');
            style.id = styleId;
            style.textContent = '@media (max-width: 767px), (orientation: landscape) and (max-height: 767px) { nav:has(a[data-testid="AppTabBar_Home_Link"]) { display: none !important; } }';
            if (oldStyle) return;
            var root = document.head || document.documentElement;
            if (root) {
              root.appendChild(style);
            } else if (mountTries++ < 100) {
              setTimeout(mountStyle, 16);
            }
          })();
        } else {
          var oldStyle = document.getElementById(styleId);
          if (oldStyle) oldStyle.remove();
        }
    """.trimIndent()
}
