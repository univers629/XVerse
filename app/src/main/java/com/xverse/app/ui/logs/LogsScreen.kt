package com.xverse.app.ui.logs

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore

/**
 * 日志页：分类筛选 + 列表 + 一键导出。
 */
@Composable
fun LogsScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val allLogs by LogStore.flow.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<LogCategory?>(null) }

    val logs = if (filter == null) allLogs else allLogs.filter { it.category == filter }

    Column(modifier = modifier.fillMaxSize()) {
        // 筛选条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CategoryChip("全部", filter == null, onClick = { filter = null })
            LogCategory.entries.forEach { cat ->
                CategoryChip(cat.label, filter == cat, onClick = { filter = if (filter == cat) null else cat })
            }
        }

        // 导出按钮
        Button(
            onClick = {
                val path = LogStore.exportToFile()
                Toast.makeText(context, path?.let { "已导出到 $it" } ?: "导出失败", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text("导出日志")
        }

        HorizontalDivider()

        if (logs.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "暂无日志",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            return
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logs.size) { index ->
                val e = logs[index]
                LogRow(e.category, e.message)
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(),
    )
}

@Composable
private fun LogRow(category: LogCategory, message: String) {
    val color = when (category) {
        LogCategory.ERROR -> MaterialTheme.colorScheme.error
        LogCategory.FILTER -> MaterialTheme.colorScheme.tertiary
        LogCategory.DOWNLOAD -> MaterialTheme.colorScheme.primary
        LogCategory.WEBVIEW -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = "[${category.label}]",
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
