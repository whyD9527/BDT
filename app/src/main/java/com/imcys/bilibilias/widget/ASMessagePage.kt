package com.imcys.bilibilias.widget

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.imcys.bilibilias.R

/** 提示页上的一个动作（[secondary] = 描边按钮，否则实心按钮） */
data class ASMessageAction(
    val text: String,
    val onClick: () -> Unit,
    val secondary: Boolean = false,
)

/**
 * 通用「提示页」组件（#6）。
 *
 * 原来 `PlayVoucherErrorPage`（凭证失效：一串风险清单 + 我知道了）与
 * `RequestFrequentScreen` 的默认态（服务器繁忙：图标 + 两段说明 + 重试/退出）
 * 各写了一遍"整屏居中 + 文案 + 按钮"的排版，只有文案、图标和按钮数量不同。
 * 现在两份实现收敛到这里：**文案 / 图标 / 按钮全部参数化**，页面本身不再有排版代码。
 *
 * @param messages 正文段落；[bulleted] 为 true 时按「• xx」左对齐排版（风险清单场景）
 * @param footer 末尾的次要说明（灰色小字）
 * @param actions 按钮列表（第 1 个离正文更远，其余依次排在下面）
 */
@Composable
fun ASMessagePage(
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
    @StringRes iconContentDescriptionRes: Int = R.string.cd_server_error_icon,
    titleStyle: TextStyle = MaterialTheme.typography.headlineMedium,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    titleWeight: FontWeight = FontWeight.Normal,
    messages: List<String> = emptyList(),
    bulleted: Boolean = false,
    footer: String? = null,
    actions: List<ASMessageAction> = emptyList(),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(40.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = if (bulleted) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = stringResource(iconContentDescriptionRes),
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Text(
            text = title,
            style = titleStyle,
            color = titleColor,
            fontWeight = titleWeight,
            modifier = Modifier.padding(top = 20.dp),
        )

        messages.forEach { message ->
            Text(
                text = if (bulleted) "• $message" else message,
                style = if (bulleted) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                textAlign = if (bulleted) TextAlign.Start else TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp),
            )
        }

        footer?.let { footerText ->
            Text(
                text = footerText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 20.dp),
            )
        }

        actions.forEachIndexed { index, action ->
            val buttonModifier = Modifier
                .padding(top = if (index == 0) 40.dp else 16.dp)
                .fillMaxWidth()
            if (action.secondary) {
                OutlinedButton(
                    onClick = action.onClick,
                    modifier = buttonModifier,
                    shape = CardDefaults.shape,
                ) {
                    Text(text = action.text)
                }
            } else {
                Button(
                    onClick = action.onClick,
                    modifier = buttonModifier,
                    shape = CardDefaults.shape,
                ) {
                    Text(text = action.text)
                }
            }
        }
    }
}
