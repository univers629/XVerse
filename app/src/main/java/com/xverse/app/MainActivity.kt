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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.roundToInt

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
        val locator = (application as XVerseApp).locator
        // 绑定 Activity 生命周期：销毁即取消，避免协程泄漏
        lifecycleScope.launch {
            try {
                locator.extensionImporter.importFile(uri)
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
    val customMonetEnabled by mainViewModel.customMonetEnabled.collectAsState(initial = false)
    val customMonetColor by mainViewModel.customMonetColor.collectAsState(
        initial = com.xverse.app.core.data.repo.SettingsRepo.DEFAULT_MONET_COLOR_ARGB,
    )
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
    var selectedTab by remember { mutableStateOf(XTab.HOME) }
    var transitionDirection by remember { mutableIntStateOf(1) }
    var homeContentActive by remember { mutableStateOf(true) }

    fun navigateTo(destination: XTab) {
        if (destination == selectedTab) return
        transitionDirection = if (destination.ordinal > selectedTab.ordinal) 1 else -1
        selectedTab = destination
    }

    // WebView 在首页淡出完成后再隐藏，避免内容先于页面动画瞬间消失。
    LaunchedEffect(selectedTab) {
        if (selectedTab == XTab.HOME) {
            homeContentActive = true
        } else {
            delay(220.milliseconds)
            homeContentActive = false
        }
    }

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
        navTab?.let { destination ->
            navigateTo(destination)
            mainViewModel.clearNavTab()
        }
    }

    XVerseTheme(
        darkTheme = darkTheme,
        dynamicColor = !customMonetEnabled,
        seedColor = androidx.compose.ui.graphics.Color(customMonetColor),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp,
                    ) {
                        XTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = {
                                    if (selectedTab == tab) {
                                        // 重复点击当前 Tab：回到首页
                                        if (tab == XTab.HOME) mainViewModel.goHome()
                                    } else {
                                        navigateTo(tab)
                                    }
                                },
                                icon = {
                                    BadgedIcon(
                                        tab = tab,
                                        selected = selectedTab == tab,
                                    )
                                },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            )
                        }
                    }
                },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) { padding ->
                // 五个屏幕全部保持组合（首页 WebView 常驻保活），非活动 Tab 隐藏。
                // 首页单独处理：BrowserScreen 始终组合，active 参数控制 WebView visibility
                // （GONE 保留页面状态，不销毁不重载）；其余纯 Compose 页数据从 Room/
                // DataStore 重读，解组无状态损失。
                //
                // 切换动画：首页 WebView 用透明度淡入淡出（始终组合，不销毁实例）；
                // 其余页按底栏方向进行短距离位移，并叠加淡入淡出与轻微缩放。
                // zIndex 保证选中页盖在下方页上：动画进行中透明的新页需要挡在旧的
                // 实心页之上，否则下方页面会透过来（点透 / 视觉穿透）。
                Box(modifier = Modifier.fillMaxSize()) {
                    // 首页：常驻组合 + 透明度动画（不销毁 WebView）
                    androidx.compose.runtime.key(XTab.HOME) {
                        val homeAlpha by animateFloatAsState(
                            targetValue = if (selectedTab == XTab.HOME) 1f else 0f,
                            animationSpec = tween(
                                durationMillis = 220,
                                easing = FastOutSlowInEasing,
                            ),
                            label = "homeAlpha",
                        )
                        BrowserScreen(
                            mainViewModel,
                            Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .zIndex(if (selectedTab == XTab.HOME) 1f else 0f)
                                .alpha(homeAlpha),
                            active = selectedTab == XTab.HOME || homeContentActive,
                        )
                    }
                    // 其余页：非活动时 AnimatedVisibility 自动回收；选中 fade+scale 进场
                    XTab.entries.filter { it != XTab.HOME }.forEach { tab ->
                        androidx.compose.runtime.key(tab) {
                            AnimatedVisibility(
                                visible = selectedTab == tab,
                                enter = fadeIn(
                                    animationSpec = tween(
                                        durationMillis = 190,
                                        delayMillis = 20,
                                        easing = LinearOutSlowInEasing,
                                    ),
                                ) + slideInHorizontally(
                                    animationSpec = tween(
                                        durationMillis = 260,
                                        easing = FastOutSlowInEasing,
                                    ),
                                    initialOffsetX = { width ->
                                        (width * 0.055f).roundToInt() * transitionDirection
                                    },
                                ) + scaleIn(
                                    initialScale = 0.992f,
                                    animationSpec = tween(
                                        durationMillis = 260,
                                        easing = FastOutSlowInEasing,
                                    ),
                                ),
                                exit = fadeOut(
                                    animationSpec = tween(durationMillis = 140),
                                ) + slideOutHorizontally(
                                    animationSpec = tween(
                                        durationMillis = 190,
                                        easing = FastOutSlowInEasing,
                                    ),
                                    targetOffsetX = { width ->
                                        -(width * 0.025f).roundToInt() * transitionDirection
                                    },
                                ) + scaleOut(
                                    targetScale = 0.997f,
                                    animationSpec = tween(durationMillis = 180),
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
