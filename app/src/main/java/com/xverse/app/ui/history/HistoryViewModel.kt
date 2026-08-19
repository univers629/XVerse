package com.xverse.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xverse.app.core.data.db.HistoryRecord
import com.xverse.app.di.ServiceLocator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 历史页 ViewModel：分组 / 搜索 / 删除。
 */
class HistoryViewModel(private val locator: ServiceLocator) : ViewModel() {

    val query = MutableStateFlow("")
    val selectedMediaFilters = MutableStateFlow<Set<HistoryMediaFilter>>(emptySet())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val source = combine(query, locator.authController.username) { q, account -> q to account }
        .flatMapLatest { (q, account) ->
            if (account.isBlank()) flowOf(emptyList())
            else if (q.isBlank()) locator.historyRepo.observeForAccount(account)
            else locator.historyRepo.search(account, q)
    }

    private val filteredSource = combine(source, selectedMediaFilters) { records, filters ->
        if (filters.isEmpty()) records
        else records.filter { record -> record.historyMediaFilter() in filters }
    }

    /** 分组的不可变快照 */
    data class UiState(
        val groups: List<Pair<String, List<HistoryRecord>>> = emptyList(),
        val total: Int = 0,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    /** 已补种过缩略图的记录 id（防重复拉取） */
    private val backfilled = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    init {
        viewModelScope.launch { locator.historyRepo.backfillMediaTypes() }
        viewModelScope.launch {
            filteredSource.collect { records ->
                _uiState.value = buildGroups(records)
                backfillThumbnails(records)
            }
        }
    }

    /**
     * 存量记录缩略图补种：thumbPath 为空但有 mediaUrl 的记录异步落盘本地，
     * 让历史页完全离线显示（旧版本写入的记录没有本地缩略图）。
     * 存量坏数据兜底：mediaUrl 若是 video.twimg.com 的 mp4 直链（GIF/视频本体，
     * 非海报帧，0.3.0 前 DOM 提取缺陷写入），改用 GraphQL 缓存的正确海报帧重拉。
     */
    private suspend fun backfillThumbnails(records: List<HistoryRecord>) {
        records.filter { it.mediaUrl.isNotBlank() && it.thumbPath.isBlank() && it.id !in backfilled }
            .forEach { record ->
                backfilled += record.id
                var url = record.mediaUrl
                // GIF/视频本体直链 → 用海报帧替代（否则拉到 mp4 字节写 .jpg，解码失败不显示）
                if (url.contains("/video.twimg.com/")) {
                    val cached = locator.mediaParser.cachedThumbnail(record.tweetId)
                    if (cached.isNotBlank()) url = cached
                }
                val thumb = com.xverse.app.core.util.ThumbCache.persist(
                    locator.appContext, "history-${record.tweetId}", url
                )
                if (thumb.isNotBlank()) {
                    // 落盘成功 → 回写 thumbPath（upsert REPLACE 保留原 visitedAt）
                    locator.historyRepo.upsert(record.copy(thumbPath = thumb))
                }
            }
    }

    /**
     * 按设备本地时区的自然日分组。不能用「过去 24 小时」这种滚动窗口：午夜前后的
     * 记录必须分属两个日期，搜索结果也沿用同一规则。
     */
    private fun buildGroups(records: List<HistoryRecord>): UiState {
        if (records.isEmpty()) return UiState()
        val context = locator.appContext
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val lang = com.xverse.app.core.data.repo.SettingsRepo.getSavedAppLanguage(context)
        val locale = com.xverse.app.core.util.LocaleUtils.getLocale(lang)
        val patternTodayYesterday = com.xverse.app.core.util.LocaleUtils.getString(context, com.xverse.app.R.string.history_date_format_today_yesterday)
        val patternOther = com.xverse.app.core.util.LocaleUtils.getString(context, com.xverse.app.R.string.history_date_format_other)
        val dateFormatter = runCatching { DateTimeFormatter.ofPattern(patternTodayYesterday, locale) }
            .getOrDefault(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
        val otherFormatter = runCatching { DateTimeFormatter.ofPattern(patternOther, locale) }
            .getOrDefault(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
        val eeeeFormatter = DateTimeFormatter.ofPattern("EEEE", locale)

        val groups = records
            .groupBy { Instant.ofEpochMilli(it.visitedAt).atZone(zone).toLocalDate() }
            .map { (date, dailyRecords) ->
                val prefix = when (date) {
                    today -> com.xverse.app.core.util.LocaleUtils.getString(context, com.xverse.app.R.string.history_date_today)
                    today.minusDays(1) -> com.xverse.app.core.util.LocaleUtils.getString(context, com.xverse.app.R.string.history_date_yesterday)
                    else -> date.format(otherFormatter)
                }
                val label = if (date == today || date == today.minusDays(1)) {
                    "$prefix · ${date.format(dateFormatter)}"
                } else {
                    "$prefix · ${date.format(eeeeFormatter)}"
                }
                label to dailyRecords
            }
        return UiState(groups, records.size)
    }

    fun setQuery(q: String) {
        query.value = q
    }

    fun toggleMediaFilter(filter: HistoryMediaFilter) {
        selectedMediaFilters.value = selectedMediaFilters.value.toMutableSet().apply {
            if (!add(filter)) remove(filter)
        }
    }

    fun delete(record: HistoryRecord) {
        viewModelScope.launch { locator.historyRepo.delete(record) }
    }

    /** 分享历史记录：调系统分享菜单（分享推文链接 + 文本预览） */
    fun share(record: HistoryRecord) {
        val context = locator.appContext
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, record.url)
            putExtra(
                android.content.Intent.EXTRA_TITLE,
                if (record.displayName.isNotBlank()) record.displayName else "@${record.username}",
            )
            putExtra(
                android.content.Intent.EXTRA_SUBJECT,
                if (record.textPreview.isNotBlank()) record.textPreview else record.url,
            )
        }
        val chooser = android.content.Intent.createChooser(send, com.xverse.app.core.util.LocaleUtils.getString(context, com.xverse.app.R.string.history_share_tweet_chooser))
        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }

    fun clearAll() {
        val account = locator.authController.username.value
        if (account.isBlank()) return
        viewModelScope.launch { locator.historyRepo.clear(account) }
    }

    /** 点击历史项：切回首页 Tab 并瞬显推文页。
     *  先切 Tab（恢复 WebView 可见），再发 LoadUrl —— 避免 GoHome 抢 WebView 的竞态。
     *  推文打开走 BrowserViewModel.openTweetInstant：WebView 已在 x.com 应用内时
     *  用 SPA 路由瞬显（不整页重载、不闪 X 徽标）；不在时退化为整页 loadUrl。
     */
    fun open(record: HistoryRecord) {
        com.xverse.app.CommandBus.selectTab(com.xverse.app.ui.navigation.XTab.HOME)
        com.xverse.app.CommandBus.push(com.xverse.app.BrowserCommand.OpenTweet(record.url))
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                HistoryViewModel((app as com.xverse.app.XVerseApp).locator)
            }
        }
    }
}

enum class HistoryMediaFilter { TEXT, IMAGE, VIDEO }

/** GIF 按产品定义并入视频；URL 兜底兼容类型字段为空的旧记录。 */
private fun HistoryRecord.historyMediaFilter(): HistoryMediaFilter? {
    return when (mediaType.lowercase()) {
        "photo", "image" -> HistoryMediaFilter.IMAGE
        "video", "gif", "animated_gif" -> HistoryMediaFilter.VIDEO
        else -> when {
            mediaUrl.contains("/media/", ignoreCase = true) -> HistoryMediaFilter.IMAGE
            mediaUrl.contains("video_thumb", ignoreCase = true) ||
                mediaUrl.startsWith("https://video.twimg.com/", ignoreCase = true) -> HistoryMediaFilter.VIDEO
            mediaUrl.isBlank() -> HistoryMediaFilter.TEXT
            else -> null
        }
    }
}
