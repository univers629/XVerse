package com.xverse.app.ui.logs

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import com.xverse.app.ui.common.ExpressiveEmptyState
import com.xverse.app.ui.common.SmoothDropdownContent

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
        LogCategoryDropdown(filter = filter, onSelect = { filter = it })

        // 导出按钮
        Button(
            onClick = {
                val path = LogStore.exportToFile()
                Toast.makeText(context, path?.let { "已导出到 $it" } ?: "导出失败", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Text("导出日志")
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))

        if (logs.isEmpty()) {
            ExpressiveEmptyState(
                icon = Icons.AutoMirrored.Filled.Article,
                title = "暂无日志",
                description = "运行日志会按分类显示在这里。",
                modifier = Modifier.fillMaxSize(),
            )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogCategoryDropdown(filter: LogCategory?, onSelect: (LogCategory?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val value = filter?.label ?: "全部"
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text("分类筛选") },
                textStyle = MaterialTheme.typography.titleSmall,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                shape = RoundedCornerShape(18.dp),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                SmoothDropdownContent(expanded = expanded) {
                    DropdownMenuItem(
                        text = { Text("全部", style = MaterialTheme.typography.titleSmall) },
                        onClick = { expanded = false; onSelect(null) },
                    )
                    LogCategory.entries.forEach { category ->
                        DropdownMenuItem(
                            text = { Text(category.label, style = MaterialTheme.typography.titleSmall) },
                            onClick = { expanded = false; onSelect(category) },
                        )
                    }
                }
            }
        }
    }
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
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
}
