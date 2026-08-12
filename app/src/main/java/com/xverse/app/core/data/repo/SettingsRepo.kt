package com.xverse.app.core.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xverse.app.core.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * 设置仓库：DataStore 持久化所有偏好项。
 */
class SettingsRepo(private val context: Context) {

    companion object {
        /** X blue, stored as signed ARGB for DataStore and Compose Color(Int). */
        const val DEFAULT_MONET_COLOR_ARGB: Int = -14836752
        const val DEFAULT_CUSTOM_MONET_ENABLED = false
        const val DEFAULT_HIDE_X_BOTTOM_BAR = false
        const val DEFAULT_FILTER_EXTENSION_RULES_ENABLED = false
        const val DEFAULT_FILTER_AI_LABEL_ENABLED = false
        const val DEFAULT_FILTER_MODE = "mask"
    }

    private object Keys {
        val FILTER_ENABLED = booleanPreferencesKey("filter_enabled")
        val FILTER_MODE = stringPreferencesKey("filter_mode")       // mask=占位+可点击验证 / strip=完全不加载广告
        val FILTER_CC_VIDEOS = booleanPreferencesKey("filter_cc_videos") // 过滤带字幕（CC）视频
        val FILTER_AI_LABEL = booleanPreferencesKey("filter_ai_label") // 过滤 AI 生成标签（Made with AI）
        val FILTER_EXTENSION_DEFAULTS = booleanPreferencesKey("filter_extension_defaults") // 用户下载过滤扩展的默认规则
        val FILTER_EXTENSION_DISABLED_GROUPS = stringSetPreferencesKey("filter_extension_disabled_groups")

        val DOWNLOAD_IMAGE_DIR = stringPreferencesKey("download_image_dir")
        val DOWNLOAD_GIF_DIR = stringPreferencesKey("download_gif_dir")
        val DOWNLOAD_VIDEO_DIR = stringPreferencesKey("download_video_dir")
        val DOWNLOAD_FILE_TEMPLATE = stringPreferencesKey("download_file_template")
        val DOWNLOAD_NOTIFY = booleanPreferencesKey("download_notify")

        val THEME_MODE = stringPreferencesKey("theme_mode")          // system / light / dark
        val HIDE_X_BOTTOM_BAR = booleanPreferencesKey("hide_x_bottom_bar")
        val CUSTOM_MONET_ENABLED = booleanPreferencesKey("custom_monet_enabled")
        val CUSTOM_MONET_COLOR = intPreferencesKey("custom_monet_color")
    }

    // ---- 过滤 ----
    val filterEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.FILTER_ENABLED] ?: true }
    val filterMode: Flow<String> = context.dataStore.data.map {
        it[Keys.FILTER_MODE] ?: DEFAULT_FILTER_MODE
    }
    val filterCcVideos: Flow<Boolean> = context.dataStore.data.map { it[Keys.FILTER_CC_VIDEOS] ?: true }
    val filterAiLabel: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.FILTER_AI_LABEL] ?: DEFAULT_FILTER_AI_LABEL_ENABLED
    }
    val filterExtensionDefaults: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.FILTER_EXTENSION_DEFAULTS] ?: DEFAULT_FILTER_EXTENSION_RULES_ENABLED
    }
    val filterExtensionDisabledGroups: Flow<Set<String>> = context.dataStore.data.map {
        it[Keys.FILTER_EXTENSION_DISABLED_GROUPS] ?: emptySet()
    }

    suspend fun setFilterEnabled(v: Boolean) = context.dataStore.edit { it[Keys.FILTER_ENABLED] = v }
    suspend fun setFilterMode(v: String) = context.dataStore.edit { it[Keys.FILTER_MODE] = v }
    suspend fun setFilterCcVideos(v: Boolean) = context.dataStore.edit { it[Keys.FILTER_CC_VIDEOS] = v }
    suspend fun setFilterAiLabel(v: Boolean) = context.dataStore.edit { it[Keys.FILTER_AI_LABEL] = v }
    suspend fun setFilterExtensionDefaults(v: Boolean) = context.dataStore.edit { it[Keys.FILTER_EXTENSION_DEFAULTS] = v }
    suspend fun setFilterExtensionGroupEnabled(extensionId: String, filterId: Int, enabled: Boolean) =
        context.dataStore.edit { prefs ->
            val key = "$extensionId:$filterId"
            val disabled = prefs[Keys.FILTER_EXTENSION_DISABLED_GROUPS].orEmpty().toMutableSet()
            if (enabled) disabled.remove(key) else disabled.add(key)
            prefs[Keys.FILTER_EXTENSION_DISABLED_GROUPS] = disabled
        }

    // ---- 下载 ----
    val downloadImageDir: Flow<String> = context.dataStore.data.map { it[Keys.DOWNLOAD_IMAGE_DIR] ?: "" }
    val downloadGifDir: Flow<String> = context.dataStore.data.map { it[Keys.DOWNLOAD_GIF_DIR] ?: "" }
    val downloadVideoDir: Flow<String> = context.dataStore.data.map { it[Keys.DOWNLOAD_VIDEO_DIR] ?: "" }
    val downloadFileTemplate: Flow<String> = context.dataStore.data.map { it[Keys.DOWNLOAD_FILE_TEMPLATE] ?: "%username%_%tweetid%" }
    val downloadNotify: Flow<Boolean> = context.dataStore.data.map { it[Keys.DOWNLOAD_NOTIFY] ?: true }

    suspend fun setDownloadImageDir(v: String) = context.dataStore.edit { it[Keys.DOWNLOAD_IMAGE_DIR] = v }
    suspend fun setDownloadGifDir(v: String) = context.dataStore.edit { it[Keys.DOWNLOAD_GIF_DIR] = v }
    suspend fun setDownloadVideoDir(v: String) = context.dataStore.edit { it[Keys.DOWNLOAD_VIDEO_DIR] = v }
    suspend fun setDownloadFileTemplate(v: String) = context.dataStore.edit { it[Keys.DOWNLOAD_FILE_TEMPLATE] = v }
    suspend fun setDownloadNotify(v: Boolean) = context.dataStore.edit { it[Keys.DOWNLOAD_NOTIFY] = v }

    // ---- 外观 ----
    val themeMode: Flow<String> = context.dataStore.data.map { it[Keys.THEME_MODE] ?: "system" }
    val hideXBottomBar: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.HIDE_X_BOTTOM_BAR] ?: DEFAULT_HIDE_X_BOTTOM_BAR
    }
    val customMonetEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.CUSTOM_MONET_ENABLED] ?: DEFAULT_CUSTOM_MONET_ENABLED
    }
    val customMonetColor: Flow<Int> = context.dataStore.data.map {
        it[Keys.CUSTOM_MONET_COLOR] ?: DEFAULT_MONET_COLOR_ARGB
    }

    suspend fun setThemeMode(v: String) = context.dataStore.edit { it[Keys.THEME_MODE] = v }
    suspend fun setHideXBottomBar(v: Boolean) = context.dataStore.edit {
        it[Keys.HIDE_X_BOTTOM_BAR] = v
    }
    suspend fun setCustomMonetEnabled(v: Boolean) = context.dataStore.edit {
        it[Keys.CUSTOM_MONET_ENABLED] = v
    }
    suspend fun setCustomMonetColor(v: Int) = context.dataStore.edit {
        it[Keys.CUSTOM_MONET_COLOR] = v or (0xFF shl 24)
    }
}
