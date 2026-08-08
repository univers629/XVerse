package com.xverse.app.ui.browser

import android.view.ViewGroup
import android.webkit.WebChromeClient
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xverse.app.core.webview.XWebView

/**
 * 浏览器页：顶部工具栏（占位式，不遮挡页面）+ 下方整屏 XWebView + 底部加载进度条。
 *
 * [active]：是否当前展示 Tab。false 时 WebView 用 GONE 隐藏 —— 实例与页面状态保留
 * （不销毁、切回不重载），仅不可见不可交互。
 */
@Composable
fun BrowserScreen(
    mainViewModel: com.xverse.app.MainViewModel,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val viewModel: BrowserViewModel = viewModel(factory = BrowserViewModel.Factory)
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val loggedIn by viewModel.loggedIn.collectAsStateWithLifecycle()

    // 处理浏览器命令（跨 Tab 深链 / 首页）
    LaunchedEffect(viewModel) {
        com.xverse.app.CommandBus.commands.collect { cmd ->
            cmd?.let {
                when (it) {
                    is com.xverse.app.BrowserCommand.GoHome -> viewModel.goHome()
                    is com.xverse.app.BrowserCommand.LoadUrl -> viewModel.loadUrl(it.url)
                    is com.xverse.app.BrowserCommand.OpenTweet -> viewModel.openTweetInstant(it.url)
                    com.xverse.app.BrowserCommand.Reload -> viewModel.reload()
                    is com.xverse.app.BrowserCommand.SetProbeMode -> viewModel.enterProbeMode(it.on)
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶栏占位：仅首页展示（其余 Tab 隐藏，避免导航按钮/登录徽章串页显示）
        if (active) {
            TopBar(viewModel, loggedIn)
        }
        // WebView 区域：铺满顶栏以下剩余空间
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    val wv = XWebView(ctx)
                    wv.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    wv.setChromeClient(object : WebChromeClient() {
                        override fun onProgressChanged(view: android.webkit.WebView, newProgress: Int) {
                            viewModel.onProgress(newProgress)
                        }

                        // JS console → app 日志（只保留 [XV] 诊断与 ERROR 级，避免被 x.com 噪音刷屏）
                        override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                            val m = msg.message()
                            val isError = msg.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR
                            if (!isError && !m.contains("[XV]")) return true
                            val src = if (msg.sourceId().isNotEmpty()) {
                                " @ ${msg.sourceId()}:${msg.lineNumber()}"
                            } else ""
                            com.xverse.app.core.log.LogStore.log(
                                com.xverse.app.core.log.LogCategory.WEBVIEW,
                                "[JS] $m$src",
                            )
                            return true
                        }
                    })
                    viewModel.onWebViewReady(wv)
                    // 首次加载：挂起深链或首页
                    viewModel.loadInitial()
                    wv
                },
                update = { wv ->
                    // 非活动 Tab：GONE 隐藏（保状态），活动恢复 VISIBLE
                    wv.visibility = if (active) android.view.View.VISIBLE else android.view.View.GONE
                },
                modifier = Modifier.fillMaxSize(),
            )
            // 底部加载进度条（浮动覆盖）
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 顶部工具栏：后退 / 前进 / 刷新 / 首页 + 下载 + 登录状态 */
@Composable
private fun TopBar(viewModel: BrowserViewModel, loggedIn: Boolean) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { viewModel.goBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "后退")
            }
            IconButton(onClick = { viewModel.goForward() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "前进")
            }
            IconButton(onClick = { viewModel.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = { viewModel.goHome() }) {
                Icon(Icons.Filled.Home, contentDescription = "首页")
            }
            Spacer(Modifier.weight(1f))
            DownloadButton(viewModel)
            LoginChip(viewModel, loggedIn)
        }
    }
}

/**
 * 顶栏下载按钮：点击弹出媒体选择下拉菜单。
 * 解析当前推文媒体（GraphQL 缓存优先，兜底页面 HTML），列出清晰度+大小，点击即入队下载。
 * 菜单宽度限制为屏幕 2/3，避免长文件名撑满屏幕。
 */
@Composable
private fun DownloadButton(viewModel: BrowserViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val mediaList by viewModel.mediaList.collectAsStateWithLifecycle()
    val parsing by viewModel.parsing.collectAsStateWithLifecycle()
    // 菜单宽度上限：屏幕宽度的 2/3
    val maxWidth = with(LocalConfiguration.current) {
        (screenWidthDp * 2f / 3f).toInt().dp
    }

    Box {
        IconButton(
            onClick = {
                if (!expanded) viewModel.refreshMediaList()
                expanded = !expanded
            },
        ) {
            Icon(Icons.Filled.Download, contentDescription = "下载媒体")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(max = maxWidth),
        ) {
            // 内容随状态交叉渐变：解析中→列表淡入淡出，不闪烁
            Crossfade(targetState = if (parsing) "parsing" else if (mediaList.isEmpty()) "empty" else "list") { state ->
                when (state) {
                    "parsing" -> DropdownMenuItem(
                        text = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        },
                        onClick = {},
                    )
                    "empty" -> DropdownMenuItem(
                        text = { Text("未解析到媒体") },
                        onClick = { expanded = false },
                    )
                    else -> mediaList.forEachIndexed { idx, item ->
                        DropdownMenuItem(
                            text = { Text(mediaLabel(item, idx, mediaList)) },
                            onClick = {
                                expanded = false
                                viewModel.enqueueMedia(item)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 媒体行文案：图片带序号；视频/动图显示清晰度 + 大小 */
private fun mediaLabel(item: com.xverse.app.core.download.MediaItem, idx: Int, list: List<com.xverse.app.core.download.MediaItem>): String {
    val isPhoto = list.all { it.extension == "jpg" }
    val label = item.quality.ifBlank { "原画" }
    val size = if (item.size > 0) " · ${fmtSize(item.size)}" else ""
    return if (isPhoto) "图片 ${idx + 1} · $label$size" else "$label$size"
}

private fun fmtSize(b: Long): String {
    if (b <= 0) return ""
    return if (b > 1048576) "%.1f MB".format(b / 1048576.0) else "${b / 1024} KB"
}

/** 登录状态徽章：未登录点击 → WebView 内打开登录页；已登录点击 → 登出 */
@Composable
private fun LoginChip(viewModel: BrowserViewModel, loggedIn: Boolean) {
    val context = LocalContext.current
    Surface(
        onClick = {
            if (loggedIn) {
                viewModel.confirmLogout(context)
            } else {
                viewModel.startLogin()
            }
        },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = if (loggedIn) "已登录" else "未登录",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
