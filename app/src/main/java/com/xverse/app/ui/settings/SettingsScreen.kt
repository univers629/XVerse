package com.xverse.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.xverse.app.AppInstance
import com.xverse.app.CommandBus
import kotlinx.coroutines.launch

/**
 * 设置页：外观 / 数据 / 过滤 / 下载 / 账户 / 开发 / 关于。
 * 日志收进本页做二级页面（不再占用底栏 Tab）。
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
) {
    // 二级页路由：null=设置主列表，"logs"=运行日志页
    var sub by remember { mutableStateOf<String?>(null) }

    if (sub == "logs") {
        LogsSubPage(onBack = { sub = null })
        return
    }

    val scope = rememberCoroutineScope()
    val settings = AppInstance.locator.settings

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        SectionTitle("外观")
        val themeMode by settings.themeMode.collectAsState(initial = "system")
        ThemeDropdown(
            selected = themeMode,
            onSelect = { v -> scope.launch { settings.setThemeMode(v) } },
        )

        SectionTitle("数据")
        val historyEnabled by settings.historyEnabled.collectAsState(initial = true)
        SwitchSetting("记录浏览历史", historyEnabled) {
            scope.launch { settings.setHistoryEnabled(it) }
        }

        SectionTitle("过滤")
        val filterEnabled by settings.filterEnabled.collectAsState(initial = true)
        SwitchSetting("启用广告过滤", filterEnabled) {
            scope.launch { settings.setFilterEnabled(it) }
        }

        // 过滤方式：mask=占位+可点击验证 / strip=完全不加载广告
        // 任一方向切换都会改变 document_start 注入组合（strip 是网络层拦截，无法热注入），
        // 切换后重建注入 + reload 首页才生效。
        val filterMode by settings.filterMode.collectAsState(initial = "mask")
        if (filterEnabled) {
            FilterModeDropdown(
                selected = filterMode,
                onSelect = { v ->
                    scope.launch {
                        settings.setFilterMode(v)
                        CommandBus.push(com.xverse.app.BrowserCommand.ReapplyInjections)
                    }
                },
            )
        }

        // 过滤带字幕（CC）视频：广告过滤子项。检测在 mutation 层播放器轮询内
        // （video.textTracks.length > 0），开关只热更新页面标记 → 无需重建注入/reload。
        val filterCcVideos by settings.filterCcVideos.collectAsState(initial = true)
        if (filterEnabled) {
            SwitchSetting("内置：过滤带字幕（CC）视频", filterCcVideos, subRow = true) { on ->
                scope.launch {
                    settings.setFilterCcVideos(on)
                    CommandBus.push(com.xverse.app.BrowserCommand.SetCcFilter(on))
                }
            }
        }

        // 过滤 AI 生成标签（Made with AI）：广告过滤子项（CC 行下方）。
        // 检测在 mutation 层 scan 内（叶子 span 精确匹配），开关热更新标记 → 无需重载。
        val filterAiLabel by settings.filterAiLabel.collectAsState(initial = true)
        if (filterEnabled) {
            SwitchSetting("内置：过滤 AI 生成标签", filterAiLabel, subRow = true) { on ->
                scope.launch {
                    settings.setFilterAiLabel(on)
                    CommandBus.push(com.xverse.app.BrowserCommand.SetAiFilter(on))
                }
            }
        }

        // 过滤规则列表：内置词开关 → 自定义屏蔽词
        FilterRulesSection()

        SectionTitle("下载")
        val downloadNotify by settings.downloadNotify.collectAsState(initial = true)
        SwitchSetting("下载完成通知", downloadNotify) {
            scope.launch { settings.setDownloadNotify(it) }
        }

        SectionTitle("账户")
        AccountSection()

        SectionTitle("开发")
        // 日志二级页入口
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { sub = "logs" }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "查看运行日志",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "分类筛选 / 导出",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionTitle("关于")
        Text(
            text = "XVerse 0.3.0\n仅面向 x.com 网页版。纯增强壳，不代理流量。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** 日志二级页：返回箭头 + 标题 + 复用 LogsScreen（分类筛选 + 导出，无需改动） */
@Composable
private fun LogsSubPage(onBack: () -> Unit) {
    // 二级页顶到屏幕顶，需给状态栏留高，否则标题/返回箭头被状态栏遮住
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置")
            }
            Text(
                text = "运行日志",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        com.xverse.app.ui.logs.LogsScreen(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 4.dp),
        )
    }
}

/**
 * 账户分组：显示登录状态；未登录 → Custom Tab 登录入口；
 * 已登录 → 登出入口。
 */
@Composable
private fun AccountSection() {
    val scope = rememberCoroutineScope()
    val locator = AppInstance.locator
    val isLoggedIn by locator.authController.loggedIn.collectAsState()
    var showLogoutConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (isLoggedIn) {
                    // 已登录 → 确认登出（MD3 弹窗）
                    showLogoutConfirm = true
                } else {
                    // 未登录 → 切回首页并打开 WebView 内登录页
                    // 只发 LoadUrl：整页加载登录页，无需先 GoHome（避免抢占 WebView 竞态）
                    CommandBus.selectTab(com.xverse.app.ui.navigation.XTab.HOME)
                    CommandBus.push(
                        com.xverse.app.BrowserCommand.LoadUrl(
                            com.xverse.app.core.util.Constants.LOGIN_URL
                        )
                    )
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isLoggedIn) "已登录" else "未登录",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = if (isLoggedIn) "点击登出" else "点击登录",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("登出") },
            text = { Text("确定要退出登录吗？将清除 x.com 的登录 Cookie。") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    scope.launch {
                        // 清 Cookie（统一入口）
                        locator.authController.logout(null)
                    }
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

@Composable
private fun SectionTitle(title: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * 颜色模式下拉：设置行样式 —— 左侧「颜色模式」标签 + 右侧按钮（当前值 + 箭头）。
 * DropdownMenu 的 popup 锚定右侧按钮的 wrap-content Box，随按钮靠右落下，从右侧展开。
 * 不带输入框边框，与其它设置行（如「记录浏览历史」）视觉一致。
 */
@Composable
private fun ThemeDropdown(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(
        "system" to "跟随系统",
        "light" to "浅色模式",
        "dark" to "深色模式",
    )
    val label = options.firstOrNull { it.first == selected }?.second ?: "跟随系统"
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "颜色模式",
            modifier = Modifier.weight(1f),
        )
        // 右侧按钮锚点：DropdownMenu 的 Popup 锚定此 Box（wrap content，即按钮本身），
        // 菜单左缘对齐按钮左缘、落在按钮下方 → 随按钮靠右显示，从右侧展开
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 过滤方式下拉：mask=占位+可点击验证 / strip=完全不加载广告。
 * strip 是 document_start 网络层拦截，无法热注入——切换到 strip 后自动重载当前页生效。
 * 视觉与颜色模式下拉一致（左侧标签 + 右侧按钮）。
 */
@Composable
private fun FilterModeDropdown(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(
        "mask" to "占位 + 可点击验证",
        "strip" to "完全不加载广告",
    )
    val label = options.firstOrNull { it.first == selected }?.second ?: "占位 + 可点击验证"
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "过滤方式",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        },
                    )
                }
            }
        }
    }
}

/** 设置开关行。普通行 bodyMedium；[subRow] 子行（CC/AI 等广告过滤子项）与内置屏蔽词行一致：bodySmall 小字 + 相同间距 */
@Composable
private fun SwitchSetting(
    label: String,
    checked: Boolean,
    subRow: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 16.dp,
                vertical = if (subRow) 4.dp else 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = if (subRow) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 过滤规则列表：内置词开关（上方）→ 自定义屏蔽词（下方） */
@Composable
private fun FilterRulesSection() {
    val scope = rememberCoroutineScope()
    val rules by AppInstance.locator.filterRepo.observeAll()
        .collectAsState(initial = emptyList())

    val builtinRules = rules.filter { it.builtin }
    val userRules = rules.filter { !it.builtin }

    // 内置词开关（如「内置：推广关键词（中英）」）
    builtinRules.forEach { rule ->
        FilterRuleRow(rule)
    }

    // 自定义屏蔽词：标题 + 添加输入 + 用户规则列表
    Text(
        text = "自定义屏蔽词",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    )

    // 屏蔽词输入
    var keyword by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("添加屏蔽词（回车添加）") },
            singleLine = true,
            shape = RoundedCornerShape(999.dp),
        )
        IconButton(
            onClick = {
                val kw = keyword.trim()
                if (kw.isNotEmpty()) {
                    scope.launch {
                        AppInstance.locator.filterRepo.insert(
                            com.xverse.app.core.data.db.FilterRule(
                                type = com.xverse.app.core.data.db.RuleType.REGEX,
                                pattern = kw,
                                enabled = true,
                                source = "user",
                                description = "屏蔽词：$kw",
                            )
                        )
                        keyword = ""
                    }
                }
            },
            enabled = keyword.isNotBlank(),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "添加屏蔽词")
        }
    }

    userRules.forEach { rule ->
        FilterRuleRow(rule)
    }
}

/** 单条规则行：描述 + 开关 + 删除（仅非内置） */
@Composable
private fun FilterRuleRow(rule: com.xverse.app.core.data.db.FilterRule) {
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rule.description.ifBlank { rule.pattern },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
        Switch(
            checked = rule.enabled,
            onCheckedChange = { enabled ->
                scope.launch {
                    AppInstance.locator.filterRepo.setEnabled(rule.id, enabled)
                }
            },
        )
        if (!rule.builtin) {
            IconButton(onClick = {
                scope.launch { AppInstance.locator.filterRepo.delete(rule.id) }
            }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "删除规则",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
