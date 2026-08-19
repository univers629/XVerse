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
            title = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_title),
            subtitle = if (state.total > 0) {
                androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_subtitle_total, state.total)
            } else {
                androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_subtitle_organized)
            },
            actions = if (state.total > 0) {
                {
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_clear_all_title))
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
                placeholder = { Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_search_placeholder)) },
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
                label = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_filter_text),
                selected = HistoryMediaFilter.TEXT in selectedFilters,
                onClick = { viewModel.toggleMediaFilter(HistoryMediaFilter.TEXT) },
                modifier = Modifier.weight(1f),
            )
            HistoryFilterChip(
                label = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_filter_image),
                selected = HistoryMediaFilter.IMAGE in selectedFilters,
                onClick = { viewModel.toggleMediaFilter(HistoryMediaFilter.IMAGE) },
                modifier = Modifier.weight(1f),
            )
            HistoryFilterChip(
                label = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_filter_video),
                selected = HistoryMediaFilter.VIDEO in selectedFilters,
                onClick = { viewModel.toggleMediaFilter(HistoryMediaFilter.VIDEO) },
                modifier = Modifier.weight(1f),
            )
        }

        if (state.total == 0) {
            val filtering = query.isNotBlank() || selectedFilters.isNotEmpty()
            ExpressiveEmptyState(
                icon = if (filtering) Icons.Filled.Search else Icons.Filled.History,
                title = if (filtering) {
                    androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_empty_search_title)
                } else {
                    androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_empty_title)
                },
                description = if (filtering) {
                    androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_empty_search_desc)
                } else {
                    androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_empty_desc)
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
            title = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_clear_all_title),
            message = if (filtering) {
                androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_clear_all_msg_filtered)
            } else {
                androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_clear_all_msg, state.total)
            },
            confirmLabel = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_action_delete_all),
            onConfirm = {
                showClearConfirm = false
                viewModel.clearAll()
            },
            onDismiss = { showClearConfirm = false },
        )
    }

    deleteTarget?.let { record ->
        val unknownUser = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_unknown_user)
        val userLabel = "@${record.username.ifBlank { unknownUser }}"
        ExpressiveDeleteConfirmDialog(
            title = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_delete_single_title),
            message = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_delete_single_msg, userLabel),
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
                val unknownUser = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_unknown_user)
                val author = buildString {
                    if (record.displayName.isNotBlank()) {
                        append(record.displayName)
                        append(' ')
                    }
                    append('@')
                    append(record.username.ifBlank { unknownUser })
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
                    text = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_visited_at, formatTime(record.visitedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            IconButton(onClick = onShare) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_action_share),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.history_action_delete),
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
