package com.xverse.app.ui.settings

import android.app.Activity
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
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

        // 过滤规则列表
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
            text = "XVerse 0.2.0\n仅面向 x.com 网页版。纯增强壳，不代理流量。",
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
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val activity = context as? Activity
                if (isLoggedIn) {
                    // 已登录 → 确认登出
                    android.app.AlertDialog.Builder(activity ?: return@clickable)
                        .setTitle("登出")
                        .setMessage("确定要退出登录吗？将清除 x.com 的登录 Cookie。")
                        .setPositiveButton("登出") { _, _ ->
                            scope.launch {
                                // 清 Cookie（统一入口）
                                locator.authController.logout(null)
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
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

@Composable
private fun SwitchSetting(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 过滤规则列表：添加屏蔽词 + 规则开关 + 删除（用户规则） */
@Composable
private fun FilterRulesSection() {
    val scope = rememberCoroutineScope()
    val rules by AppInstance.locator.filterRepo.observeAll()
        .collectAsState(initial = emptyList())

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

    rules.forEach { rule ->
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
}
