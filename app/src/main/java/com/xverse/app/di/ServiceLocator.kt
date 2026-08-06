package com.xverse.app.di

import android.content.Context
import com.xverse.app.core.data.db.AppDatabase
import com.xverse.app.core.data.db.FilterRule
import com.xverse.app.core.data.db.RuleType
import com.xverse.app.core.data.repo.DownloadRepo
import com.xverse.app.core.data.repo.FilterRepo
import com.xverse.app.core.data.repo.HistoryRepo
import com.xverse.app.core.data.repo.SettingsRepo
import com.xverse.app.core.auth.AuthController
import com.xverse.app.core.download.DownloadController
import com.xverse.app.core.download.DownloadNotifier
import com.xverse.app.core.download.Downloader
import com.xverse.app.core.download.MediaParser
import com.xverse.app.core.log.LogStore

/**
 * 手动 DI 容器：懒加载所有单例，跨层共享。
 * 工程规范：不引入 Hilt/KSP，保持轻量。
 */
class ServiceLocator(private val app: Context) {

    /** Application 上下文（供 FilterScript 等读取 assets） */
    val appContext: Context get() = app

    val settings: SettingsRepo by lazy { SettingsRepo(app) }

    val db: AppDatabase by lazy { AppDatabase.build(app) }

    val historyRepo: HistoryRepo by lazy { HistoryRepo(db.historyDao()) }

    val downloadRepo: DownloadRepo by lazy { DownloadRepo(db.downloadDao()) }

    val filterRepo: FilterRepo by lazy {
        FilterRepo(db.filterRuleDao()).also { ensureBuiltinRules(it) }
    }

    /** M3 下载三件套：解析 / 执行 / 通知 / 调度 */
    val mediaParser: MediaParser by lazy { MediaParser(app) }
    val downloader: Downloader by lazy { Downloader(app, downloadRepo) }
    val downloadNotifier: DownloadNotifier get() = DownloadNotifier

    val downloadController: DownloadController by lazy {
        DownloadController(app, downloadRepo, settings, mediaParser)
    }

    /** M4：Custom Tab 辅助登录 + 登出收口 */
    val authController: AuthController by lazy { AuthController(app) }

    init {
        LogStore.init(app)
        DownloadNotifier.ensureChannel(app)
    }

    /** WorkManager 启动的进程内取单例（Application 已初始化） */
    companion object {
        fun from(context: Context): ServiceLocator =
            com.xverse.app.AppInstance.locator
    }

    /** 首次启动写入内置过滤规则；已存在则按 version 升级（替换旧版） */
    private fun ensureBuiltinRules(repo: FilterRepo) {
        try {
            val builtins = listOf(
                FilterRule(
                    type = RuleType.REGEX,
                    pattern = "广告|推广|赞助|advertisement|sponsored|promoted",
                    enabled = true,
                    builtin = true,
                    source = "builtin",
                    version = "2",
                    description = "内置：推广关键词（中英）",
                ),
                FilterRule(
                    type = RuleType.CSS,
                    pattern = "article[data-testid=\"tweet\"]:has(div[data-testid=\"placementTracking\"])",
                    enabled = true,
                    builtin = true,
                    source = "builtin",
                    version = "1",
                    description = "内置：推广帖隐藏",
                ),
            )
            val existing = kotlinx.coroutines.runBlocking { repo.getBySource("builtin") }
            // 逐条按 version 升级：已有同 source+type 但 version 更低的 → 替换为新版
            builtins.forEach { b ->
                val current = existing.firstOrNull { it.type == b.type && it.source == b.source }
                if (current == null) {
                    kotlinx.coroutines.runBlocking { repo.insert(b) }
                } else if (current.version != b.version) {
                    kotlinx.coroutines.runBlocking { repo.insert(b.copy(id = current.id, enabled = current.enabled)) }
                }
            }
        } catch (e: Exception) {
            LogStore.error("初始化内置规则失败", e)
        }
    }
}
