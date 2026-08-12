package com.xverse.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import kotlin.math.roundToInt

/** 为所有 Material 下拉菜单补充统一、轻量的展开与收回动效。 */
@Composable
fun SmoothDropdownContent(
    expanded: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = 160,
                delayMillis = 20,
                easing = LinearOutSlowInEasing,
            ),
        ) + expandVertically(
            animationSpec = tween(
                durationMillis = 220,
                easing = FastOutSlowInEasing,
            ),
            expandFrom = Alignment.Top,
            initialHeight = { fullHeight -> (fullHeight * 0.86f).roundToInt() },
        ),
        exit = fadeOut(
            animationSpec = tween(durationMillis = 110),
        ) + shrinkVertically(
            animationSpec = tween(
                durationMillis = 160,
                easing = FastOutSlowInEasing,
            ),
            shrinkTowards = Alignment.Top,
            targetHeight = { fullHeight -> (fullHeight * 0.9f).roundToInt() },
        ),
    ) {
        Column(content = content)
    }
}
