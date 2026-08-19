package com.xverse.app.core.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object LocaleUtils {

    fun getSystemLocale(): Locale {
        return if (Build.VERSION.SDK_INT >= 24) {
            Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()
        } else {
            @Suppress("DEPRECATION")
            Resources.getSystem().configuration.locale ?: Locale.getDefault()
        }
    }

    fun getLocale(lang: String): Locale {
        return when (lang) {
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "ja" -> Locale.JAPANESE
            "en" -> Locale.ENGLISH
            else -> getSystemLocale()
        }
    }

    fun applyLocale(context: Context, lang: String): Context {
        val targetLocale = getLocale(lang)
        Locale.setDefault(targetLocale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(targetLocale)
        if (Build.VERSION.SDK_INT >= 24) {
            config.setLocales(LocaleList(targetLocale))
        }
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        try {
            val appCtx = context.applicationContext
            if (appCtx != null && appCtx != context) {
                val appConfig = Configuration(appCtx.resources.configuration)
                appConfig.setLocale(targetLocale)
                if (Build.VERSION.SDK_INT >= 24) {
                    appConfig.setLocales(LocaleList(targetLocale))
                }
                @Suppress("DEPRECATION")
                appCtx.resources.updateConfiguration(appConfig, appCtx.resources.displayMetrics)
            }
        } catch (_: Exception) {}
        return context.createConfigurationContext(config)
    }

    fun getString(context: Context, resId: Int, vararg formatArgs: Any): String {
        val lang = com.xverse.app.core.data.repo.SettingsRepo.getSavedAppLanguage(context)
        val targetLocale = getLocale(lang)
        val config = Configuration(context.resources.configuration).apply {
            setLocale(targetLocale)
            if (Build.VERSION.SDK_INT >= 24) {
                setLocales(LocaleList(targetLocale))
            }
        }
        val localizedCtx = context.createConfigurationContext(config)
        return if (formatArgs.isNotEmpty()) {
            localizedCtx.resources.getString(resId, *formatArgs)
        } else {
            localizedCtx.resources.getString(resId)
        }
    }

    fun restartApp(activity: android.app.Activity?) {
        if (activity == null) return
        val pm = activity.packageManager
        val intent = pm.getLaunchIntentForPackage(activity.packageName)?.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        if (intent != null) {
            activity.startActivity(intent)
            activity.finishAffinity()
            kotlin.system.exitProcess(0)
        } else {
            activity.recreate()
        }
    }
}
