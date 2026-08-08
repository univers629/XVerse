package com.xverse.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// X 品牌色
val XBlue = Color(0xFF1D9BF0)
val XDark = Color(0xFF101418)

private val LightColors = lightColorScheme(
    primary = XBlue,
    onPrimary = Color.White,
    secondary = Color(0xFF536DFE),
    tertiary = Color(0xFFFF6B35),
    background = Color(0xFFF7F9F9),
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = XBlue,
    onPrimary = Color.White,
    secondary = Color(0xFF536DFE),
    tertiary = Color(0xFFFF8A5C),
    background = XDark,
    surface = Color(0xFF151A1F),
)

/**
 * XVerse 主题：Material You 动态取色（Android 12+ 默认开启），
 * 可降级到固定种子色。
 */
@Composable
fun XVerseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    seedColor: Color = XBlue,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
