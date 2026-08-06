package com.xverse.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 底栏五 Tab：首页 / 历史 / 下载 / 日志 / 设置。
 * 日志作为第五 Tab 常驻（策划案 5.6 底栏：首页/历史/下载/设置 + 日志）。
 */
enum class XTab(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    HOME("首页", Icons.Outlined.Home, Icons.Filled.Home),
    HISTORY("历史", Icons.Outlined.History, Icons.Filled.History),
    DOWNLOAD("下载", Icons.Outlined.Download, Icons.Filled.Download),
    LOGS("日志", Icons.Outlined.List, Icons.Filled.List),
    SETTINGS("设置", Icons.Outlined.Settings, Icons.Filled.Settings),
}
