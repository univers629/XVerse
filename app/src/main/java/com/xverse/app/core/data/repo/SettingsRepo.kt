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
        val HISTORY_RECORD_MEDIA = booleanPreferencesKey("history_record_media")
        val HISTORY_KEEP_DAYS = intPreferencesKey("history_keep_days")
        val HISTORY_MAX = intPreferencesKey("history_max")

        val FILTER_ENABLED = booleanPreferencesKey("filter_enabled")
        val FILTER_AUTO_UPDATE = booleanPreferencesKey("filter_auto_update")
        val FILTER_REMOTE_VERSION = stringPreferencesKey("filter_remote_version")

        val DOWNLOAD_DIR = stringPreferencesKey("download_dir")
        val DOWNLOAD_DEFAULT_RES = stringPreferencesKey("download_default_res")
        val DOWNLOAD_FILE_TEMPLATE = stringPreferencesKey("download_file_template")
        val DOWNLOAD_NOTIFY = booleanPreferencesKey("download_notify")

        val THEME_MODE = stringPreferencesKey("theme_mode")          // system / light / dark
        val THEME_DYNAMIC = booleanPreferencesKey("theme_dynamic")
        val THEME_SEED = stringPreferencesKey("theme_seed")
        val FONT_SCALE = intPreferencesKey("font_scale")             // 0/1/2
    }

    // ---- 历史 ----
    val historyEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.HISTORY_ENABLED] ?: true }
    val historyRecordMedia: Flow<Boolean> = context.dataStore.data.map { it[Keys.HISTORY_RECORD_MEDIA] ?: true }
    val historyKeepDays: Flow<Int> = context.dataStore.data.map { it[Keys.HISTORY_KEEP_DAYS] ?: Constants.HISTORY_MAX_KEEP_DAYS }
    val historyMax: Flow<Int> = context.dataStore.data.map { it[Keys.HISTORY_MAX] ?: Constants.HISTORY_MAX_RECORDS }

    suspend fun setHistoryEnabled(v: Boolean) = context.dataStore.edit { it[Keys.HISTORY_ENABLED] = v }
    suspend fun setHistoryRecordMedia(v: Boolean) = context.dataStore.edit { it[Keys.HISTORY_RECORD_MEDIA] = v }
    suspend fun setHistoryKeepDays(v: Int) = context.dataStore.edit { it[Keys.HISTORY_KEEP_DAYS] = v }
    suspend fun setHistoryMax(v: Int) = context.dataStore.edit { it[Keys.HISTORY_MAX] = v }

    // ---- 过滤 ----
    val filterEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.FILTER_ENABLED] ?: true }
    val filterAutoUpdate: Flow<Boolean> = context.dataStore.data.map { it[Keys.FILTER_AUTO_UPDATE] ?: true }
    val filterRemoteVersion: Flow<String> = context.dataStore.data.map { it[Keys.FILTER_REMOTE_VERSION] ?: "" }

    suspend fun setFilterEnabled(v: Boolean) = context.dataStore.edit { it[Keys.FILTER_ENABLED] = v }
    suspend fun setFilterAutoUpdate(v: Boolean) = context.dataStore.edit { it[Keys.FILTER_AUTO_UPDATE] = v }
    suspend fun setFilterRemoteVersion(v: String) = context.dataStore.edit { it[Keys.FILTER_REMOTE_VERSION] = v }

    // ---- 下载 ----
    val downloadDir: Flow<String> = context.dataStore.data.map { it[Keys.DOWNLOAD_DIR] ?: "" }
    val downloadDefaultRes: Flow<String> = context.dataStore.data.map { it[Keys.DOWNLOAD_DEFAULT_RES] ?: "1080" }
    val downloadFileTemplate: Flow<String> = context.dataStore.data.map { it[Keys.DOWNLOAD_FILE_TEMPLATE] ?: "%username%_%tweetid%" }
    val downloadNotify: Flow<Boolean> = context.dataStore.data.map { it[Keys.DOWNLOAD_NOTIFY] ?: true }

    suspend fun setDownloadDir(v: String) = context.dataStore.edit { it[Keys.DOWNLOAD_DIR] = v }
    suspend fun setDownloadDefaultRes(v: String) = context.dataStore.edit { it[Keys.DOWNLOAD_DEFAULT_RES] = v }
    suspend fun setDownloadFileTemplate(v: String) = context.dataStore.edit { it[Keys.DOWNLOAD_FILE_TEMPLATE] = v }
    suspend fun setDownloadNotify(v: Boolean) = context.dataStore.edit { it[Keys.DOWNLOAD_NOTIFY] = v }

    // ---- 外观 ----
    val themeMode: Flow<String> = context.dataStore.data.map { it[Keys.THEME_MODE] ?: "system" }
    val themeDynamic: Flow<Boolean> = context.dataStore.data.map { it[Keys.THEME_DYNAMIC] ?: true }
    val themeSeed: Flow<String> = context.dataStore.data.map { it[Keys.THEME_SEED] ?: "" }
    val fontScale: Flow<Int> = context.dataStore.data.map { it[Keys.FONT_SCALE] ?: 1 }

    suspend fun setThemeMode(v: String) = context.dataStore.edit { it[Keys.THEME_MODE] = v }
    suspend fun setThemeDynamic(v: Boolean) = context.dataStore.edit { it[Keys.THEME_DYNAMIC] = v }
    suspend fun setThemeSeed(v: String) = context.dataStore.edit { it[Keys.THEME_SEED] = v }
    suspend fun setFontScale(v: Int) = context.dataStore.edit { it[Keys.FONT_SCALE] = v }
}
