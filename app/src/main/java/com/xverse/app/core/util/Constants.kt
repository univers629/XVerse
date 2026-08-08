package com.xverse.app.core.util

/**
 * 全局常量。
 */
object Constants {
    /** 首页地址 */
    const val HOME_URL = "https://x.com"

    /** x.com 登录页（WebView 内打开，Cookie 写入 WebView 存储） */
    const val LOGIN_URL = "https://x.com/i/flow/login"

    /** 历史记录分组 */
    const val HISTORY_MAX_KEEP_DAYS = 90
    const val HISTORY_MAX_RECORDS = 5000

    /** 通知渠道 */
    const val CHANNEL_DOWNLOAD = "xverse_download"
}
