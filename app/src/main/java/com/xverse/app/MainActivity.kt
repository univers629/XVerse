package com.xverse.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import com.xverse.app.MainViewModel
import com.xverse.app.core.log.LogCategory
import com.xverse.app.core.log.LogStore
import com.xverse.app.ui.browser.BrowserScreen
import com.xverse.app.ui.download.DownloadScreen
import com.xverse.app.ui.extensions.ExtensionsScreen
import com.xverse.app.ui.history.HistoryScreen
import com.xverse.app.ui.navigation.XTab
import com.xverse.app.ui.settings.SettingsScreen
import com.xverse.app.ui.theme.XVerseTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** adb 切换探针模式的广播 action */
const val ACTION_PROBE_MODE = "com.xverse.app.PROBE"

/**
 * 单 Activity：Scaffold + NavigationBar 底栏。
 * Tab 切换用 `keepActive`（visibility 方案），WebView 不销毁、页面状态保留。
 */
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels { MainViewModel.Factory }

    // 下载完成/前台通知需要运行时授权（Android 13+）
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 用户选择后无需额外处理 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 请求通知权限（仅 33+ 需要）
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setContent {
            MainScreen(mainViewModel)
        }
        // 深链：首次启动携带 VIEW intent（如外部点击 x.com 链接）
        handleViewIntent(intent)
        registerProbeReceiver()
    }

    /**
     * adb 切换探针模式：重建 WebView 使注入生效。
     * 33+ 必须用 RECEIVER_EXPORTED：adb shell 广播来自外部 UID，NOT_EXPORTED 会整包拦截。
     * 这是临时调试入口（只切换探针开关），不暴露敏感能力，可接受导出。
     */
    private fun registerProbeReceiver() {
        val filter = android.content.IntentFilter(ACTION_PROBE_MODE)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(probeReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(probeReceiver, filter)
        }
    }

    private val probeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            val mode = intent.getStringExtra("mode")
            if (mode == null) return
            val on = mode == "on"
            // 探针模式：BrowserScreen 收到命令后置 BrowserViewModel.probeMode + 重建 WebView
            CommandBus.push(BrowserCommand.SetProbeMode(on))
            Toast.makeText(this@MainActivity, "探针模式 ${if (on) "开" else "关"}，重建 WebView…", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(probeReceiver) } catch (_: Exception) {}
    }

    /** 深链 / 外部 VIEW intent：转发给浏览器加载 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            val scheme = uri.scheme
            val isFile = scheme == "file" || scheme == "content"
            // 扩展包（.crx/.zip）：导入扩展并切到扩展页
            if (isFile) {
                val mime = intent.type ?: ""
                val isCrx = mime.contains("chrome-extension") || mime == "application/zip" ||
                    mime == "application/octet-stream" ||
                    uri.toString().endsWith(".crx", ignoreCase = true) ||
                    uri.toString().endsWith(".zip", ignoreCase = true)
                if (isCrx) {
                    importExtension(uri)
                    return
                }
                return
            }
            // 普通 http(s) 深链：交给浏览器加载
            val url = uri.toString()
            if (url.startsWith("http://") || url.startsWith("https://")) {
                mainViewModel.openDeepLink(url)
            }
        }
    }

    /** 文件管理器「用 XVerse 打开」的扩展包 → 导入 + 切扩展页 + Toast */
    private fun importExtension(uri: android.net.Uri) {
        val locator = AppInstance.locator
        // 绑定 Activity 生命周期：销毁即取消，避免协程泄漏
        lifecycleScope.launch {
            try {
                val extId = locator.extensionImporter.importFile(uri)
                Toast.makeText(this@MainActivity, "扩展已导入", Toast.LENGTH_SHORT).show()
                mainViewModel.selectTab(XTab.EXTENSIONS)
            } catch (e: Exception) {
                LogStore.log(LogCategory.FILTER, "扩展导入失败: ${e.message}")
                Toast.makeText(
                    this@MainActivity,
                    "导入失败：${e.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}

@Composable
fun MainScreen(mainViewModel: MainViewModel) {
    val themeMode by mainViewModel.themeMode.collectAsState(initial = "system")
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
    var selectedTab by remember { mutableStateOf(XTab.HOME) }

    // 状态栏图标颜色跟随应用主题（enableEdgeToEdge 只按系统深浅自动，应用可独立设置主题）：
    // 应用浅色 → 深色图标；应用深色 → 浅色图标。避免「浅色背景 + 白色图标」白底白字不可见。
    // SideEffect 在每次 darkTheme 变化（设置页切主题）后重设，处理 app 与系统主题不一致。
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.SideEffect {
        val window = (context as? android.app.Activity)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    // 由 MainViewModel 驱动（历史页点击回跳等跨 Tab 导航）
    val navTab by mainViewModel.navTab.collectAsState(initial = null)
    LaunchedEffect(navTab) {
        if (navTab != null) {
            selectedTab = navTab!!
            mainViewModel.clearNavTab()
        }
    }

    XVerseTheme(
        darkTheme = darkTheme,
        dynamicColor = true,
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        XTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = {
                                    if (selectedTab == tab) {
                                        // 重复点击当前 Tab：回到首页
                                        if (tab == XTab.HOME) mainViewModel.goHome()
                                    } else {
                                        selectedTab = tab
                                    }
                                },
                                icon = {
                                    BadgedIcon(
                                        tab = tab,
                                        selected = selectedTab == tab,
                                    )
                                },
                                label = { androidx.compose.material3.Text(tab.label) },
                            )
                        }
                    }
                },
                contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            ) { padding ->
                // 五个屏幕全部保持组合（首页 WebView 常驻保活），非活动 Tab 隐藏。
                // 首页单独处理：BrowserScreen 始终组合，active 参数控制 WebView visibility
                // （GONE 保留页面状态，不销毁不重载）；其余纯 Compose 页数据从 Room/
                // DataStore 重读，解组无状态损失。
                //
                // 切换动画：首页 WebView 用透明度淡入淡出（始终组合，只变 alpha，
                // WebView 实例不销毁）；其余页用 AnimatedVisibility fade+scale 进出场。
                // zIndex 保证选中页盖在下方页上：动画进行中透明的新页需要挡在旧的
                // 实心页之上，否则下方页面会透过来（点透 / 视觉穿透）。
                Box(modifier = Modifier.fillMaxSize()) {
                    // 首页：常驻组合 + 透明度动画（不销毁 WebView）
                    androidx.compose.runtime.key(XTab.HOME) {
                        val homeAlpha by animateFloatAsState(
                            targetValue = if (selectedTab == XTab.HOME) 1f else 0f,
                            animationSpec = tween(durationMillis = 220),
                            label = "homeAlpha",
                        )
                        BrowserScreen(
                            mainViewModel,
                            Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .zIndex(if (selectedTab == XTab.HOME) 1f else 0f)
                                .alpha(homeAlpha),
                            active = selectedTab == XTab.HOME,
                        )
                    }
                    // 其余页：非活动时 AnimatedVisibility 自动回收；选中 fade+scale 进场
                    XTab.entries.filter { it != XTab.HOME }.forEach { tab ->
                        androidx.compose.runtime.key(tab) {
                            AnimatedVisibility(
                                visible = selectedTab == tab,
                                enter = fadeIn(tween(200)) + scaleIn(
                                    initialScale = 0.96f,
                                    animationSpec = tween(200),
                                ),
                                exit = fadeOut(tween(150)) + scaleOut(
                                    targetScale = 0.98f,
                                    animationSpec = tween(150),
                                ),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(if (selectedTab == tab) 1f else 0f),
                            ) {
                                when (tab) {
                                    XTab.HISTORY -> HistoryScreen(mainViewModel, modifier = Modifier.fillMaxSize().padding(padding).statusBarsPadding())
                                    XTab.DOWNLOAD -> DownloadScreen(mainViewModel, Modifier.fillMaxSize().padding(padding).statusBarsPadding())
                                    XTab.EXTENSIONS -> ExtensionsScreen(Modifier.fillMaxSize().padding(padding).statusBarsPadding())
                                    else -> SettingsScreen(Modifier.fillMaxSize().padding(padding).statusBarsPadding())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgedIcon(tab: XTab, selected: Boolean) {
    val icon = if (selected) tab.selectedIcon else tab.icon
    androidx.compose.material3.Icon(
        imageVector = icon,
        contentDescription = tab.label,
    )
}
