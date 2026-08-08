package com.xverse.app.ui.download

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xverse.app.core.data.db.DownloadStatus
import com.xverse.app.core.data.db.DownloadTask
import com.xverse.app.ui.download.DownloadViewModel
import com.xverse.app.ui.download.label
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

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "下载中心",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // 保存位置：系统相册目录（MediaStore，无需选目录）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "保存到系统相册：图片 → Pictures/XVerse，视频 → Movies/XVerse",
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        if (tasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无下载任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(tasks, key = { it.id }) { task ->
                TaskCard(task, viewModel)
            }
        }
    }
}

@Composable
private fun TaskCard(task: DownloadTask, vm: DownloadViewModel) {
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
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(enabled = true, onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
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
                        style = MaterialTheme.typography.bodyMedium,
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
                TaskActions(task, vm)
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
private fun TaskActions(task: DownloadTask, vm: DownloadViewModel) {
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
    IconButton(onClick = { vm.delete(task.id) }) {
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
    val type = task.mediaType.ifBlank {
        when (task.fileName.substringAfterLast('.', "").lowercase()) {
            "gif" -> "gif"
            "jpg", "jpeg", "png", "webp", "heic", "bmp" -> "photo"
            else -> "video"
        }
    }
    val (label, container, content) = when (type) {
        "gif" -> Triple(
            "GIF",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        "photo" -> Triple(
            "图片",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        else -> Triple(
            "视频",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(color = container, shape = RoundedCornerShape(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}
