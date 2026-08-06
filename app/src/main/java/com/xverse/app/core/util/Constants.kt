package com.xverse.app.core.util

/**
 * 全局常量。
 */
object Constants {
    /** 首页地址 */
    const val HOME_URL = "https://x.com"

    /** x.com 登录页（WebView 内打开，Cookie 写入 WebView 存储） */
    const val LOGIN_URL = "https://x.com/i/flow/login"

    /** 桌面 UA 会被 x.com 拦截为非常规浏览器，伪装标准 Chrome 移动版 */
    const val CHROME_MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.6613.99 Mobile Safari/537.36"

    /** 历史记录分组 */
    const val HISTORY_MAX_KEEP_DAYS = 90
    const val HISTORY_MAX_RECORDS = 5000

    /** JS 回调协议：window.__gmCallbacks 全局注册表 */
    const val BRIDGE_OBJECT = "XVerseNative"

    /** 通知渠道 */
    const val CHANNEL_DOWNLOAD = "xverse_download"
    const val CHANNEL_GENERAL = "xverse_general"

    /** 日志 */
    const val TAG = "XVerse"
}
