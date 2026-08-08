package com.xverse.app.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xverse.app.core.data.db.HistoryRecord
import com.xverse.app.ui.history.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史页：搜索 + 分组（今天/昨天/本周/更早）+ 单删/清空 + 点击回跳。
 */
@Composable
fun HistoryScreen(
    mainViewModel: com.xverse.app.MainViewModel,
    modifier: Modifier = Modifier,
) {
    val viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部：标题 + 搜索 + 清空
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = queryValue(viewModel),
                onValueChange = { viewModel.setQuery(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索历史…") },
                singleLine = true,
                shape = RoundedCornerShape(999.dp),
            )
            if (state.total > 0) {
                IconButton(onClick = { viewModel.clearAll() }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "清空历史")
                }
            }
        }

        if (state.total == 0) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无历史记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            state.groups.forEach { (groupName, records) ->
                item(key = "header_$groupName") {
                    Text(
                        text = groupName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(records, key = { it.id }) { record ->
                    HistoryRow(
                        record = record,
                        onClick = { viewModel.open(record) },
                        onDelete = { viewModel.delete(record) },
                        onShare = { viewModel.share(record) },
                    )
                }
            }
        }
    }
}

@Composable
private fun queryValue(viewModel: HistoryViewModel): String {
    val q by viewModel.query.collectAsStateWithLifecycle()
    return q
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
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                Text(
                    text = if (record.displayName.isNotBlank()) record.displayName else "@${record.username.ifBlank { "未知用户" }}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                if (record.textPreview.isNotBlank()) {
                    Text(
                        text = record.textPreview,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text = formatTime(record.visitedAt),
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
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
}
