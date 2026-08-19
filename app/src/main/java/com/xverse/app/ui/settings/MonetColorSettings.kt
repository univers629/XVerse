package com.xverse.app.ui.settings

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils

/** Two-row custom Monet control with an MD3 Expressive color workflow. */
@Composable
internal fun MonetColorSettings(
    enabled: Boolean,
    colorArgb: Int,
    onEnabledChange: (Boolean) -> Unit,
    onColorChange: (Int) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.monet_custom_theme_color), style = MaterialTheme.typography.titleSmall)
                Text(
                    androidx.compose.ui.res.stringResource(com.xverse.app.R.string.monet_custom_theme_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { showPicker = true }
                .alpha(if (enabled) 1f else 0.42f)
                .padding(horizontal = 32.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.monet_select_theme_color), style = MaterialTheme.typography.titleSmall)
                Text(
                    colorArgb.toHexColor(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ColorSwatch(Color(colorArgb))
        }
    }

    if (showPicker) {
        MonetColorPickerDialog(
            initialColor = colorArgb,
            onDismiss = { showPicker = false },
            onConfirm = {
                onColorChange(it)
                showPicker = false
            },
        )
    }
}

@Composable
private fun ColorSwatch(color: Color) {
    Surface(
        modifier = Modifier.size(46.dp),
        shape = CircleShape,
        color = color,
        contentColor = if (color.luminance() > 0.48f) Color.Black else Color.White,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.ColorLens, contentDescription = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.monet_current_theme_color), modifier = Modifier.size(22.dp))
        }
    }
}

private enum class PickerMode { PALETTE, MIXER }

@Composable
private fun MonetColorPickerDialog(
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initialHsl = remember(initialColor) {
        FloatArray(3).also { ColorUtils.colorToHSL(initialColor, it) }
    }
    var hue by remember(initialColor) { mutableFloatStateOf(initialHsl[0]) }
    var saturation by remember(initialColor) { mutableFloatStateOf(initialHsl[1]) }
    var lightness by remember(initialColor) { mutableFloatStateOf(initialHsl[2]) }
    var mode by remember { mutableStateOf(PickerMode.PALETTE) }
    var hexValue by remember(initialColor) { mutableStateOf(initialColor.toHexDigits()) }
    val selectedColor = remember(hue, saturation, lightness) {
        ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness))
    }

    fun selectColor(color: Int) {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hue = hsl[0]
        saturation = hsl[1]
        lightness = hsl[2]
        hexValue = color.toHexDigits()
    }

    fun updateHsl(newHue: Float, newSaturation: Float, newLightness: Float) {
        hue = newHue
        saturation = newSaturation
        lightness = newLightness
        hexValue = ColorUtils.HSLToColor(
            floatArrayOf(newHue, newSaturation, newLightness),
        ).toHexDigits()
    }

    com.xverse.app.ui.common.AppAlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = { Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.monet_select_theme_color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SelectedColorHeader(
                    selectedColor = selectedColor,
                    hexValue = hexValue,
                    onHexValueChange = { input ->
                        val sanitized = input
                            .removePrefix("#")
                            .filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }
                            .take(6)
                            .uppercase()
                        hexValue = sanitized
                        if (sanitized.length == 6) {
                            sanitized.toLongOrNull(16)?.let { rgb ->
                                selectColor((0xFF000000L or rgb).toInt())
                            }
                        }
                    },
                )
                PickerModeTabs(mode = mode, onModeChange = { mode = it })
                when (mode) {
                    PickerMode.PALETTE -> ContinuousColorPalette(
                        selectedColor = selectedColor,
                        onColorSelected = ::selectColor,
                    )

                    PickerMode.MIXER -> HslSliders(
                        hue = hue,
                        saturation = saturation,
                        lightness = lightness,
                        onHueChange = { updateHsl(it, saturation, lightness) },
                        onSaturationChange = { updateHsl(hue, it, lightness) },
                        onLightnessChange = { updateHsl(hue, saturation, it) },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedColor) },
                enabled = hexValue.length == 6,
            ) { Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.action_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.xverse.app.R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SelectedColorHeader(
    selectedColor: Int,
    hexValue: String,
    onHexValueChange: (String) -> Unit,
) {
    val color = Color(selectedColor)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(12.dp),
            color = color,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {}
        OutlinedTextField(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            value = hexValue,
            onValueChange = onHexValueChange,
            prefix = { Text("#") },
            label = { Text("HEX") },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun PickerModeTabs(mode: PickerMode, onModeChange: (PickerMode) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            PickerModeTab(
                label = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.monet_tab_palette),
                selected = mode == PickerMode.PALETTE,
                onClick = { onModeChange(PickerMode.PALETTE) },
            )
            PickerModeTab(
                label = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.monet_tab_mixer),
                selected = mode == PickerMode.MIXER,
                onClick = { onModeChange(PickerMode.MIXER) },
            )
        }
    }
}

@Composable
private fun RowScope.PickerModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.weight(1f).clickable(onClick = onClick),
        shape = RoundedCornerShape(11.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ContinuousColorPalette(selectedColor: Int, onColorSelected: (Int) -> Unit) {
    val hsv = remember(selectedColor) {
        FloatArray(3).also { AndroidColor.colorToHSV(selectedColor, it) }
    }
    val hueColor = remember(hsv[0]) {
        Color(AndroidColor.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.72f)
                    .clip(RoundedCornerShape(10.dp))
                    .pointerInput(hsv[0], onColorSelected) {
                        fun update(position: Offset) {
                            val selectedSaturation = (position.x / size.width).coerceIn(0f, 1f)
                            val selectedBrightness = (1f - position.y / size.height).coerceIn(0f, 1f)
                            onColorSelected(
                                AndroidColor.HSVToColor(
                                    floatArrayOf(hsv[0], selectedSaturation, selectedBrightness),
                                ),
                            )
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            update(down.position)
                            down.consume()
                            var pressed = true
                            while (pressed) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { change ->
                                    if (change.pressed) {
                                        update(change.position)
                                        change.consume()
                                    }
                                }
                                pressed = event.changes.any { it.pressed }
                            }
                        }
                    },
            ) {
                drawRect(brush = Brush.horizontalGradient(listOf(Color.White, hueColor)))
                drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val marker = Offset(hsv[1] * size.width, (1f - hsv[2]) * size.height)
                drawCircle(Color.Black.copy(alpha = 0.44f), radius = 11.dp.toPx(), center = marker)
                drawCircle(Color.White, radius = 9.dp.toPx(), center = marker)
                drawCircle(Color(selectedColor), radius = 6.dp.toPx(), center = marker)
            }
        }
        GradientSliderRow(
            label = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.monet_slider_hue),
            valueLabel = "${hsv[0].toInt()}°",
            value = hsv[0] / 360f,
            colors = HUE_COLORS,
            thumbColor = hueColor,
            onValueChange = { next ->
                onColorSelected(
                    AndroidColor.HSVToColor(floatArrayOf(next * 360f, hsv[1], hsv[2])),
                )
            },
        )
    }
}

@Composable
private fun HslSliders(
    hue: Float,
    saturation: Float,
    lightness: Float,
    onHueChange: (Float) -> Unit,
    onSaturationChange: (Float) -> Unit,
    onLightnessChange: (Float) -> Unit,
) {
    val saturatedColor = Color(ColorUtils.HSLToColor(floatArrayOf(hue, 1f, 0.5f)))
    val saturationStart = Color(ColorUtils.HSLToColor(floatArrayOf(hue, 0f, lightness)))
    val saturationEnd = Color(ColorUtils.HSLToColor(floatArrayOf(hue, 1f, lightness)))
    val lightnessMiddle = Color(ColorUtils.HSLToColor(floatArrayOf(hue, saturation, 0.5f)))

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GradientSliderRow(
            label = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.monet_slider_hue),
            valueLabel = "${hue.toInt()}°",
            value = hue / 360f,
            colors = HUE_COLORS,
            thumbColor = saturatedColor,
            onValueChange = { onHueChange(it * 360f) },
        )
        GradientSliderRow(
            label = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.monet_slider_saturation),
            valueLabel = "${(saturation * 100).toInt()}%",
            value = saturation,
            colors = listOf(saturationStart, saturationEnd),
            thumbColor = Color(ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness))),
            onValueChange = onSaturationChange,
        )
        GradientSliderRow(
            label = androidx.compose.ui.res.stringResource(com.xverse.app.R.string.monet_slider_lightness),
            valueLabel = "${(lightness * 100).toInt()}%",
            value = lightness,
            colors = listOf(Color.Black, lightnessMiddle, Color.White),
            thumbColor = Color(ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness))),
            onValueChange = onLightnessChange,
        )
    }
}

@Composable
private fun GradientSliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    colors: List<Color>,
    thumbColor: Color,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GradientSlider(
            value = value,
            colors = colors,
            thumbColor = thumbColor,
            onValueChange = onValueChange,
        )
    }
}

@Composable
private fun GradientSlider(
    value: Float,
    colors: List<Color>,
    thumbColor: Color,
    onValueChange: (Float) -> Unit,
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val outlineColor = MaterialTheme.colorScheme.outline
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .pointerInput(onValueChange) {
                fun update(x: Float) {
                    val inset = 14.dp.toPx()
                    val trackWidth = size.width - inset * 2f
                    onValueChange(((x - inset) / trackWidth).coerceIn(0f, 1f))
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    update(down.position.x)
                    down.consume()
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            if (change.pressed) {
                                update(change.position.x)
                                change.consume()
                            }
                        }
                        pressed = event.changes.any { it.pressed }
                    }
                }
            },
    ) {
        val inset = 14.dp.toPx()
        val trackHeight = 12.dp.toPx()
        val trackTop = (size.height - trackHeight) / 2f
        val trackWidth = size.width - inset * 2f
        drawRoundRect(
            brush = Brush.horizontalGradient(colors, startX = inset, endX = size.width - inset),
            topLeft = Offset(inset, trackTop),
            size = Size(trackWidth, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f),
        )
        val thumbCenter = Offset(inset + value.coerceIn(0f, 1f) * trackWidth, size.height / 2f)
        drawCircle(outlineColor.copy(alpha = 0.42f), radius = 11.dp.toPx(), center = thumbCenter)
        drawCircle(surfaceColor, radius = 9.5.dp.toPx(), center = thumbCenter)
        drawCircle(thumbColor, radius = 7.dp.toPx(), center = thumbCenter)
    }
}

private val HUE_COLORS = listOf(
    Color.Red,
    Color.Yellow,
    Color.Green,
    Color.Cyan,
    Color.Blue,
    Color.Magenta,
    Color.Red,
)

private fun Int.toHexColor(): String = "#%06X".format(this and 0xFFFFFF)

private fun Int.toHexDigits(): String = "%06X".format(this and 0xFFFFFF)
