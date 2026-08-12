package com.xverse.app.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.xverse.app.CommandBus
import com.xverse.app.di.ServiceLocator
import com.xverse.app.ui.common.ExpressivePageTitle
import com.xverse.app.ui.common.SmoothDropdownContent
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
        // 必须继续使用 SettingsScreen 传入的 Scaffold padding；否则二级页会铺到
        // 底栏下方，最后几条日志无法完全滚动出来。
        LogsSubPage(modifier = modifier, onBack = { sub = null })
        return
    }

    val scope = rememberCoroutineScope()
    val locator = ServiceLocator.from(LocalContext.current)
    val settings = locator.settings

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ExpressivePageTitle(
            title = "设置",
            subtitle = "外观、浏览与数据偏好",
        )

        SectionTitle("账户")
        AccountSection()

        SectionTitle("外观")
        val hideXBottomBar by settings.hideXBottomBar.collectAsState(
            initial = com.xverse.app.core.data.repo.SettingsRepo.DEFAULT_HIDE_X_BOTTOM_BAR,
        )
        SwitchSetting("隐藏网页内 X 底栏", hideXBottomBar) { hidden ->
            scope.launch { settings.setHideXBottomBar(hidden) }
        }
        val themeMode by settings.themeMode.collectAsState(initial = "system")
        ThemeDropdown(
            selected = themeMode,
            onSelect = { v -> scope.launch { settings.setThemeMode(v) } },
        )
        val customMonetEnabled by settings.customMonetEnabled.collectAsState(
            initial = com.xverse.app.core.data.repo.SettingsRepo.DEFAULT_CUSTOM_MONET_ENABLED,
        )
        val customMonetColor by settings.customMonetColor.collectAsState(
            initial = com.xverse.app.core.data.repo.SettingsRepo.DEFAULT_MONET_COLOR_ARGB,
        )
        MonetColorSettings(
            enabled = customMonetEnabled,
            colorArgb = customMonetColor,
            onEnabledChange = { enabled ->
                scope.launch { settings.setCustomMonetEnabled(enabled) }
            },
            onColorChange = { color ->
                scope.launch { settings.setCustomMonetColor(color) }
            },
        )

        SectionTitle("过滤")
        val filterEnabled by settings.filterEnabled.collectAsState(initial = true)
        SwitchSetting("启用广告过滤", filterEnabled) {
            scope.launch {
                settings.setFilterEnabled(it)
                CommandBus.push(com.xverse.app.BrowserCommand.ReapplyInjections)
            }
        }
        val filterMode by settings.filterMode.collectAsState(
            initial = com.xverse.app.core.data.repo.SettingsRepo.DEFAULT_FILTER_MODE,
        )
        val filterCcVideos by settings.filterCcVideos.collectAsState(initial = true)
        val filterAiLabel by settings.filterAiLabel.collectAsState(
            initial = com.xverse.app.core.data.repo.SettingsRepo.DEFAULT_FILTER_AI_LABEL_ENABLED,
        )
        val filterExtensionDefaults by settings.filterExtensionDefaults.collectAsState(
            initial = com.xverse.app.core.data.repo.SettingsRepo.DEFAULT_FILTER_EXTENSION_RULES_ENABLED,
        )
        val disabledExtensionGroups by settings.filterExtensionDisabledGroups.collectAsState(initial = emptySet())
        val installedExtensions by locator.extensionRepo.observeAll().collectAsState(initial = emptyList())
        val extensionFilterPacks = remember(installedExtensions) {
            locator.extensionRuntime.filterPackSummaries()
        }
        if (filterEnabled) {
            Text(
                text = "统一控制网页广告与跟踪",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 6.dp),
            )
            // 过滤方式：mask=占位+可点击验证 / strip=完全不加载广告。
            FilterModeDropdown(
                selected = filterMode,
                onSelect = { v ->
                    scope.launch {
                        settings.setFilterMode(v)
                        CommandBus.push(com.xverse.app.BrowserCommand.ReapplyInjections)
                    }
                },
            )
            if (extensionFilterPacks.isNotEmpty()) {
                val count = extensionFilterPacks.sumOf { it.ruleCount }
                val packName = if (extensionFilterPacks.size == 1) {
                    extensionFilterPacks.first().name
                } else {
                    "扩展"
                }
                SwitchSetting("$packName 集成规则（$count 条）", filterExtensionDefaults, subRow = true) { on ->
                    scope.launch {
                        settings.setFilterExtensionDefaults(on)
                        CommandBus.push(com.xverse.app.BrowserCommand.ReapplyInjections)
                    }
                }
                if (filterExtensionDefaults) {
                    extensionFilterPacks.forEach { pack ->
                        pack.groups.forEach { group ->
                            val key = "${pack.extensionId}:${group.filterId}"
                            SwitchSetting(
                                label = "${group.name}（${group.ruleCount} 条）",
                                checked = key !in disabledExtensionGroups,
                                subRow = true,
                                nested = true,
                            ) { on ->
                                scope.launch {
                                    settings.setFilterExtensionGroupEnabled(pack.extensionId, group.filterId, on)
                                    CommandBus.push(com.xverse.app.BrowserCommand.ReapplyInjections)
                                }
                            }
                        }
                    }
                }
            }
            SwitchSetting("过滤带字幕（CC）视频", filterCcVideos, subRow = true) { on ->
                scope.launch {
                    settings.setFilterCcVideos(on)
                    CommandBus.push(com.xverse.app.BrowserCommand.SetCcFilter(on))
                }
            }
            SwitchSetting("过滤 AI 生成内容", filterAiLabel, subRow = true) { on ->
                scope.launch {
                    settings.setFilterAiLabel(on)
                    CommandBus.push(com.xverse.app.BrowserCommand.SetAiFilter(on))
                }
            }
            // 推广关键词与自定义规则和 CC/AI 同属总开关的同级子项。
            FilterRulesSection()
        }

        SectionTitle("下载")
        DownloadPathsSection()

        SectionTitle("开发")
        // 日志二级页入口
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            ) {
                Text("运行日志", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "分类筛选 / 导出",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsTextActionButton(
                label = "查看",
                onClick = { sub = "logs" },
            )
        }

        SectionTitle("关于")
        Text(
            text = "XVerse 0.4.0\n仅面向 x.com 网页版。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
        )
    }
}

/** 日志二级页：沿用父级 Scaffold 的状态栏、底栏安全区。 */
@Composable
private fun LogsSubPage(modifier: Modifier, onBack: () -> Unit) {
    Column(modifier = modifier.fillMaxSize()) {
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
                style = MaterialTheme.typography.titleMedium,
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

/** 账户分组：显示当前用户名，并保存多个加密 Cookie 会话供一键切换。 */
@Composable
private fun AccountSection() {
    val scope = rememberCoroutineScope()
    val locator = ServiceLocator.from(LocalContext.current)
    val isLoggedIn by locator.authController.loggedIn.collectAsState()
    val username by locator.authController.username.collectAsState()
    val accounts by locator.authController.accounts.collectAsState()
    var showLogoutConfirm by remember { mutableStateOf(false) }

    fun openLogin() {
        CommandBus.selectTab(com.xverse.app.ui.navigation.XTab.HOME)
        CommandBus.push(com.xverse.app.BrowserCommand.LoadUrl(com.xverse.app.core.util.Constants.LOGIN_URL))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        // 顶部总览行只管理当前 WebView 会话：账户列表在下方独立呈现。
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = if (isLoggedIn) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.AccountCircle,
                                contentDescription = null,
                                tint = if (isLoggedIn) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                },
                headlineContent = {
                    Text(
                        text = when {
                            isLoggedIn && username.isNotBlank() -> "@$username"
                            isLoggedIn -> "正在识别用户名…"
                            else -> "未登录"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        if (isLoggedIn) "当前登录账户" else "登录后可同步保存多个账户",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                trailingContent = {
                    SettingsTonalActionButton(
                        label = if (isLoggedIn) "退出登录" else "登录",
                        onClick = {
                            scope.launch {
                                if (isLoggedIn) showLogoutConfirm = true else openLogin()
                            }
                        },
                    )
                },
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            Text(
                text = "已保存账户",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )

            // 每个已保存账户使用标准两行 ListItem；当前账户以色调容器表示且没有切换按钮。
            accounts.forEach { account ->
                val current = isLoggedIn && account.username.equals(username, ignoreCase = true)
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = if (current) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                    } else {
                        Color.Transparent
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = CircleShape,
                                color = if (current) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = account.username.firstOrNull()?.uppercaseChar()?.toString() ?: "@",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (current) {
                                            MaterialTheme.colorScheme.onSecondary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        },
                        headlineContent = {
                            Text(
                                text = "@${account.username}",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = if (current) {
                            {
                                Text(
                                    text = "当前账户",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            null
                        },
                        trailingContent = if (current) null else {
                            {
                                SettingsTonalActionButton(
                                    label = "切换",
                                    onClick = {
                                        scope.launch {
                                            if (locator.authController.switchTo(account.username)) {
                                                CommandBus.selectTab(com.xverse.app.ui.navigation.XTab.HOME)
                                                CommandBus.push(com.xverse.app.BrowserCommand.LoadUrl(com.xverse.app.core.util.Constants.HOME_URL))
                                            }
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            }

        if (isLoggedIn) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        if (locator.authController.prepareAddAccount()) openLogin()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp),
                shape = MaterialTheme.shapes.extraLarge,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    "登录其他账户",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            shape = RoundedCornerShape(16.dp),
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
    Row(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 12.dp).weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
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
    val value = options.firstOrNull { it.first == selected }?.second ?: "跟随系统"
    SettingDropdown(label = "颜色模式", value = value, options = options, onSelect = onSelect)
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
    val value = options.firstOrNull { it.first == selected }?.second ?: "占位 + 可点击验证"
    SettingDropdown(
        label = "过滤方式",
        value = value,
        options = options,
        onSelect = onSelect,
        subRow = true,
    )
}

/** MD3 标准暴露式下拉选择器：只读文本框作为锚点，菜单与其等宽。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingDropdown(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    subRow: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = if (subRow) 6.dp else 10.dp),
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(label) },
                textStyle = MaterialTheme.typography.titleSmall,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                shape = RoundedCornerShape(18.dp),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                SmoothDropdownContent(expanded = expanded) {
                    options.forEach { (optionValue, optionLabel) ->
                        DropdownMenuItem(
                            text = { Text(optionLabel, style = MaterialTheme.typography.titleSmall) },
                            onClick = {
                                expanded = false
                                onSelect(optionValue)
                            },
                        )
                    }
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
    nested: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.padding(
            start = if (nested) 44.dp else 32.dp,
            end = 32.dp,
            top = if (subRow) 5.dp else 10.dp,
            bottom = if (subRow) 5.dp else 10.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = if (subRow) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private enum class DownloadPathType { IMAGE, GIF, VIDEO }

/** 三类媒体各自选择 SAF 目录；权限持久化后后台下载也能继续写入。 */
@Composable
private fun DownloadPathsSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = ServiceLocator.from(context).settings
    val imageDir by settings.downloadImageDir.collectAsState(initial = "")
    val gifDir by settings.downloadGifDir.collectAsState(initial = "")
    val videoDir by settings.downloadVideoDir.collectAsState(initial = "")
    var picking by remember { mutableStateOf<DownloadPathType?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val type = picking ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            scope.launch {
                when (type) {
                    DownloadPathType.IMAGE -> settings.setDownloadImageDir(uri.toString())
                    DownloadPathType.GIF -> settings.setDownloadGifDir(uri.toString())
                    DownloadPathType.VIDEO -> settings.setDownloadVideoDir(uri.toString())
                }
            }
        }
        picking = null
    }

    DownloadPathRow("图片", imageDir, "Pictures/XVerse") {
        picking = DownloadPathType.IMAGE
        picker.launch(null)
    }
    DownloadPathRow("GIF", gifDir, "Pictures/XVerse") {
        picking = DownloadPathType.GIF
        picker.launch(null)
    }
    DownloadPathRow("视频", videoDir, "Movies/XVerse") {
        picking = DownloadPathType.VIDEO
        picker.launch(null)
    }
}

@Composable
private fun DownloadPathRow(label: String, uri: String, defaultPath: String, onPick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val hint = if (uri.isBlank()) {
        "默认：$defaultPath"
    } else {
        val name = runCatching { DocumentFile.fromTreeUri(context, uri.toUri())?.name }.getOrNull()
        "已选：${name ?: uri}"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SettingsActionButton(label = "选择路径", onClick = onPick)
    }
}

/** 设置主列表中的操作按钮统一使用更紧凑的字号和高度。 */
@Composable
private fun SettingsActionButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SettingsTonalActionButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SettingsTextActionButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** 过滤规则列表：推广关键词开关（上方）→ 自定义内容/网络/元素规则（下方）。 */
@Composable
private fun FilterRulesSection() {
    val scope = rememberCoroutineScope()
    val locator = ServiceLocator.from(LocalContext.current)
    val rules by locator.filterRepo.observeAll()
        .collectAsState(initial = emptyList())

    val builtinRules = rules.filter { it.builtin }
    val userRules = rules.filter { !it.builtin }

    // 内置推广关键词开关与 CC/AI 使用相同子行层级。
    builtinRules.forEach { rule ->
        FilterRuleRow(rule, subRow = true)
    }

    // MD3 InputChip 专门表示用户输入的离散信息；输入框负责添加，标签负责启停/删除。
    var keyword by remember { mutableStateOf("") }
    OutlinedTextField(
        value = keyword,
        onValueChange = { keyword = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 8.dp),
        label = { Text("自定义过滤规则") },
        placeholder = { Text("关键词、||域名^ 或 x.com##选择器") },
        supportingText = { Text("兼容 AdGuard/ABP 基础网络规则与元素隐藏规则") },
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        trailingIcon = {
            IconButton(
                onClick = {
                    val kw = keyword.trim()
                    if (kw.isNotEmpty()) {
                        scope.launch {
                            com.xverse.app.core.webview.ContentFilterRuleParser.toRule(kw)?.let { rule ->
                                locator.filterRepo.insert(rule)
                                keyword = ""
                            }
                        }
                    }
                },
                enabled = keyword.isNotBlank(),
            ) { Icon(Icons.Filled.Add, contentDescription = "添加屏蔽词") }
        },
    )

    if (userRules.isNotEmpty()) {
        Text(
            text = "已添加的规则 · 点按启用或停用",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 2.dp, bottom = 6.dp),
        )
        UserRuleChips(userRules)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserRuleChips(rules: List<com.xverse.app.core.data.db.FilterRule>) {
    val scope = rememberCoroutineScope()
    val locator = ServiceLocator.from(LocalContext.current)
    FlowRow(
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        rules.forEach { rule ->
            InputChip(
                selected = rule.enabled,
                onClick = { scope.launch { locator.filterRepo.setEnabled(rule.id, !rule.enabled) } },
                label = {
                    Text(
                        rule.description.removePrefix("屏蔽词：").ifBlank { rule.pattern },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                // 状态切换时保留相同 18dp 槽位，防止勾选出现/消失导致芯片宽度跳变。
                leadingIcon = {
                    Box(
                        modifier = Modifier.size(18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (rule.enabled) {
                            Icon(
                                Icons.Filled.Done,
                                contentDescription = "已启用",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
                trailingIcon = {
                    // 不使用默认 48dp IconButton，避免它撑高 InputChip、破坏三者的垂直居中。
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .clickable { scope.launch { locator.filterRepo.delete(rule.id) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "删除屏蔽词",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
                shape = MaterialTheme.shapes.large,
            )
        }
    }
}

/** 单条规则行：描述 + 开关 + 删除（仅非内置） */
@Composable
private fun FilterRuleRow(
    rule: com.xverse.app.core.data.db.FilterRule,
    subRow: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val locator = ServiceLocator.from(LocalContext.current)
    Row(
        modifier = Modifier.padding(
            start = 32.dp,
            end = 32.dp,
            top = if (subRow) 5.dp else 10.dp,
            bottom = if (subRow) 5.dp else 10.dp,
        ),
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
                        locator.filterRepo.setEnabled(rule.id, enabled)
                    }
                },
            )
            if (!rule.builtin) {
                IconButton(onClick = {
                    scope.launch { locator.filterRepo.delete(rule.id) }
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
