package com.xverse.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xverse.app.AppInstance
import com.xverse.app.core.data.db.HistoryRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * 历史页 ViewModel：分组 / 搜索 / 删除。
 */
class HistoryViewModel : ViewModel() {

    private val locator get() = AppInstance.locator

    val query = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    private val source = query.flatMapLatest { q ->
        if (q.isBlank()) locator.historyRepo.observeAll()
        else locator.historyRepo.search(q)
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
        viewModelScope.launch {
            source.collect { records ->
                _uiState.value = buildGroups(records)
                backfillThumbnails(records)
            }
        }
    }

    /**
     * 存量记录缩略图补种：thumbPath 为空但有 mediaUrl 的记录异步落盘本地，
     * 让历史页完全离线显示（旧版本写入的记录没有本地缩略图）。
     */
    private suspend fun backfillThumbnails(records: List<HistoryRecord>) {
        records.filter { it.mediaUrl.isNotBlank() && it.thumbPath.isBlank() && it.id !in backfilled }
            .forEach { record ->
                backfilled += record.id
                val thumb = com.xverse.app.core.util.ThumbCache.persist(
                    locator.appContext, "history-${record.tweetId}", record.mediaUrl
                )
                if (thumb.isNotBlank()) {
                    // 落盘成功 → 回写 thumbPath（upsert REPLACE 保留原 visitedAt）
                    locator.historyRepo.upsert(record.copy(thumbPath = thumb))
                }
            }
    }

    private fun buildGroups(records: List<HistoryRecord>): UiState {
        if (records.isEmpty()) return UiState()
        val now = System.currentTimeMillis()
        val dayMs = 86_400_000L
        val weekMs = 7 * dayMs
        val today = records.filter { now - it.visitedAt < dayMs }
        val yesterday = records.filter { now - it.visitedAt < 2 * dayMs && now - it.visitedAt >= dayMs }
        val week = records.filter { now - it.visitedAt < weekMs && now - it.visitedAt >= 2 * dayMs }
        val earlier = records.filter { now - it.visitedAt >= weekMs }

        val groups = buildList {
            if (today.isNotEmpty()) add("今天" to today)
            if (yesterday.isNotEmpty()) add("昨天" to yesterday)
            if (week.isNotEmpty()) add("本周" to week)
            if (earlier.isNotEmpty()) add("更早" to earlier)
        }
        return UiState(groups, records.size)
    }

    fun setQuery(q: String) {
        query.value = q
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
        val chooser = android.content.Intent.createChooser(send, "分享推文")
        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }

    fun clearAll() {
        viewModelScope.launch { locator.historyRepo.clear() }
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
        val Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return HistoryViewModel() as T
            }
        }
    }
}
