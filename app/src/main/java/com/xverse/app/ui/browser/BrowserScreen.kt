package com.xverse.app.ui.browser

import android.view.ViewGroup
import android.webkit.WebChromeClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** 顶部工具栏：后退 / 前进 / 刷新 / 首页 + 登录状态 */
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
                Icon(Icons.Filled.ArrowBack, contentDescription = "后退")
            }
            IconButton(onClick = { viewModel.goForward() }) {
                Icon(Icons.Filled.ArrowForward, contentDescription = "前进")
            }
            IconButton(onClick = { viewModel.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = { viewModel.goHome() }) {
                Icon(Icons.Filled.Home, contentDescription = "首页")
            }
            Spacer(Modifier.weight(1f))
            LoginChip(viewModel, loggedIn)
        }
    }
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
