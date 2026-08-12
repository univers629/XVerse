package com.xverse.app.ui.download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xverse.app.core.data.db.DownloadStatus
import com.xverse.app.core.data.db.DownloadTask
import com.xverse.app.ui.download.DownloadViewModel
import com.xverse.app.ui.download.DownloadMediaType
import com.xverse.app.ui.download.downloadMediaType
import com.xverse.app.ui.download.label
import com.xverse.app.ui.common.ExpressiveEmptyState
import com.xverse.app.ui.common.ExpressivePageTitle
import com.xverse.app.ui.common.ExpressiveDeleteConfirmDialog
import kotlinx.coroutines.launch

/**
 * 下载中心：任务列表 + 状态/进度 + 暂停恢复重试删除 + 保存目录选择。
 */
@Composable
fun DownloadScreen(
    mainViewModel: com.xverse.app.MainViewModel,
    modifier: Modifier = Modifier,
) {
    val viewModel: DownloadViewModel = viewModel(factory = DownloadViewModel.Factory)
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val filteredTasks by viewModel.filteredTasks.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedTypes by viewModel.selectedMediaTypes.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<DownloadTask?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        ExpressivePageTitle(
            title = "下载中心",
            subtitle = if (tasks.isEmpty()) "媒体下载会显示在这里" else "${tasks.size} 个下载任务",
        )

        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            placeholder = { Text("搜索下载…") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { viewModel.setQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "清空搜索")
                    }
                }
            } else null,
            shape = MaterialTheme.shapes.extraLarge,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DownloadFilterChip(
                label = "图片",
                selected = DownloadMediaType.IMAGE in selectedTypes,
                onClick = { viewModel.toggleMediaType(DownloadMediaType.IMAGE) },
                modifier = Modifier.weight(1f),
            )
            DownloadFilterChip(
                label = "GIF",
                selected = DownloadMediaType.GIF in selectedTypes,
                onClick = { viewModel.toggleMediaType(DownloadMediaType.GIF) },
                modifier = Modifier.weight(1f),
            )
            DownloadFilterChip(
                label = "视频",
                selected = DownloadMediaType.VIDEO in selectedTypes,
                onClick = { viewModel.toggleMediaType(DownloadMediaType.VIDEO) },
                modifier = Modifier.weight(1f),
            )
        }

        if (tasks.isEmpty()) {
            ExpressiveEmptyState(
                icon = Icons.Filled.Download,
                title = "暂无下载任务",
                description = "在首页打开推文后，可从工具栏选择媒体下载。",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            return@Column
        }

        if (filteredTasks.isEmpty()) {
            ExpressiveEmptyState(
                icon = Icons.Filled.Search,
                title = "没有符合条件的下载",
                description = "请调整搜索内容或媒体类型筛选。",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(filteredTasks, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    vm = viewModel,
                    onDeleteRequest = { deleteTarget = task },
                )
            }
        }
    }

    deleteTarget?.let { task ->
        val active = task.status == DownloadStatus.QUEUED ||
            task.status == DownloadStatus.RUNNING ||
            task.status == DownloadStatus.PAUSED
        val message = when (task.status) {
            DownloadStatus.DONE ->
                "将删除下载记录和设备中已保存的文件“${task.fileName}”。此操作无法撤销。"
            DownloadStatus.FAILED ->
                "将删除失败记录和可能残留的本地文件“${task.fileName}”。此操作无法撤销。"
            else ->
                "将取消当前下载，并删除任务及已产生的本地文件“${task.fileName}”。此操作无法撤销。"
        }
        ExpressiveDeleteConfirmDialog(
            title = if (active) "取消并删除下载？" else "删除下载记录？",
            message = message,
            confirmLabel = if (active) "取消并删除" else "删除",
            onConfirm = {
                deleteTarget = null
                viewModel.delete(task.id)
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun DownloadFilterChip(
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
private fun TaskCard(
    task: DownloadTask,
    vm: DownloadViewModel,
    onDeleteRequest: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 点击打开已下载文件（系统相册/播放器）；未完成/无应用时 Toast 提示原因
    val onOpen: () -> Unit = {
        scope.launch {
            val msg = vm.open(task.id)
            if (msg != null) {
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable(enabled = true, onClick = onOpen),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 媒体缩略图（本地 thumbPath 优先，离线零网络）
                if (task.mediaUrl.isNotBlank()) {
                    com.xverse.app.ui.common.NetworkThumb(
                        url = task.mediaUrl,
                        thumbPath = task.thumbPath,
                        modifier = Modifier.size(52.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (task.mediaUrl.isNotBlank()) 10.dp else 0.dp),
                ) {
                    Text(
                        text = task.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FormatBadge(task)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${task.resolution.ifBlank { "原画" }} · ${task.status.label()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = when (task.status) {
                                DownloadStatus.DONE -> MaterialTheme.colorScheme.primary
                                DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                TaskActions(task, vm, onDeleteRequest)
            }
            if (task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.PAUSED) {
                LinearProgressIndicator(
                    progress = { if (task.totalBytes > 0) task.progress / 100f else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Text(
                    text = "${task.progress}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End),
                )
            }
            if (task.status == DownloadStatus.FAILED && task.error.isNotBlank()) {
                Text(
                    text = task.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun TaskActions(
    task: DownloadTask,
    vm: DownloadViewModel,
    onDeleteRequest: () -> Unit,
) {
    when (task.status) {
        DownloadStatus.RUNNING -> IconButton(onClick = { vm.pause(task.id) }) {
            Icon(Icons.Filled.Pause, contentDescription = "暂停")
        }
        DownloadStatus.PAUSED -> IconButton(onClick = { vm.resume(task.id) }) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "恢复")
        }
        DownloadStatus.QUEUED -> IconButton(onClick = { vm.resume(task.id) }) {
            Icon(Icons.Filled.Refresh, contentDescription = "重新入队")
        }
        DownloadStatus.FAILED -> IconButton(onClick = { vm.retry(task.id) }) {
            Icon(Icons.Filled.Refresh, contentDescription = "重试")
        }
        DownloadStatus.DONE -> {}
    }
    // 所有状态都可删除：QUEUED/RUNNING 会取消排队任务，其余清理已完成/失败记录
    IconButton(onClick = onDeleteRequest) {
        Icon(Icons.Filled.Delete, contentDescription = "删除")
    }
}

/**
 * 格式徽标：GIF / 视频 / 图片。
 * 优先用任务落库的 mediaType（photo/video/gif）；存量任务该字段为空时按文件名后缀兜底。
 * 样式对齐扩展页来源徽标：Surface + 4dp 圆角 + labelSmall。
 */
@Composable
private fun FormatBadge(task: DownloadTask) {
    val darkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val (label, container, content) = when (task.downloadMediaType()) {
        DownloadMediaType.GIF -> Triple(
            "GIF",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        DownloadMediaType.IMAGE -> Triple(
            "图片",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        DownloadMediaType.VIDEO -> Triple(
            "视频",
            // 深色模式下进一步降低容器不透明度，避免淡绿块过于跳脱。
            Color(0xFF9CE5A8).copy(alpha = if (darkSurface) 0.22f else 0.50f),
            if (darkSurface) Color(0xFFB7EDC0) else Color(0xFF155D2D),
        )
    }
    Surface(color = container, shape = MaterialTheme.shapes.small) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}
