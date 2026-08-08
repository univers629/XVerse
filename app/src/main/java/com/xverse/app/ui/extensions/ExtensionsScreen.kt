package com.xverse.app.ui.extensions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xverse.app.AppInstance
import com.xverse.app.core.extensions.ExtensionEntity
import java.io.File

/**
 * 扩展页：导入（文件/链接）+ 扩展卡片列表 + 配置覆盖层。
 */
@Composable
fun ExtensionsScreen(
    modifier: Modifier = Modifier,
) {
    val viewModel: ExtensionsViewModel = viewModel(factory = ExtensionsViewModel.Factory)
    val extensions by viewModel.extensions.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()

    val optionsExt by viewModel.optionsExt.collectAsStateWithLifecycle()
    optionsExt?.let { ext ->
        OptionsOverlay(ext = ext, onClose = { viewModel.closeOptions() })
        return
    }

    // 导入对话框状态
    var showImportDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFile(uri)
    }

    // 对话框放在所有提前 return 之前：空列表态也要能弹出导入对话框
    if (showImportDialog) {
        ImportDialog(
            onDismiss = { showImportDialog = false },
            onImportFile = {
                showImportDialog = false
                // .crx 的系统 MIME 常解析为 application/octet-stream，DocumentsUI
                // 在限定 MIME 集合时不可靠地过滤未知类型文件，故用 */* 全显示，
                // 由 ExtensionImporter 做 Cr24/ZIP 头与解压校验兜底。
                launcher.launch(arrayOf("*/*"))
            },
            onImportUrl = { input ->
                showImportDialog = false
                viewModel.importFromUrl(input)
            },
            importing = importing,
            context = context,
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "扩展",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (importing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            Button(onClick = { showImportDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("导入扩展")
            }
        }

        if (extensions.isEmpty() && !importing) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.Extension,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "还没有扩展",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "支持 .crx/.zip 扩展包、.user.js 油猴用户脚本，或粘贴 Chrome/Edge 商店链接、扩展 ID、直链",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(2f / 3f),
                    textAlign = TextAlign.Center,
                )
            }
            return
        }

        val groups = listOf(
            ExtensionSource.USERSCRIPT to extensions.filter { it.source == ExtensionSource.USERSCRIPT.source },
            ExtensionSource.CHROME to extensions.filter { it.source == ExtensionSource.CHROME.source },
            ExtensionSource.EDGE to extensions.filter { it.source == ExtensionSource.EDGE.source },
        ).filter { it.second.isNotEmpty() }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            groups.forEach { (source, list) ->
                item(key = "hdr-${source.source}") { SourceHeader(source) }
                items(list, key = { it.id }) { ext ->
                    ExtensionCard(ext, viewModel)
                }
            }
        }
    }
}

/** 来源：分组分隔行的标题与图标。source 字段值对应 ExtensionEntity.source */
private enum class ExtensionSource(
    val source: String,
    val label: String,
    val iconRes: Int,
) {
    USERSCRIPT("USERSCRIPT", "油猴用户脚本", com.xverse.app.R.drawable.ic_brand_userscript),
    CHROME("CHROME", "Chrome 插件", com.xverse.app.R.drawable.ic_brand_chrome),
    EDGE("EDGE", "Edge 插件", com.xverse.app.R.drawable.ic_brand_edge),
}

/** 来源分组分隔行：品牌徽标 + 标签（跳过空分组） */
@Composable
private fun SourceHeader(source: ExtensionSource) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(source.iconRes),
            contentDescription = source.label,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = source.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 扩展卡片：图标 + 名称/版本 + 描述 + 开关 + 配置 + 卸载 */
@Composable
private fun ExtensionCard(ext: ExtensionEntity, viewModel: ExtensionsViewModel) {
    var showUninstallConfirm by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标：本地 icon.png；缺省扩展占位
            ExtensionIcon(ext)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 名称可长：weight(1f, fill=false) 让它在空间不足时优先被省略，
                    // 不会把「用户脚本」徽标挤出卡片边界压到开关/删除按钮下面
                    Text(
                        text = ext.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = "v${ext.version}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                    if (ext.source == ExtensionSource.USERSCRIPT.source) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = "油猴用户脚本",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                if (ext.description.isNotBlank()) {
                    Text(
                        text = ext.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Switch(
                    checked = ext.enabled,
                    onCheckedChange = { viewModel.setEnabled(ext, it) },
                )
                if (ext.optionsPage.isNotBlank()) {
                    TextButton(onClick = { viewModel.openOptions(ext) }) {
                        Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("配置")
                    }
                }
            }
            IconButton(onClick = { showUninstallConfirm = true }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "卸载",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showUninstallConfirm) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirm = false },
            title = { Text("卸载扩展") },
            text = { Text("确定要卸载「${ext.name}」吗？将删除其数据和文件。") },
            confirmButton = {
                TextButton(onClick = {
                    showUninstallConfirm = false
                    viewModel.uninstall(ext)
                }) {
                    Text("卸载")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }
}

/** 扩展图标：本地 icon.png 或占位（inSampleSize 缩容解码，避免大图标全尺寸占内存） */
@Composable
private fun ExtensionIcon(ext: ExtensionEntity) {
    var icon by remember(ext.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loaded by remember(ext.id) { mutableStateOf(false) }

    if (!loaded) {
        androidx.compose.runtime.LaunchedEffect(ext.id) {
            val bmp = if (ext.iconPath.isBlank()) null
            else try {
                val f = File(AppInstance.locator.extensionRuntime.extDir(ext.id), ext.iconPath)
                if (f.isFile) decodeSampled(f.absolutePath, 160) else null
            } catch (e: Exception) {
                null
            }
            icon = bmp
            loaded = true
        }
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = icon
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = ext.name,
                modifier = Modifier.size(40.dp),
            )
        } else {
            Icon(
                Icons.Filled.Extension,
                contentDescription = ext.name,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 导入对话框：文件 / 链接 双模式 */
@Composable
private fun ImportDialog(
    onDismiss: () -> Unit,
    onImportFile: () -> Unit,
    onImportUrl: (String) -> Unit,
    importing: Boolean,
    context: android.content.Context,
) {
    var mode by remember { mutableStateOf("url") }
    var input by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!importing) onDismiss() },
        title = { Text("导入扩展") },
        text = {
            Column {
                // 模式切换
                Row {
                    TextButton(onClick = { mode = "url" }) {
                        Text("从链接导入", color = if (mode == "url") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { mode = "file" }) {
                        Text("从文件选择", color = if (mode == "file") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                when (mode) {
                    "url" -> {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("商店链接 / 扩展 ID / .crx .zip .user.js 油猴脚本直链") },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "支持 Chrome/Edge 商店链接、裸扩展 ID，或 .crx/.zip/.user.js 油猴脚本直链",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    "file" -> {
                        Text(
                            text = "选择本机 .crx / .zip 扩展包或 .user.js 油猴用户脚本",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (mode == "url") {
                        val v = input.trim()
                        if (v.isNotEmpty()) onImportUrl(v)
                    } else {
                        onImportFile()
                    }
                },
                enabled = !importing,
            ) {
                Text("导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !importing) { Text("取消") }
        },
    )
}

/** 缩容解码图片：目标边长上限 [reqPx]，超出的图按 2 的幂采样，控制内存占用 */
private fun decodeSampled(path: String, reqPx: Int): android.graphics.Bitmap? {
    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, opts)
    var sample = 1
    while (opts.outWidth / (sample * 2) >= reqPx && opts.outHeight / (sample * 2) >= reqPx) {
        sample *= 2
    }
    return android.graphics.BitmapFactory.decodeFile(
        path,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

/** 配置页覆盖层：全屏 WebView 加载扩展 options 页 */
@Composable
private fun OptionsOverlay(ext: ExtensionEntity, onClose: () -> Unit) {
    val runtime = AppInstance.locator.extensionRuntime
    // 覆盖层顶到屏幕顶，需给状态栏留高，否则返回箭头落在状态栏下被点击吞掉
    Surface(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回扩展列表")
                }
                Text(
                    text = "${ext.name} · 配置",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            AndroidView(
                factory = { ctx -> runtime.newOptionsWebView(ctx, ext) },
                modifier = Modifier.fillMaxSize(),
                // 覆盖层关闭（解组）时销毁 WebView，避免实例常驻泄漏
                onRelease = { it.destroy() },
            )
        }
    }
}
