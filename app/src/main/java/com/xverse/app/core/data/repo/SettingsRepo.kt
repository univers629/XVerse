package com.xverse.app.core.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xverse.app.core.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * 设置仓库：DataStore 持久化所有偏好项。
 */
class SettingsRepo(private val context: Context) {

    private object Keys {
        val HISTORY_ENABLED = booleanPreferencesKey("history_enabled")

        val FILTER_ENABLED = booleanPreferencesKey("filter_enabled")
        val FILTER_MODE = stringPreferencesKey("filter_mode")       // mask=占位+可点击验证 / strip=完全不加载广告
        val FILTER_CC_VIDEOS = booleanPreferencesKey("filter_cc_videos") // 过滤带字幕（CC）视频
        val FILTER_AI_LABEL = booleanPreferencesKey("filter_ai_label") // 过滤 AI 生成标签（Made with AI）

        val DOWNLOAD_DIR = stringPreferencesKey("download_dir")
        val DOWNLOAD_FILE_TEMPLATE = stringPreferencesKey("download_file_template")
        val DOWNLOAD_NOTIFY = booleanPreferencesKey("download_notify")

        val THEME_MODE = stringPreferencesKey("theme_mode")          // system / light / dark
    }

    // ---- 历史 ----
    val historyEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.HISTORY_ENABLED] ?: true }

    suspend fun setHistoryEnabled(v: Boolean) = context.dataStore.edit { it[Keys.HISTORY_ENABLED] = v }

    // ---- 过滤 ----
    val filterEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.FILTER_ENABLED] ?: true }
    val filterMode: Flow<String> = context.dataStore.data.map { it[Keys.FILTER_MODE] ?: "mask" }
    val filterCcVideos: Flow<Boolean> = context.dataStore.data.map { it[Keys.FILTER_CC_VIDEOS] ?: true }
    val filterAiLabel: Flow<Boolean> = context.dataStore.data.map { it[Keys.FILTER_AI_LABEL] ?: true }

    suspend fun setFilterEnabled(v: Boolean) = context.dataStore.edit { it[Keys.FILTER_ENABLED] = v }
    suspend fun setFilterMode(v: String) = context.dataStore.edit { it[Keys.FILTER_MODE] = v }
    suspend fun setFilterCcVideos(v: Boolean) = context.dataStore.edit { it[Keys.FILTER_CC_VIDEOS] = v }
    suspend fun setFilterAiLabel(v: Boolean) = context.dataStore.edit { it[Keys.FILTER_AI_LABEL] = v }

    // ---- 下载 ----
    val downloadDir: Flow<String> = context.dataStore.data.map { it[Keys.DOWNLOAD_DIR] ?: "" }
    val downloadFileTemplate: Flow<String> = context.dataStore.data.map { it[Keys.DOWNLOAD_FILE_TEMPLATE] ?: "%username%_%tweetid%" }
    val downloadNotify: Flow<Boolean> = context.dataStore.data.map { it[Keys.DOWNLOAD_NOTIFY] ?: true }

    suspend fun setDownloadDir(v: String) = context.dataStore.edit { it[Keys.DOWNLOAD_DIR] = v }
    suspend fun setDownloadFileTemplate(v: String) = context.dataStore.edit { it[Keys.DOWNLOAD_FILE_TEMPLATE] = v }
    suspend fun setDownloadNotify(v: Boolean) = context.dataStore.edit { it[Keys.DOWNLOAD_NOTIFY] = v }

    // ---- 外观 ----
    val themeMode: Flow<String> = context.dataStore.data.map { it[Keys.THEME_MODE] ?: "system" }

    suspend fun setThemeMode(v: String) = context.dataStore.edit { it[Keys.THEME_MODE] = v }
}
