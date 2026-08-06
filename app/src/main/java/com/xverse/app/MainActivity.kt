package com.xverse.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import com.xverse.app.MainViewModel
import com.xverse.app.ui.browser.BrowserScreen
import com.xverse.app.ui.download.DownloadScreen
import com.xverse.app.ui.history.HistoryScreen
import com.xverse.app.ui.logs.LogsScreen
import com.xverse.app.ui.navigation.XTab
import com.xverse.app.ui.settings.SettingsScreen
import com.xverse.app.ui.theme.XVerseTheme

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
    }

    /** 深链 / 外部 VIEW intent：转发给浏览器加载 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val url = intent.data?.toString()
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                mainViewModel.openDeepLink(url)
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

    // 由 MainViewModel 驱动（历史页点击回跳等跨 Tab 导航）
    val navTab by mainViewModel.navTab.collectAsState(initial = null)
    LaunchedEffect(navTab) {
        if (navTab != null) {
            selectedTab = navTab!!
            mainViewModel.clearNavTab()
        }
    }

    // 未读角标
    val historyUnread by mainViewModel.historyUnread.collectAsState(initial = 0)
    val logUnread by mainViewModel.logUnread.collectAsState(initial = 0)

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
                                    if (tab == XTab.HISTORY || tab == XTab.LOGS) {
                                        mainViewModel.markRead(tab)
                                    }
                                },
                                icon = {
                                    BadgedIcon(
                                        tab = tab,
                                        selected = selectedTab == tab,
                                        historyUnread = historyUnread,
                                        logUnread = logUnread,
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
                // DataStore 重读，解组无状态损失，非活动用 Spacer 占位防重叠。
                XTab.entries.forEach { tab ->
                    androidx.compose.runtime.key(tab) {
                        // 首页有自己的顶栏（内部已处理状态栏 inset）；
                        // 其余页无顶栏，需避开透明状态栏
                        val modifier = if (tab == XTab.HOME) {
                            Modifier
                                .fillMaxSize()
                                .padding(padding)
                        } else {
                            Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .statusBarsPadding()
                        }
                        if (tab == XTab.HOME) {
                            BrowserScreen(mainViewModel, modifier, active = selectedTab == XTab.HOME)
                        } else if (selectedTab == tab) {
                            when (tab) {
                                XTab.HISTORY -> HistoryScreen(mainViewModel, modifier)
                                XTab.DOWNLOAD -> DownloadScreen(mainViewModel, modifier)
                                XTab.LOGS -> LogsScreen(modifier)
                                else -> SettingsScreen(modifier)
                            }
                        } else {
                            // 非活动 Tab：空占位（不参与绘制/触摸）
                            androidx.compose.foundation.layout.Spacer(modifier)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgedIcon(tab: XTab, selected: Boolean, historyUnread: Int, logUnread: Int) {
    val count = when (tab) {
        XTab.HISTORY -> historyUnread
        XTab.LOGS -> logUnread
        else -> 0
    }
    val icon = if (selected) tab.selectedIcon else tab.icon
    if (count > 0) {
        androidx.compose.material3.Badge {
            androidx.compose.material3.Text(if (count > 99) "99+" else count.toString())
        }
    }
    androidx.compose.material3.Icon(
        imageVector = icon,
        contentDescription = tab.label,
    )
}
