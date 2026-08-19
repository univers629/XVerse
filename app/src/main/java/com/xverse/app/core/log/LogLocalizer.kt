package com.xverse.app.core.log

/**
 * 实时多语言日志本地化处理器：
 * 将底层记录的日志格式实时转换为当前界面语言（中文 / English / 日本語）。
 */
object LogLocalizer {

    private data class PatternRule(
        val regex: Regex,
        val zh: (MatchResult) -> String,
        val en: (MatchResult) -> String,
        val ja: (MatchResult) -> String,
    )

    private val rules: List<PatternRule> = listOf(
        // Service Worker
        PatternRule(
            Regex("""(?i).*Service Worker.*(?:ad request blocker installed|广告请求拦截器已安装|広告リクエストブロッカー).*"""),
            zh = { "Service Worker 广告请求拦截器已安装" },
            en = { "Service Worker ad request blocker installed" },
            ja = { "Service Worker 広告リクエストブロッカーがインストールされました" },
        ),
        // XWebView init
        PatternRule(
            Regex("""(?i)XWebView (?:initialized, WebView version|初始化，WebView 版本|初期化完了、WebView バージョン)[:：]\s*(.*)"""),
            zh = { "XWebView 初始化，WebView 版本: ${it.groupValues[1]}" },
            en = { "XWebView initialized, WebView version: ${it.groupValues[1]}" },
            ja = { "XWebView 初期化完了、WebView バージョン: ${it.groupValues[1]}" },
        ),
        // WebAuthn
        PatternRule(
            Regex("""(?i)(?:Enable WebAuthn failed|启用 WebAuthn 失败|WebAuthn 有効化失敗)[:：]\s*(.*)"""),
            zh = { "启用 WebAuthn 失败: ${it.groupValues[1]}" },
            en = { "Enable WebAuthn failed: ${it.groupValues[1]}" },
            ja = { "WebAuthn 有効化失敗: ${it.groupValues[1]}" },
        ),
        // Native document_start registered
        PatternRule(
            Regex("""(?i)WebView (?:native document_start registered|原生 document_start 已注册|ネイティブ document_start 登録済み)\s*x(\d+)"""),
            zh = { "WebView 原生 document_start 已注册 x${it.groupValues[1]}" },
            en = { "WebView native document_start registered x${it.groupValues[1]}" },
            ja = { "WebView ネイティブ document_start 登録済み x${it.groupValues[1]}" },
        ),
        // Native document_start executed before page
        PatternRule(
            Regex("""(?i)Native document_start executed before page x(\d+)\s*->\s*(.*)"""),
            zh = { "原生 document_start 已先于页面执行 x${it.groupValues[1]} → ${it.groupValues[2]}" },
            en = { "Native document_start executed before page x${it.groupValues[1]} -> ${it.groupValues[2]}" },
            ja = { "ネイティブ document_start がページより先に実行 x${it.groupValues[1]} → ${it.groupValues[2]}" },
        ),
        // WebView fallback early injection
        PatternRule(
            Regex("""(?i)WebView lacks native document_start, falling back to early injection x(\d+)\s*->\s*(.*)"""),
            zh = { "WebView 不支持原生 document_start，回退 early 注入 x${it.groupValues[1]} → ${it.groupValues[2]}" },
            en = { "WebView lacks native document_start, falling back to early injection x${it.groupValues[1]} -> ${it.groupValues[2]}" },
            ja = { "WebView はネイティブ document_start 非対応、early 注入にフォールバック x${it.groupValues[1]} → ${it.groupValues[2]}" },
        ),
        // Early bundle injected
        PatternRule(
            Regex("""(?i)Injected extension early bundle\s*->\s*(.*)"""),
            zh = { "注入扩展 early bundle → ${it.groupValues[1]}" },
            en = { "Injected extension early bundle -> ${it.groupValues[1]}" },
            ja = { "拡張機能 early bundle を注入 → ${it.groupValues[1]}" },
        ),
        // Late script injected
        PatternRule(
            Regex("""(?i)Injected late scripts x(\d+)\s*->\s*(.*)"""),
            zh = { "注入 late 脚本 x${it.groupValues[1]} → ${it.groupValues[2]}" },
            en = { "Injected late scripts x${it.groupValues[1]} -> ${it.groupValues[2]}" },
            ja = { "late スクリプトを注入 x${it.groupValues[1]} → ${it.groupValues[2]}" },
        ),
        // Late bundle injected
        PatternRule(
            Regex("""(?i)Injected extension late bundle\s*->\s*(.*)"""),
            zh = { "注入扩展 late bundle → ${it.groupValues[1]}" },
            en = { "Injected extension late bundle -> ${it.groupValues[1]}" },
            ja = { "拡張機能 late bundle を注入 → ${it.groupValues[1]}" },
        ),
        // Ad/tracking blocker enabled
        PatternRule(
            Regex("""(?i)(?:Web ad/tracking blocker (?:enabled|disabled)|网页广告/跟踪拦截(?:开启|关闭)|Web広告/トラッキングブロック(?:有効|無効))\s*[（\(](?:custom=|自定义\s*)?([^,，]+)[,，]\s*(?:extDefault=|扩展默认\s*)?([^,，]+)[,，]\s*strip=([^）\)]+)[）\)]"""),
            zh = { "网页广告/跟踪拦截开启（自定义${it.groupValues[1].trim()}，扩展默认${it.groupValues[2].trim()}，strip=${it.groupValues[3].trim()}）" },
            en = { "Web ad/tracking blocker enabled (custom=${it.groupValues[1].trim()}, extDefault=${it.groupValues[2].trim()}, strip=${it.groupValues[3].trim()})" },
            ja = { "Web広告/トラッキングブロック有効 (カスタム${it.groupValues[1].trim()}、拡張デフォルト${it.groupValues[2].trim()}、strip=${it.groupValues[3].trim()})" },
        ),
        // Filter script ready
        PatternRule(
            Regex("""(?i)(?:Filter script ready|过滤脚本就绪|フィルタースクリプト準備完了)\s*[（\(](?:mode[:=\s]*|模式\s*)?([^,，]+)[,，]\s*(?:cc[:=\s]*|CC\s*视频\s*)?([^,，]+)[,，]\s*(?:ai[:=\s]*|AI\s*标签\s*)?([^）\)]+)[）\)]:?\s*(?:builtin\+user[:=\s]*|内置\s*\+\s*用户规则\s*|組み込み\s*\+\s*ユーザー\s*)([^,，]+)[,，]\s*(?:integrated[:=\s]*|集成规则\s*|統合\s*)(.+)"""),
            zh = { "过滤脚本就绪（模式 ${it.groupValues[1].trim()}，CC视频 ${it.groupValues[2].trim()}，AI标签 ${it.groupValues[3].trim()}）：内置+用户规则 ${it.groupValues[4].trim()}，集成规则 ${it.groupValues[5].trim()}" },
            en = { "Filter script ready (mode=${it.groupValues[1].trim()}, cc=${it.groupValues[2].trim()}, ai=${it.groupValues[3].trim()}): builtin+user=${it.groupValues[4].trim()}, integrated=${it.groupValues[5].trim()}" },
            ja = { "フィルタースクリプト準備完了 (モード${it.groupValues[1].trim()}、CC動画${it.groupValues[2].trim()}、AIラベル${it.groupValues[3].trim()}): 組み込み+ユーザー${it.groupValues[4].trim()}、統合${it.groupValues[5].trim()}" },
        ),
        // Extension injection updated
        PatternRule(
            Regex("""(?i)(?:Extension injection updated|扩展注入已更新|拡張機能注入更新)[:：]\s*x(\d+)"""),
            zh = { "扩展注入已更新：x${it.groupValues[1]}" },
            en = { "Extension injection updated: x${it.groupValues[1]}" },
            ja = { "拡張機能注入を更新: x${it.groupValues[1]}" },
        ),
        // Filter rules hot-reloaded
        PatternRule(
            Regex("""(?i)(?:Filter rules hot-reloaded|过滤规则已热更新|フィルタールールをホット更新)[:：]\s*x(\d+)"""),
            zh = { "过滤规则已热更新：x${it.groupValues[1]}" },
            en = { "Filter rules hot-reloaded: x${it.groupValues[1]}" },
            ja = { "フィルタールールをホット更新: x${it.groupValues[1]}" },
        ),
        // Filter mode changed
        PatternRule(
            Regex("""(?i).*Filter mode changed.*|.*过滤方式变更.*|.*ブロック方式変更.*"""),
            zh = { "过滤方式变更：重建注入并重新加载页面" },
            en = { "Filter mode changed: rebuilding injection and reloading page" },
            ja = { "ブロック方式変更: 注入再構築とページ再読み込み" },
        ),
        // CC video filter flag
        PatternRule(
            Regex("""(?i)CC (?:video filter|视频过滤|動画フィルター)\s*(ON|OFF|开|关|有効|無効).*"""),
            zh = {
                val state = if (it.groupValues[1].equals("OFF", true) || it.groupValues[1] == "关" || it.groupValues[1] == "無効") "关" else "开"
                "CC 视频过滤 $state（热更新标记，不重载）"
            },
            en = {
                val state = if (it.groupValues[1].equals("OFF", true) || it.groupValues[1] == "关" || it.groupValues[1] == "無効") "OFF" else "ON"
                "CC video filter $state (hot-updated flag, no reload)"
            },
            ja = {
                val state = if (it.groupValues[1].equals("OFF", true) || it.groupValues[1] == "关" || it.groupValues[1] == "無効") "OFF" else "ON"
                "CC 動画フィルター $state (ホット更新フラグ、再読み込みなし)"
            },
        ),
        // AI label filter flag
        PatternRule(
            Regex("""(?i)AI (?:label filter|标签过滤|ラベルフィルター)\s*(ON|OFF|开|关|有効|無効).*"""),
            zh = {
                val state = if (it.groupValues[1].equals("OFF", true) || it.groupValues[1] == "关" || it.groupValues[1] == "無効") "关" else "开"
                "AI 标签过滤 $state（热更新标记，不重载）"
            },
            en = {
                val state = if (it.groupValues[1].equals("OFF", true) || it.groupValues[1] == "关" || it.groupValues[1] == "無効") "OFF" else "ON"
                "AI label filter $state (hot-updated flag, no reload)"
            },
            ja = {
                val state = if (it.groupValues[1].equals("OFF", true) || it.groupValues[1] == "关" || it.groupValues[1] == "無効") "OFF" else "ON"
                "AI ラベルフィルター $state (ホット更新フラグ、再読み込みなし)"
            },
        ),
        // Filter disabled
        PatternRule(
            Regex("""(?i).*Filter disabled, skipping injection.*|.*过滤已关闭.*|.*フィルター無効.*"""),
            zh = { "过滤已关闭，跳过注入" },
            en = { "Filter disabled, skipping injection" },
            ja = { "フィルター無効、注入をスキップ" },
        ),
        // Native blocked request
        PatternRule(
            Regex("""(?i)(?:Native blocked ad/tracking request|原生阻断广告/追踪请求|ネイティブ広告/トラッキングリクエストをブロック)[:：]\s*(.*)"""),
            zh = { "原生阻断广告/追踪请求: ${it.groupValues[1]}" },
            en = { "Native blocked ad/tracking request: ${it.groupValues[1]}" },
            ja = { "ネイティブ広告/トラッキングリクエストをブロック: ${it.groupValues[1]}" },
        ),
        // Start download
        PatternRule(
            Regex("""(?i)(?:Start download|开始下载|ダウンロード開始)[:：]\s*(.*)"""),
            zh = { "开始下载: ${it.groupValues[1]}" },
            en = { "Start download: ${it.groupValues[1]}" },
            ja = { "ダウンロード開始: ${it.groupValues[1]}" },
        ),
        // Download completed
        PatternRule(
            Regex("""(?i)(?:Download completed|下载完成|ダウンロード完了)[:：]\s*(.*?)\s*[（\(](.*)[）\)]"""),
            zh = { "下载完成: ${it.groupValues[1]}（${it.groupValues[2]}）" },
            en = { "Download completed: ${it.groupValues[1]} (${it.groupValues[2]})" },
            ja = { "ダウンロード完了: ${it.groupValues[1]} (${it.groupValues[2]})" },
        ),
        // Download interrupted
        PatternRule(
            Regex("""(?i)(?:Download interrupted|下载中断|ダウンロード中断)[:：]\s*(.*?)\s*[（\(](.*)[）\)]"""),
            zh = { "下载中断: ${it.groupValues[1]}（${it.groupValues[2]}）" },
            en = { "Download interrupted: ${it.groupValues[1]} (${it.groupValues[2]})" },
            ja = { "ダウンロード中断: ${it.groupValues[1]} (${it.groupValues[2]})" },
        ),
        // File complete 416
        PatternRule(
            Regex("""(?i)(?:File already complete \(416\), skipping|文件已完整（416），跳过|ファイルは既に完全 \(416\)、スキップ)[:：]\s*(.*)"""),
            zh = { "文件已完整（416），跳过: ${it.groupValues[1]}" },
            en = { "File already complete (416), skipping: ${it.groupValues[1]}" },
            ja = { "ファイルは既に完全 (416)、スキップ: ${it.groupValues[1]}" },
        ),
        // Queued
        PatternRule(
            Regex("""(?i)(?:Queued|已入队|キューに追加)[:：]\s*(.*?)\s*[（\(](.*)[）\)]"""),
            zh = { "已入队: ${it.groupValues[1]}（${it.groupValues[2]}）" },
            en = { "Queued: ${it.groupValues[1]} (${it.groupValues[2]})" },
            ja = { "キューに追加: ${it.groupValues[1]} (${it.groupValues[2]})" },
        ),
        // Extension URL queued
        PatternRule(
            Regex("""(?i)(?:Extension URL queued|扩展直链已入队|拡張機能直リンクをキューに追加)[:：]\s*(.*)"""),
            zh = { "扩展直链已入队: ${it.groupValues[1]}" },
            en = { "Extension URL queued: ${it.groupValues[1]}" },
            ja = { "拡張機能直リンクをキューに追加: ${it.groupValues[1]}" },
        ),
        // Extension direct save
        PatternRule(
            Regex("""(?i)(?:Extension direct save|扩展直存|拡張機能直接保存)[:：]\s*(.*?)\s*[（\(](.*)[）\)]"""),
            zh = { "扩展直存: ${it.groupValues[1]}（${it.groupValues[2]}）" },
            en = { "Extension direct save: ${it.groupValues[1]} (${it.groupValues[2]})" },
            ja = { "拡張機能直接保存: ${it.groupValues[1]} (${it.groupValues[2]})" },
        ),
        // Extension direct save failed
        PatternRule(
            Regex("""(?i)(?:Extension direct save failed|扩展直存失败|拡張機能直接保存失敗)[:：]\s*(.*)"""),
            zh = { "扩展直存失败: ${it.groupValues[1]}" },
            en = { "Extension direct save failed: ${it.groupValues[1]}" },
            ja = { "拡張機能直接保存失敗: ${it.groupValues[1]}" },
        ),
        // Failed to resolve view URI
        PatternRule(
            Regex("""(?i)(?:Failed to resolve view URI|解析查看 URI 失败|閲覧用 URI の解決に失敗)[:：]\s*(.*?)\s*[（\(](.*)[）\)]"""),
            zh = { "解析查看 URI 失败: ${it.groupValues[1]}（${it.groupValues[2]}）" },
            en = { "Failed to resolve view URI: ${it.groupValues[1]} (${it.groupValues[2]})" },
            ja = { "閲覧用 URI の解決に失敗: ${it.groupValues[1]} (${it.groupValues[2]})" },
        ),
        // Task not found
        PatternRule(
            Regex("""(?i)(?:Task not found, skipping|任务不存在，跳过|タスクが存在しません、スキップ)[:：]\s*(.*)"""),
            zh = { "任务不存在，跳过: ${it.groupValues[1]}" },
            en = { "Task not found, skipping: ${it.groupValues[1]}" },
            ja = { "タスクが存在しません、スキップ: ${it.groupValues[1]}" },
        ),
        // Worker started
        PatternRule(
            Regex("""(?i)(?:Worker started|Worker 启动|Worker 開始)[:：]\s*(.*)"""),
            zh = { "Worker 启动: ${it.groupValues[1]}" },
            en = { "Worker started: ${it.groupValues[1]}" },
            ja = { "Worker 開始: ${it.groupValues[1]}" },
        ),
        // Parsing tweet media
        PatternRule(
            Regex("""(?i)(?:Parsing tweet media|解析推文媒体|ツイートメディアを解析)[:：]\s*(.*)"""),
            zh = { "解析推文媒体: ${it.groupValues[1]}" },
            en = { "Parsing tweet media: ${it.groupValues[1]}" },
            ja = { "ツイートメディアを解析: ${it.groupValues[1]}" },
        ),
        // Parsed N media items
        PatternRule(
            Regex("""(?i)(?:Parsed (\d+) media items|解析到 (\d+) 个媒体|(\d+) 件のメディアを解析)"""),
            zh = {
                val n = it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } }
                "解析到 $n 个媒体"
            },
            en = {
                val n = it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } }
                "Parsed $n media items"
            },
            ja = {
                val n = it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } }
                "$n 件のメディアを解析"
            },
        ),
        // GraphQL cached
        PatternRule(
            Regex("""(?i)GraphQL (?:cached (\d+) media URLs|缓存 (\d+) 个媒体直链|キャッシュ (\d+) 件のメディア直リンク)\s*[（\(]tweet (.*)[）\)]"""),
            zh = {
                val n = it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } }
                "GraphQL 缓存 $n 个媒体直链（tweet ${it.groupValues[4]}）"
            },
            en = {
                val n = it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } }
                "GraphQL cached $n media URLs (tweet ${it.groupValues[4]})"
            },
            ja = {
                val n = it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } }
                "GraphQL キャッシュ $n 件のメディア直リンク (tweet ${it.groupValues[4]})"
            },
        ),
        // Hit GraphQL cache
        PatternRule(
            Regex("""(?i)(?:Hit GraphQL cache (\d+) items|命中 GraphQL 缓存 (\d+) 个|GraphQL キャッシュヒット (\d+) 件)\s*[（\(]tweet (.*)[）\)]"""),
            zh = {
                val n = it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } }
                "命中 GraphQL 缓存 $n 个（tweet ${it.groupValues[4]}）"
            },
            en = {
                val n = it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } }
                "Hit GraphQL cache $n items (tweet ${it.groupValues[4]})"
            },
            ja = {
                val n = it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } }
                "GraphQL キャッシュヒット $n 件 (tweet ${it.groupValues[4]})"
            },
        ),
        // Failed to fetch tweet page
        PatternRule(
            Regex("""(?i).*Failed to fetch tweet page.*|.*拉取推文页失败.*|.*ツイートページの取得に失敗.*"""),
            zh = { "拉取推文页失败" },
            en = { "Failed to fetch tweet page" },
            ja = { "ツイートページの取得に失敗" },
        ),
        // UserScript @require downloaded
        PatternRule(
            Regex("""(?i)UserScript @require (?:downloaded|已下载|ダウンロード完了)[:：]\s*(.*?)\s*[（\(](.*)[）\)]"""),
            zh = { "用户脚本 @require 已下载: ${it.groupValues[1]}（${it.groupValues[2]}）" },
            en = { "UserScript @require downloaded: ${it.groupValues[1]} (${it.groupValues[2]})" },
            ja = { "ユーザースクリプト @require ダウンロード完了: ${it.groupValues[1]} (${it.groupValues[2]})" },
        ),
        // UserScript @require failed/skipped
        PatternRule(
            Regex("""(?i)UserScript @require (?:failed/skipped|下载失败/跳过|失敗/スキップ)[:：]\s*(.*)"""),
            zh = { "用户脚本 @require 下载失败/跳过: ${it.groupValues[1]}" },
            en = { "UserScript @require failed/skipped: ${it.groupValues[1]}" },
            ja = { "ユーザースクリプト @require 失敗/スキップ: ${it.groupValues[1]}" },
        ),
        // UserScript imported
        PatternRule(
            Regex("""(?i)(?:UserScript imported|用户脚本已导入|ユーザースクリプトをインポート)[:：]\s*(.*)"""),
            zh = { "用户脚本已导入: ${it.groupValues[1]}" },
            en = { "UserScript imported: ${it.groupValues[1]}" },
            ja = { "ユーザースクリプトをインポート: ${it.groupValues[1]}" },
        ),
        // Extension imported
        PatternRule(
            Regex("""(?i)(?:Extension imported|扩展已导入|拡張機能をインポート)[:：]\s*(.*)"""),
            zh = { "扩展已导入: ${it.groupValues[1]}" },
            en = { "Extension imported: ${it.groupValues[1]}" },
            ja = { "拡張機能をインポート: ${it.groupValues[1]}" },
        ),
        // Extension source corrected
        PatternRule(
            Regex("""(?i)(?:Corrected extension source to Edge|已修正扩展来源为 Edge|拡張機能ソースを Edge に修正)[:：]\s*(.*)"""),
            zh = { "已修正扩展来源为 Edge: ${it.groupValues[1]}" },
            en = { "Corrected extension source to Edge: ${it.groupValues[1]}" },
            ja = { "拡張機能ソースを Edge に修正: ${it.groupValues[1]}" },
        ),
        // Extension storage written
        PatternRule(
            Regex("""(?i)(?:Extension storage written|扩展存储已写|拡張機能ストレージ書き込み完了)[:：]\s*(.*)"""),
            zh = { "扩展存储已写: ${it.groupValues[1]}" },
            en = { "Extension storage written: ${it.groupValues[1]}" },
            ja = { "拡張機能ストレージ書き込み完了: ${it.groupValues[1]}" },
        ),
        // Extension data cleared
        PatternRule(
            Regex("""(?i)(?:Extension data cleared|扩展数据已清除|拡張機能データをクリア)[:：]\s*(.*)"""),
            zh = { "扩展数据已清除: ${it.groupValues[1]}" },
            en = { "Extension data cleared: ${it.groupValues[1]}" },
            ja = { "拡張機能データをクリア: ${it.groupValues[1]}" },
        ),
        // Extension import failed
        PatternRule(
            Regex("""(?i)(?:Extension import failed|扩展导入失败|拡張機能のインポート失敗)[:：]\s*(.*)"""),
            zh = { "扩展导入失败: ${it.groupValues[1]}" },
            en = { "Extension import failed: ${it.groupValues[1]}" },
            ja = { "拡張機能のインポート失敗: ${it.groupValues[1]}" },
        ),
        // Extension URL import failed
        PatternRule(
            Regex("""(?i)(?:Extension URL import failed|扩展链接导入失败|拡張機能 URL のインポート失敗)[:：]\s*(.*)"""),
            zh = { "扩展链接导入失败: ${it.groupValues[1]}" },
            en = { "Extension URL import failed: ${it.groupValues[1]}" },
            ja = { "拡張機能 URL のインポート失敗: ${it.groupValues[1]}" },
        ),
        // Saved account
        PatternRule(
            Regex("""(?i)(?:Saved account|已保存账户|保存済みアカウント)[:：]\s*@(.*)"""),
            zh = { "已保存账户：@${it.groupValues[1]}" },
            en = { "Saved account: @${it.groupValues[1]}" },
            ja = { "保存済みアカウント: @${it.groupValues[1]}" },
        ),
        // Switched account
        PatternRule(
            Regex("""(?i)(?:Switched account|已切换账户|アカウントを切り替えました)[:：]\s*@(.*)"""),
            zh = { "已切换账户：@${it.groupValues[1]}" },
            en = { "Switched account: @${it.groupValues[1]}" },
            ja = { "アカウントを切り替えました: @${it.groupValues[1]}" },
        ),
        // Removed account
        PatternRule(
            Regex("""(?i)(?:Removed account|已移除账户|アカウントを削除しました)[:：]\s*@(.*)"""),
            zh = { "已移除账户：@${it.groupValues[1]}" },
            en = { "Removed account: @${it.groupValues[1]}" },
            ja = { "アカウントを削除しました: @${it.groupValues[1]}" },
        ),
        // Logout
        PatternRule(
            Regex("""(?i).*Logout: cleared cookies.*|.*登出：清空 Cookie.*|.*ログアウト: Cookie を消去.*"""),
            zh = { "登出：清空 Cookie" },
            en = { "Logout: cleared cookies" },
            ja = { "ログアウト: Cookie を消去" },
        ),
        // Recorded tweet
        PatternRule(
            Regex("""(?i)(?:Recorded|已记录|記録完了)[:：]\s*@([^/]+)/(.*)"""),
            zh = { "已记录: @${it.groupValues[1]}/${it.groupValues[2]}" },
            en = { "Recorded: @${it.groupValues[1]}/${it.groupValues[2]}" },
            ja = { "記録完了: @${it.groupValues[1]}/${it.groupValues[2]}" },
        ),
        // Thumbnail updated
        PatternRule(
            Regex("""(?i)(?:Thumbnail updated|缩略图补写|サムネイル更新)[:：]\s*@([^/]+)/(.*)"""),
            zh = { "缩略图补写: @${it.groupValues[1]}/${it.groupValues[2]}" },
            en = { "Thumbnail updated: @${it.groupValues[1]}/${it.groupValues[2]}" },
            ja = { "サムネイル更新: @${it.groupValues[1]}/${it.groupValues[2]}" },
        ),
        // History cleanup
        PatternRule(
            Regex("""(?i)(?:History cleanup: expired|历史清理：过期|履歴クリーンアップ: 期限切れ)\s*(\d+).*?(?:limit exceeded|超上限|上限超過)\s*(\d+).*"""),
            zh = { "历史清理：过期 ${it.groupValues[1]} 条，超上限 ${it.groupValues[2]} 条" },
            en = { "History cleanup: expired ${it.groupValues[1]}, limit exceeded ${it.groupValues[2]}" },
            ja = { "履歴クリーンアップ: 期限切れ ${it.groupValues[1]} 件、上限超過 ${it.groupValues[2]} 件" },
        ),
        // Page started
        PatternRule(
            Regex("""(?i)(?:Page started|页面开始|ページ読み込み開始)[:：]\s*(.*)"""),
            zh = { "页面开始加载: ${it.groupValues[1]}" },
            en = { "Page started: ${it.groupValues[1]}" },
            ja = { "ページ読み込み開始: ${it.groupValues[1]}" },
        ),
        // Page finished
        PatternRule(
            Regex("""(?i)(?:Page finished|页面完成|ページ読み込み完了)[:：]\s*(.*)"""),
            zh = { "页面加载完成: ${it.groupValues[1]}" },
            en = { "Page finished: ${it.groupValues[1]}" },
            ja = { "ページ読み込み完了: ${it.groupValues[1]}" },
        ),
    )

    fun localize(message: String, lang: String): String {
        for (rule in rules) {
            val match = rule.regex.matchEntire(message) ?: rule.regex.find(message)
            if (match != null) {
                return when {
                    lang.startsWith("zh", ignoreCase = true) -> rule.zh(match)
                    lang.startsWith("ja", ignoreCase = true) -> rule.ja(match)
                    else -> rule.en(match)
                }
            }
        }
        return message
    }
}
