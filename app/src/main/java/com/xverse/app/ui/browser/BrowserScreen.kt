package com.xverse.app.ui.browser

import android.view.ViewGroup
import android.webkit.WebChromeClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xverse.app.core.webview.XWebView
import com.xverse.app.core.search.XSearchFilterState
import kotlin.math.roundToInt

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
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val searchFavorites by viewModel.searchFavorites.collectAsStateWithLifecycle()
    var searchExpanded by remember { mutableStateOf(false) }
    var searchState by remember { mutableStateOf(XSearchFilterState()) }

    LaunchedEffect(active) {
        if (!active) searchExpanded = false
    }

    // 处理浏览器命令（跨 Tab 深链 / 首页）
    LaunchedEffect(viewModel) {
        com.xverse.app.CommandBus.commands.collect { cmd ->
            when (cmd) {
                is com.xverse.app.BrowserCommand.GoHome -> viewModel.goHome()
                is com.xverse.app.BrowserCommand.LoadUrl -> viewModel.loadUrl(cmd.url)
                is com.xverse.app.BrowserCommand.OpenTweet -> viewModel.openTweetInstant(cmd.url)
                com.xverse.app.BrowserCommand.Reload -> viewModel.reload()
                com.xverse.app.BrowserCommand.ReapplyInjections -> viewModel.reapplyInjections()
                is com.xverse.app.BrowserCommand.SetCcFilter -> viewModel.applyCcFilterSetting(cmd.on)
                is com.xverse.app.BrowserCommand.SetAiFilter -> viewModel.applyAiFilterSetting(cmd.on)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶栏占位：仅首页展示（其余 Tab 隐藏，避免导航按钮/登录徽章串页显示）
        if (active) {
            TopBar(
                viewModel = viewModel,
                loggedIn = loggedIn,
                searchExpanded = searchExpanded,
                onSearchToggle = { searchExpanded = !searchExpanded },
            )
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
                // Activity 销毁 / HOME tab 解组时释放 WebView，避免 native 资源泄漏
                onRelease = {
                    viewModel.onWebViewReleased(it)
                    it.destroy()
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
            androidx.compose.animation.AnimatedVisibility(
                visible = active && searchExpanded,
                enter = expandVertically(
                    animationSpec = tween(180),
                    expandFrom = Alignment.Top,
                ) + fadeIn(tween(120)),
                exit = shrinkVertically(
                    animationSpec = tween(140),
                    shrinkTowards = Alignment.Top,
                ) + fadeOut(tween(100)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(2f),
            ) {
                XSearchPanel(
                    state = searchState,
                    history = searchHistory,
                    favorites = searchFavorites,
                    onStateChange = { searchState = it },
                    onSearch = { query ->
                        searchExpanded = false
                        viewModel.executeSearch(query)
                    },
                    onSaveFavorite = viewModel::saveSearchFavorite,
                    onRemoveFavorite = viewModel::removeSearchFavorite,
                    onClearHistory = viewModel::clearSearchHistory,
                    onClose = { searchExpanded = false },
                )
            }
        }
    }
}

/** 顶部工具栏：后退 / 前进 / 刷新 / 首页 + 高级搜索 + 下载 + 登录状态 */
@Composable
private fun TopBar(
    viewModel: BrowserViewModel,
    loggedIn: Boolean,
    searchExpanded: Boolean,
    onSearchToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        EdgeBalancedTopBarRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        ) {
            IconButton(
                onClick = { viewModel.goBack() },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "后退")
            }
            IconButton(
                onClick = { viewModel.goForward() },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "前进")
            }
            IconButton(
                onClick = { viewModel.reload() },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
            IconButton(
                onClick = { viewModel.goHome() },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Filled.Home, contentDescription = "首页")
            }
            if (searchExpanded) {
                FilledTonalIconButton(
                    onClick = onSearchToggle,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = "收起高级搜索")
                }
            } else {
                IconButton(
                    onClick = onSearchToggle,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = "展开高级搜索")
                }
            }
            DownloadButton(viewModel)
            LoginChip(viewModel, loggedIn)
        }
    }
}

/**
 * 按可见轮廓而非点击区域中心排列：图标以 24dp 图形宽度参与间距计算，
 * 登录胶囊则以完整宽度参与计算。这样首个图标左缘与登录胶囊右缘的屏幕边距一致。
 */
@Composable
private fun EdgeBalancedTopBarRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(modifier = modifier, content = content) { measurables, constraints ->
        val placeables = measurables.map { measurable ->
            measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        }
        if (placeables.isEmpty()) {
            return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        }

        val iconVisualWidth = 24.dp.roundToPx()
        val visualWidths = placeables.mapIndexed { index, placeable ->
            if (index < placeables.lastIndex) iconVisualWidth else placeable.width
        }
        val layoutWidth = constraints.maxWidth
        val layoutHeight = placeables.maxOf { it.height }.coerceIn(constraints.minHeight, constraints.maxHeight)
        val freeSpace = (layoutWidth - visualWidths.sum()).coerceAtLeast(0)
        val gap = freeSpace.toFloat() / (placeables.size + 1)

        layout(layoutWidth, layoutHeight) {
            var visibleLeft = gap
            placeables.forEachIndexed { index, placeable ->
                val visualWidth = visualWidths[index]
                val visualInset = (placeable.width - visualWidth) / 2f
                val x = (visibleLeft - visualInset).roundToInt()
                val y = (layoutHeight - placeable.height) / 2
                placeable.placeRelative(x, y)
                visibleLeft += visualWidth + gap
            }
        }
    }
}

/**
 * 顶栏下载按钮：点击弹出媒体选择下拉菜单。
 * 解析当前推文媒体（GraphQL 缓存优先，兜底页面 HTML），列出清晰度+大小，点击即入队下载。
 * 菜单宽度限制为屏幕 2/3，避免长文件名撑满屏幕；右边缘固定对齐下载按钮，向左展开。
 */
@Composable
private fun DownloadButton(viewModel: BrowserViewModel, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val mediaList by viewModel.mediaList.collectAsStateWithLifecycle()
    val parsing by viewModel.parsing.collectAsStateWithLifecycle()
    // 菜单宽度上限：屏幕宽度的 2/3
    val maxWidth = with(LocalConfiguration.current) {
        (screenWidthDp * 2f / 3f).toInt().dp
    }

    Box(modifier = modifier) {
        IconButton(
            modifier = Modifier.size(48.dp),
            onClick = {
                if (!expanded) viewModel.refreshMediaList()
                expanded = !expanded
            },
        ) {
            Icon(Icons.Filled.Download, contentDescription = "下载媒体")
        }
        LeftExpandingMenu(
            expanded = expanded,
            maxWidth = maxWidth,
            onDismissRequest = { expanded = false },
        ) {
                // 内容随状态交叉渐变：解析中→列表淡入淡出，不闪烁
                Crossfade(
                    targetState = if (parsing) "parsing" else if (mediaList.isEmpty()) "empty" else "list",
                    animationSpec = tween(120),
                    label = "downloadMenuState",
                ) { state ->
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
                                text = { Text(mediaLabel(item, idx)) },
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

/** 将菜单右边缘固定在锚点右边缘，避免系统因右侧空间不足而切换定位方向。 */
private class LeftExpandingMenuPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        return IntOffset(
            x = (anchorBounds.right - popupContentSize.width).coerceIn(0, maxX),
            y = anchorBounds.bottom,
        )
    }
}

@Composable
private fun LeftExpandingMenu(
    expanded: Boolean,
    maxWidth: androidx.compose.ui.unit.Dp,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val visibilityState = remember { MutableTransitionState(false) }
    visibilityState.targetState = expanded

    if (visibilityState.currentState || visibilityState.targetState) {
        Popup(
            popupPositionProvider = remember { LeftExpandingMenuPositionProvider() },
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true),
        ) {
            AnimatedVisibility(
                visibleState = visibilityState,
                enter = fadeIn(
                    animationSpec = tween(
                        durationMillis = 160,
                        easing = LinearOutSlowInEasing,
                    ),
                ) + expandVertically(
                    animationSpec = tween(
                        durationMillis = 220,
                        easing = FastOutSlowInEasing,
                    ),
                    expandFrom = Alignment.Top,
                    initialHeight = { height -> (height * 0.82f).roundToInt() },
                ) + scaleIn(
                    initialScale = 0.96f,
                    transformOrigin = TransformOrigin(1f, 0f),
                    animationSpec = tween(
                        durationMillis = 220,
                        easing = FastOutSlowInEasing,
                    ),
                ),
                exit = fadeOut(tween(110)) + shrinkVertically(
                    animationSpec = tween(
                        durationMillis = 160,
                        easing = FastOutSlowInEasing,
                    ),
                    shrinkTowards = Alignment.Top,
                    targetHeight = { height -> (height * 0.9f).roundToInt() },
                ) + scaleOut(
                    targetScale = 0.98f,
                    transformOrigin = TransformOrigin(1f, 0f),
                    animationSpec = tween(durationMillis = 140),
                ),
            ) {
                Surface(
                    modifier = Modifier.widthIn(max = maxWidth),
                    shape = MaterialTheme.shapes.extraSmall,
                    tonalElevation = 3.dp,
                    shadowElevation = 3.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                            .width(IntrinsicSize.Max),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

/** 媒体行文案：所有类型均显示序号、清晰度和大小。 */
private fun mediaLabel(item: com.xverse.app.core.download.MediaItem, idx: Int): String {
    val type = when (item.mediaType) {
        "photo" -> "图片"
        "gif" -> "GIF"
        else -> "视频"
    }
    val label = item.quality.ifBlank { if (item.mediaType == "photo") "原图" else "原始" }
    val size = if (item.size > 0) " · ${fmtSize(item.size)}" else ""
    return "$type ${idx + 1} · $label$size"
}

private fun fmtSize(b: Long): String {
    if (b <= 0) return ""
    return if (b > 1048576) "%.1f MB".format(b / 1048576.0) else "${b / 1024} KB"
}

/** 登录状态徽章：未登录点击 → WebView 内打开登录页；已登录点击 → 确认后登出（MD3 弹窗） */
@Composable
private fun LoginChip(viewModel: BrowserViewModel, loggedIn: Boolean, modifier: Modifier = Modifier) {
    var showLogoutConfirm by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier,
        onClick = {
            if (loggedIn) {
                showLogoutConfirm = true
            } else {
                viewModel.startLogin()
            }
        },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Text(
            text = if (loggedIn) "已登录" else "未登录",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showLogoutConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            shape = RoundedCornerShape(16.dp),
            title = { Text("登出") },
            text = { Text("确定要退出登录吗？将清除 x.com 的登录 Cookie。") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    viewModel.logout()
                }) {
                    Text("登出")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }
}
