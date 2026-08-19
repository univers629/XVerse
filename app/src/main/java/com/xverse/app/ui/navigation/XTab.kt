package com.xverse.app.ui.navigation

import androidx.annotation.StringRes
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
import com.xverse.app.R

/**
 * 底栏五 Tab：首页 / 历史 / 下载 / 扩展 / 设置。
 * 日志不再占用底栏位置，收进设置页做二级页面（扩展页原为日志位）。
 */
enum class XTab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    HOME(R.string.tab_home, Icons.Outlined.Home, Icons.Filled.Home),
    HISTORY(R.string.tab_history, Icons.Outlined.History, Icons.Filled.History),
    DOWNLOAD(R.string.tab_download, Icons.Outlined.Download, Icons.Filled.Download),
    EXTENSIONS(R.string.tab_extensions, Icons.Outlined.Extension, Icons.Filled.Extension),
    SETTINGS(R.string.tab_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
}
