package com.xverse.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.graphics.ColorUtils

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
 * Lightweight seed scheme built from HSL tonal roles. It keeps the custom theme self-contained
 * instead of pulling the full View-based Material Components package into this Compose app.
 */
private fun customMonetColorScheme(seedColor: Color, darkTheme: Boolean) = run {
    val seedHsl = FloatArray(3).also { ColorUtils.colorToHSL(seedColor.toArgb(), it) }
    val hue = seedHsl[0]
    val chroma = seedHsl[1].coerceIn(0.42f, 0.88f)
    val secondaryChroma = (chroma * 0.42f).coerceIn(0.20f, 0.38f)
    val tertiaryHue = (hue + 58f) % 360f
    val tertiaryChroma = (chroma * 0.72f).coerceIn(0.34f, 0.68f)
    val neutralChroma = (chroma * 0.10f).coerceIn(0.035f, 0.09f)

    fun tone(targetHue: Float, saturation: Float, lightness: Float): Color = Color(
        ColorUtils.HSLToColor(floatArrayOf(targetHue, saturation.coerceIn(0f, 1f), lightness)),
    )

    val primary = if (darkTheme) tone(hue, chroma, 0.78f) else tone(hue, chroma, 0.40f)
    val onPrimary = if (darkTheme) tone(hue, chroma, 0.18f) else Color.White
    val primaryContainer = if (darkTheme) tone(hue, chroma * 0.85f, 0.29f) else tone(hue, chroma * 0.75f, 0.90f)
    val onPrimaryContainer = if (darkTheme) tone(hue, chroma * 0.72f, 0.90f) else tone(hue, chroma, 0.12f)
    val secondary = if (darkTheme) tone(hue, secondaryChroma, 0.78f) else tone(hue, secondaryChroma, 0.40f)
    val onSecondary = if (darkTheme) tone(hue, secondaryChroma, 0.18f) else Color.White
    val secondaryContainer = if (darkTheme) tone(hue, secondaryChroma, 0.29f) else tone(hue, secondaryChroma, 0.90f)
    val onSecondaryContainer = if (darkTheme) tone(hue, secondaryChroma, 0.90f) else tone(hue, secondaryChroma, 0.12f)
    val tertiary = if (darkTheme) tone(tertiaryHue, tertiaryChroma, 0.78f) else tone(tertiaryHue, tertiaryChroma, 0.40f)
    val onTertiary = if (darkTheme) tone(tertiaryHue, tertiaryChroma, 0.18f) else Color.White
    val tertiaryContainer = if (darkTheme) tone(tertiaryHue, tertiaryChroma, 0.29f) else tone(tertiaryHue, tertiaryChroma, 0.90f)
    val onTertiaryContainer = if (darkTheme) tone(tertiaryHue, tertiaryChroma, 0.90f) else tone(tertiaryHue, tertiaryChroma, 0.12f)

    val background = tone(hue, neutralChroma, if (darkTheme) 0.065f else 0.985f)
    val surface = tone(hue, neutralChroma, if (darkTheme) 0.075f else 0.985f)
    val onSurface = tone(hue, neutralChroma, if (darkTheme) 0.90f else 0.12f)
    val surfaceVariant = tone(hue, neutralChroma * 1.6f, if (darkTheme) 0.28f else 0.90f)
    val onSurfaceVariant = tone(hue, neutralChroma * 1.5f, if (darkTheme) 0.78f else 0.30f)
    val outline = tone(hue, neutralChroma, if (darkTheme) 0.60f else 0.50f)
    val outlineVariant = tone(hue, neutralChroma, if (darkTheme) 0.30f else 0.80f)

    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = if (darkTheme) tone(hue, chroma, 0.40f) else tone(hue, chroma, 0.78f),
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = primary,
        inverseSurface = tone(hue, neutralChroma, if (darkTheme) 0.90f else 0.20f),
        inverseOnSurface = tone(hue, neutralChroma, if (darkTheme) 0.20f else 0.95f),
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = Color.Black,
        surfaceBright = tone(hue, neutralChroma, if (darkTheme) 0.24f else 0.985f),
        surfaceDim = tone(hue, neutralChroma, if (darkTheme) 0.065f else 0.87f),
        surfaceContainerLowest = tone(hue, neutralChroma, if (darkTheme) 0.04f else 1f),
        surfaceContainerLow = tone(hue, neutralChroma, if (darkTheme) 0.10f else 0.965f),
        surfaceContainer = tone(hue, neutralChroma, if (darkTheme) 0.12f else 0.945f),
        surfaceContainerHigh = tone(hue, neutralChroma, if (darkTheme) 0.15f else 0.925f),
        surfaceContainerHighest = tone(hue, neutralChroma, if (darkTheme) 0.18f else 0.90f),
    )
}

/*
 * Material 3 Expressive 的视觉基调：比传统 MD3 更鲜明的字号层级、色调表面和
 * 更圆润的容器。这里保持所有组件仍使用稳定的 Material 3 API，避免为了视觉样式
 * 引入 alpha 组件后影响现有下载/WebView 工作流的稳定性。
 */
private val BaseTypography = Typography()

private val ExpressiveTypography = Typography(
    displaySmall = BaseTypography.displaySmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    headlineLarge = BaseTypography.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.Bold),
    titleLarge = BaseTypography.titleLarge.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.25).sp,
    ),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(10),
    small = RoundedCornerShape(16),
    medium = RoundedCornerShape(22),
    large = RoundedCornerShape(30),
    extraLarge = RoundedCornerShape(38),
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
    val colorScheme = remember(context, darkTheme, dynamicColor, seedColor) {
        when {
            dynamicColor -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            !dynamicColor -> customMonetColorScheme(seedColor, darkTheme)
            darkTheme -> DarkColors
            else -> LightColors
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ExpressiveTypography,
        shapes = ExpressiveShapes,
        content = content,
    )
}
