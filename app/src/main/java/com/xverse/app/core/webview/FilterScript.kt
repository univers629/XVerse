package com.xverse.app.core.webview

import android.content.Context
import com.xverse.app.core.data.db.FilterRule
import com.xverse.app.core.data.db.RuleType
import com.xverse.app.core.log.LogStore

/**
 * 过滤规则组装：内置 assets 脚本 + 用户自定义规则 + 远程规则。
 * 输出为可注入的 JS 文本（内联 IIFE，工程规范 #1）。
 */
class FilterScript(private val context: Context) {

    /**
     * 内置脚本（document_start 注入）。
     * 过滤方式决定注入组合：
     *  - mask（默认）：CSS + Mutation，广告进 DOM 后遮罩为可点击占位卡片（防误屏蔽）；
     *  - strip：CSS + strip + Mutation。strip 在 GraphQL 数据层删除广告条目（广告根本不进 DOM），
     *    Mutation 兜底处理遗漏/新形式广告。
     * [ccVideos]：过滤带字幕（CC）视频（广告过滤子项）。mutation 层默认即过滤（不注入也能过滤），
     * 关闭时需注入标记关闭——不依赖此注入值，仅用于 BrowserViewModel 决定是否注入关闭标记。
     * [aiLabel]：过滤 AI 生成标签（Made with AI，广告过滤子项）。同样默认过滤，关闭时注入关闭标记。
     */
    fun builtinEarlyScripts(mode: String = "mask", ccVideos: Boolean = true, aiLabel: Boolean = true): List<String> {
        val scripts = mutableListOf<String>()
        // CC/AI 过滤默认开：mutation 层不注入标记即过滤（window.__xvFilterCc/__xvFilterAi 未定义）
        if (!ccVideos) scripts.add(ccDisabledScript()) // 关闭才注入标记（原始 JS 文本，不经 loadAsset）
        if (!aiLabel) scripts.add(aiDisabledScript())
        val paths = mutableListOf("scripts/filter/anti-promo-css.js")
        if (mode == "strip") paths.add("scripts/filter/anti-promo-strip.js")
        paths.add("scripts/filter/anti-promo-mutation.js")
        scripts.addAll(paths.mapNotNull(::loadAsset))
        return scripts
    }

    /** 由用户规则生成 JS（追加到 mutation 层：REGEX 关键词/用户名） */
    fun userRuleScript(rules: List<FilterRule>): String {
        val regexRules = rules.filter { it.type == RuleType.REGEX && it.enabled }
        if (regexRules.isEmpty()) return ""
        val patterns = regexRules.map { it.pattern }
            .filter { it.isNotBlank() }
            .joinToString("|") { escapeRegex(it) }
        if (patterns.isBlank()) return ""
        return """
            // 用户自定义过滤规则
            (function(){
              'use strict';
              // 依赖 mutation 层注入的共享卡片工具 window.__xvFilterCard（加载顺序保证在其后）
              if (!window.__xvFilterCard) return;
              var re = new RegExp('${escapeJs(patterns)}', 'i');
              var iv = setInterval(function(){
                var arts = document.querySelectorAll('article[data-testid="tweet"]');
                var i, a, t, u;
                for (i = 0; i < arts.length; i++) {
                  a = arts[i];
                  // 已遮罩（广告层或本层）则跳过；命中规则 → 复用共享卡片（可点击验证）
                  if (a.dataset.xverseUserHidden || a.dataset.xverseHidden) continue;
                  t = (a.innerText || '');
                  u = '';
                  var links = a.querySelectorAll('a[href^="/"]');
                  var j, href, m;
                  for (j = 0; j < links.length; j++) {
                    href = links[j].getAttribute('href') || '';
                    m = href.match(/^\/([A-Za-z0-9_]+)$/);
                    if (m) u += ' ' + m[1];
                  }
                  if (re.test(t) || re.test(u)) {
                    window.__xvFilterCard.hide(a, '命中屏蔽词 · 点击查看', 'xverseUserHidden');
                  }
                }
              }, 2000);
              window.addEventListener('pagehide', function(){ clearInterval(iv); });
            })();
        """.trimIndent()
    }

    /** 用户自定义 CSS 规则 */
    fun userCss(rules: List<FilterRule>): String {
        val cssRules = rules.filter { it.type == RuleType.CSS && it.enabled }
            .map { it.pattern.trim() }
            .filter { it.isNotBlank() }
        if (cssRules.isEmpty()) return ""
        // 双引号/反斜杠需转义，避免破坏外层 JS 字符串字面量
        val joined = cssRules.joinToString("\\n") { escapeJs(it) }
        return """
            (function(){
              'use strict';
              var s = document.createElement('style');
              s.textContent = "$joined";
              (document.head || document.documentElement).appendChild(s);
            })();
        """.trimIndent()
    }

    /** 组装全部 early 脚本（含规则），返回注入列表 */
    fun buildEarlyScripts(userRules: List<FilterRule>, mode: String = "mask", ccVideos: Boolean = true, aiLabel: Boolean = true): List<String> {
        val scripts = builtinEarlyScripts(mode, ccVideos, aiLabel).toMutableList()
        val userScript = userRuleScript(userRules)
        if (userScript.isNotBlank()) scripts.add(userScript)
        val userCssScript = userCss(userRules)
        if (userCssScript.isNotBlank()) scripts.add(userCssScript)
        return scripts
    }

    /**
     * CC 视频过滤关闭标记（document_start 注入）。
     * 仅关闭时注入（mutation 层默认开启过滤）：置 window.__xvFilterCc = false，
     * mutation 层播放器轮询读到后跳过 CC 检测。开关热更新走 evaluate 通道，
     * 不重建注入（检测在已注入脚本内、运行时读标记）。
     */
    private fun ccDisabledScript(): String = """
        // CC 视频过滤已关闭（设置 → 过滤 → 过滤带字幕（CC）视频）
        window.__xvFilterCc = false;
    """.trimIndent()

    /**
     * AI 生成标签过滤关闭标记（document_start 注入）。
     * 仅关闭时注入（mutation 层默认开启过滤）：置 window.__xvFilterAi = false，
     * mutation 层 scan 读到后跳过 AI 标签检测。开关热更新走 evaluate 通道。
     */
    private fun aiDisabledScript(): String = """
        // AI 生成标签过滤已关闭（设置 → 过滤 → 过滤 AI 生成标签）
        window.__xvFilterAi = false;
    """.trimIndent()

    private fun loadAsset(path: String): String? {
        return try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            LogStore.error("加载脚本资产失败: $path", e)
            null
        }
    }

    companion object {
        private fun escapeRegex(s: String): String {
            return s.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("*", ".*")
        }

        private fun escapeJs(s: String): String {
            return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
        }
    }
}
