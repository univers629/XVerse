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
                val msg = path?.let { com.xverse.app.core.util.LocaleUtils.getString(context, com.xverse.app.R.string.logs_toast_exported, it) }
                    ?: com.xverse.app.core.util.LocaleUtils.getString(context, com.xverse.app.R.string.logs_toast_export_failed)
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.logs_btn_export))
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))

        if (logs.isEmpty()) {
            ExpressiveEmptyState(
                icon = Icons.AutoMirrored.Filled.Article,
                title = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.logs_empty_title),
                description = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.logs_empty_desc),
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    var expanded by remember { mutableStateOf(false) }
    val allLabel = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.logs_cat_all)
    val value = filter?.let { androidx.compose.ui.res.stringResource(it.labelRes) } ?: allLabel
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
                label = { Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.logs_filter_label)) },
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
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides context,
                    androidx.compose.ui.platform.LocalConfiguration provides config,
                ) {
                    SmoothDropdownContent(expanded = expanded) {
                        DropdownMenuItem(
                            text = { Text(allLabel, style = MaterialTheme.typography.titleSmall) },
                            onClick = { expanded = false; onSelect(null) },
                        )
                        LogCategory.entries.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(androidx.compose.ui.res.stringResource(category.labelRes), style = MaterialTheme.typography.titleSmall) },
                                onClick = { expanded = false; onSelect(category) },
                            )
                        }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val lang = remember(context) {
        com.xverse.app.core.data.repo.SettingsRepo.getSavedAppLanguage(context).let {
            if (it == "system") {
                com.xverse.app.core.util.LocaleUtils.getSystemLocale().language
            } else {
                it
            }
        }
    }
    val displayMessage = remember(message, lang) {
        com.xverse.app.core.log.LogLocalizer.localize(message, lang)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            val catLabel = androidx.compose.ui.res.stringResource(category.labelRes)
            Text(
                text = "[$catLabel]",
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = displayMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
