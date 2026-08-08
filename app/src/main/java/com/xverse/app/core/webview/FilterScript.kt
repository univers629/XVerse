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

    /** 内置三层脚本（document_start 注入） */
    private fun builtinEarlyScripts(): List<String> {
        return listOf(
            "scripts/filter/anti-promo-css.js",
            "scripts/filter/anti-promo-mutation.js",
            "scripts/filter/anti-promo-strip.js",
        ).mapNotNull(::loadAsset)
    }

    /** 内置 Redux 拦截脚本（document_start 注入，尽力而为） */
    private fun builtinReduxScript(): String? = loadAsset("scripts/filter/anti-promo-redux.js")

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
              var re = new RegExp('${escapeJs(patterns)}', 'i');
              var iv = setInterval(function(){
                var arts = document.querySelectorAll('article[data-testid="tweet"]');
                var i, a, t, u;
                for (i = 0; i < arts.length; i++) {
                  a = arts[i];
                  if (a.dataset.xverseUserHidden) continue;
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
                    a.style.display = 'none';
                    a.dataset.xverseUserHidden = '1';
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
    fun buildEarlyScripts(userRules: List<FilterRule>): List<String> {
        val scripts = builtinEarlyScripts().toMutableList()
        val userScript = userRuleScript(userRules)
        if (userScript.isNotBlank()) scripts.add(userScript)
        val userCssScript = userCss(userRules)
        if (userCssScript.isNotBlank()) scripts.add(userCssScript)
        return scripts
    }

    /** Redux 拦截脚本单独注册（尝试最后注入） */
    fun reduxScript(): String? = builtinReduxScript()

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
