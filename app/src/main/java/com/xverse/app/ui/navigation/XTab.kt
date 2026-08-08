package com.xverse.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 底栏五 Tab：首页 / 历史 / 下载 / 扩展 / 设置。
 * 日志不再占用底栏位置，收进设置页做二级页面（扩展页原为日志位）。
 */
enum class XTab(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    HOME("首页", Icons.Outlined.Home, Icons.Filled.Home),
    HISTORY("历史", Icons.Outlined.History, Icons.Filled.History),
    DOWNLOAD("下载", Icons.Outlined.Download, Icons.Filled.Download),
    EXTENSIONS("扩展", Icons.Outlined.Extension, Icons.Filled.Extension),
    SETTINGS("设置", Icons.Outlined.Settings, Icons.Filled.Settings),
}
