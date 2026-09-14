package com.imcys.bilibilias.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 隐私勾选行。
 *
 * 原实现里「《BILIBILIAS 隐私政策》」是硬编码文案，且点击会跳转到作者域名下的外部页面
 * （该站点随项目停维已失效）。本应用已不再接入任何统计/上报，故这里只保留勾选项本身，
 * 不再提供外部链接。
 */
@Composable
fun ASAgreePrivacyPolicy(agreePrivacyPolicy: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // 勾选同意隐私政策
        RadioButton(
            selected = agreePrivacyPolicy,
            onClick = {
                onClick()
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            },
            modifier = Modifier
                .padding(0.dp)
                .scale(0.75f)
                .size(20.dp)
        )
        Text("我已阅读并同意本应用的隐私说明", fontSize = 14.sp)
    }
}
