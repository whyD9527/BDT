package com.imcys.bilibilias.ui.event.playvoucher

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.imcys.bilibilias.R
import com.imcys.bilibilias.widget.ASMessageAction
import com.imcys.bilibilias.widget.ASMessagePage
import org.koin.compose.viewmodel.koinViewModel

/**
 * 「大会员凭证失效」提示页。
 *
 * #6（2026-10-05）：排版收敛到通用组件 [ASMessagePage]（文案/按钮全部参数化），
 * 本页只剩"用哪些文案、按钮点了做什么"。
 *
 * ⚠️ 标题下的风险清单原来是 `LazyColumn` 里的 `items(points.size)`；在通用组件里
 * 变成普通 Column —— 所以文案**必须在 Composable 上下文里先取好**：
 * `listOf(资源 id).map { stringResource(it) }` 里的 lambda 不是 @Composable，会编译失败。
 */
@Composable
fun PlayVoucherErrorPage(onBlack: () -> Unit = {}) {
    val vm = koinViewModel<PlayVoucherErrorViewModel>()
    ASMessagePage(
        title = stringResource(R.string.voucher_title),
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
        titleStyle = MaterialTheme.typography.titleMedium,
        titleColor = MaterialTheme.colorScheme.primary,
        titleWeight = FontWeight.Bold,
        messages = listOf(
            stringResource(R.string.voucher_risk_1),
            stringResource(R.string.voucher_risk_2),
            stringResource(R.string.voucher_risk_3),
            stringResource(R.string.voucher_risk_4),
            stringResource(R.string.voucher_risk_5),
            stringResource(R.string.voucher_risk_6),
        ),
        bulleted = true,
        footer = stringResource(R.string.voucher_footer),
        actions = listOf(
            ASMessageAction(
                text = stringResource(R.string.common_i_know),
                onClick = {
                    vm.ontUseTVVoucherInfo()
                    onBlack.invoke()
                },
            )
        ),
    )
}
