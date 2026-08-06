package com.xverse.app.ui.settings

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xverse.app.AppInstance
import com.xverse.app.CommandBus
import kotlinx.coroutines.launch

/**
 * 设置页：外观 / 数据 / 过滤 / 账户 / 下载 / 关于。
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
) {
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
        RowSetting("跟随系统", selected = themeMode == "system") {
            scope.launch { settings.setThemeMode("system") }
        }
        RowSetting("浅色模式", selected = themeMode == "light") {
            scope.launch { settings.setThemeMode("light") }
        }
        RowSetting("深色模式", selected = themeMode == "dark") {
            scope.launch { settings.setThemeMode("dark") }
        }

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

        SectionTitle("关于")
        Text(
            text = "XVerse 0.1.0\n仅面向 x.com 网页版。纯增强壳，不代理流量。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
                    CommandBus.selectTab(com.xverse.app.ui.navigation.XTab.HOME)
                    CommandBus.push(com.xverse.app.BrowserCommand.GoHome)
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

@Composable
private fun RowSetting(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        RadioButton(selected = selected, onClick = onClick)
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
