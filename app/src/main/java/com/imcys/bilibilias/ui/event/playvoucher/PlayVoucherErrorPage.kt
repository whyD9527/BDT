package com.imcys.bilibilias.ui.event.playvoucher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.imcys.bilibilias.R
import com.imcys.bilibilias.ui.widget.ASTopAppBar
import com.imcys.bilibilias.ui.widget.ASIconButton
import com.imcys.bilibilias.ui.widget.BILIBILIASTopAppBarStyle
import org.koin.compose.viewmodel.koinViewModel


@Preview
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayVoucherErrorPage(onBlack:()-> Unit = {}) {
    val vm = koinViewModel<PlayVoucherErrorViewModel>()
    Scaffold(
        topBar = {
            ASTopAppBar(
                style = BILIBILIASTopAppBarStyle.Small,
                title = { Text(stringResource(R.string.play_voucher_error_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                navigationIcon = {
                    ASIconButton(onClick = {}) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                actions = {}
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(16.dp)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.voucher_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                val points = listOf(
                    R.string.voucher_risk_1,
                    R.string.voucher_risk_2,
                    R.string.voucher_risk_3,
                    R.string.voucher_risk_4,
                    R.string.voucher_risk_5,
                    R.string.voucher_risk_6
                )
                items(points.size) { index ->
                    Text(
                        // ⚠️ `LazyColumn` 的 content 是 `LazyListScope.() -> Unit`，**不是** @Composable
                        // —— 只有 `item { }` / `items { }` 的 lambda 里才能调 `stringResource`。
                        // 所以上面 listOf 存的是**资源 id**，这里才取文案。
                        text = "• " + stringResource(points[index]),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.voucher_footer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            Button(
                onClick = {
                    vm.ontUseTVVoucherInfo()
                    onBlack.invoke()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text(stringResource(R.string.common_i_know))
            }
        }
    }
}