package com.xverse.app.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties

/**
 * 包装 Material 3 AlertDialog，确保在跨越 Window 边界渲染时，
 * 完整继承父级 Compose 树的 LocalContext 与 LocalConfiguration（多语言、主题、屏幕配置）。
 */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    val context = LocalContext.current
    val config = LocalConfiguration.current

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config) {
                confirmButton()
            }
        },
        modifier = modifier,
        dismissButton = dismissButton?.let { btn ->
            {
                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config) {
                    btn()
                }
            }
        },
        icon = icon?.let { ic ->
            {
                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config) {
                    ic()
                }
            }
        },
        title = title?.let { t ->
            {
                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config) {
                    t()
                }
            }
        },
        text = text?.let { tx ->
            {
                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config) {
                    tx()
                }
            }
        },
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}
