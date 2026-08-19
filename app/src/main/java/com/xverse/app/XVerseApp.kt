package com.xverse.app

import android.app.Application
import com.xverse.app.di.ServiceLocator

/**
 * 应用入口：持有全局单例 ServiceLocator。
 * 手动 DI，不引入 Hilt。
 */
class XVerseApp : Application() {
    val locator: ServiceLocator by lazy { ServiceLocator(this) }

    override fun attachBaseContext(base: android.content.Context) {
        val lang = com.xverse.app.core.data.repo.SettingsRepo.getSavedAppLanguage(base)
        super.attachBaseContext(com.xverse.app.core.util.LocaleUtils.applyLocale(base, lang))
    }

    fun updateAppLocale(lang: String) {
        com.xverse.app.core.util.LocaleUtils.applyLocale(this, lang)
    }
}
