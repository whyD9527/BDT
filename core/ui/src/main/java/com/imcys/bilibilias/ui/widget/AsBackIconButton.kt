package com.imcys.bilibilias.ui.widget

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import com.imcys.bilibilias.ui.R

@Composable
fun AsBackIconButton(onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current

    ASIconButton(onClick = {
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        onClick.invoke()
    }) {
        Icon(
            Icons.AutoMirrored.Outlined.ArrowBack,
            // F（2026-10-02 真机复验）：原先硬编码 "返回"，英文界面下左上角就漏中文
            contentDescription = stringResource(R.string.core_back)
        )
    }
}