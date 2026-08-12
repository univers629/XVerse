package com.xverse.app.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xverse.app.core.data.db.HistoryRecord
import com.xverse.app.ui.common.ExpressiveDeleteConfirmDialog
import com.xverse.app.ui.common.ExpressiveEmptyState
import com.xverse.app.ui.common.ExpressivePageTitle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史页：搜索 + 按本地自然日分割 + 单删/清空 + 点击回跳。
 */
@Composable
fun HistoryScreen(
    mainViewModel: com.xverse.app.MainViewModel,
    modifier: Modifier = Modifier,
) {
    val viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedFilters by viewModel.selectedMediaFilters.collectAsStateWithLifecycle()
    var showClearConfirm by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<HistoryRecord?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        ExpressivePageTitle(
            title = "历史记录",
            subtitle = if (state.total > 0) "共 ${state.total} 条浏览记录" else "按访问日期整理",
            actions = if (state.total > 0) {
                {
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "清空历史")
                    }
                }
            } else null,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.setQuery(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索历史…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                shape = MaterialTheme.shapes.extraLarge,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HistoryFilterChip(
                label = "纯文字",
                selected = HistoryMediaFilter.TEXT in selectedFilters,
                onClick = { viewModel.toggleMediaFilter(HistoryMediaFilter.TEXT) },
                modifier = Modifier.weight(1f),
            )
            HistoryFilterChip(
                label = "图片",
                selected = HistoryMediaFilter.IMAGE in selectedFilters,
                onClick = { viewModel.toggleMediaFilter(HistoryMediaFilter.IMAGE) },
                modifier = Modifier.weight(1f),
            )
            HistoryFilterChip(
                label = "视频",
                selected = HistoryMediaFilter.VIDEO in selectedFilters,
                onClick = { viewModel.toggleMediaFilter(HistoryMediaFilter.VIDEO) },
                modifier = Modifier.weight(1f),
            )
        }

        if (state.total == 0) {
            val filtering = query.isNotBlank() || selectedFilters.isNotEmpty()
            ExpressiveEmptyState(
                icon = if (filtering) Icons.Filled.Search else Icons.Filled.History,
                title = if (filtering) "没有符合条件的记录" else "暂无历史记录",
                description = if (filtering) {
                    "请调整搜索内容或媒体类型筛选。"
                } else {
                    "浏览推文后，记录会按每天自动整理在这里。"
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            return
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            state.groups.forEach { (groupName, records) ->
                item(key = "header_$groupName") {
                    HistoryDateDivider(groupName)
                }
                items(records, key = { it.id }) { record ->
                    HistoryRow(
                        record = record,
                        onClick = { viewModel.open(record) },
                        onDelete = { deleteTarget = record },
                        onShare = { viewModel.share(record) },
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        val filtering = query.isNotBlank() || selectedFilters.isNotEmpty()
        ExpressiveDeleteConfirmDialog(
            title = "清空全部历史？",
            message = if (filtering) {
                "将删除当前账号的全部浏览记录，包括当前筛选中未显示的记录。此操作无法撤销。"
            } else {
                "将删除当前账号的全部 ${state.total} 条浏览记录。此操作无法撤销。"
            },
            confirmLabel = "全部删除",
            onConfirm = {
                showClearConfirm = false
                viewModel.clearAll()
            },
            onDismiss = { showClearConfirm = false },
        )
    }

    deleteTarget?.let { record ->
        ExpressiveDeleteConfirmDialog(
            title = "删除这条历史？",
            message = "将删除 @${record.username.ifBlank { "未知用户" }} 的这条浏览记录。此操作无法撤销。",
            onConfirm = {
                deleteTarget = null
                viewModel.delete(record)
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

/** 左对齐的日期分隔：动态莫奈主色文字，不使用胶囊或卡片背景。 */
@Composable
private fun HistoryDateDivider(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
        )
    }
}

@Composable
private fun HistoryFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        label = {
            Text(
                text = label,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
            )
        },
    )
}

@Composable
private fun HistoryRow(
    record: HistoryRecord,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 媒体缩略图（本地 thumbPath 优先，离线零网络）
            val thumb = record.mediaUrl
            if (thumb.isNotBlank()) {
                com.xverse.app.ui.common.NetworkThumb(
                    url = thumb,
                    thumbPath = record.thumbPath,
                    modifier = Modifier.size(56.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (thumb.isNotBlank()) 10.dp else 0.dp),
            ) {
                val author = buildString {
                    if (record.displayName.isNotBlank()) {
                        append(record.displayName)
                        append(' ')
                    }
                    append('@')
                    append(record.username.ifBlank { "未知用户" })
                }
                Text(
                    text = author,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (record.textPreview.isNotBlank()) {
                    Text(
                        text = record.textPreview,
                    style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text = "访问于 ${formatTime(record.visitedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            IconButton(onClick = onShare) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = "分享",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 网络缩略图：见 ui.common.NetworkThumb */

private fun formatTime(time: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
}
