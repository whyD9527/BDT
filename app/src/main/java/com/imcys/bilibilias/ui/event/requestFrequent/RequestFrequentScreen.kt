package com.imcys.bilibilias.ui.event.requestFrequent

import android.os.Process.killProcess
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.imcys.bilibilias.R
import com.imcys.bilibilias.widget.ASMessageAction
import com.imcys.bilibilias.widget.ASMessagePage
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Serializable
data class RequestFrequentRoute(
    val url: String
) : NavKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestFrequentScreen(
    requestFrequentRoute: RequestFrequentRoute,
    onToBack: () -> Unit,
) {

    // 拦截返回
    BackHandler {}

    RequestFrequentScaffold {
        RequestFrequentContent(
            url = requestFrequentRoute.url,
            paddingValues = it,
            onToBack,
        )
    }
}

@Composable
fun RequestFrequentContent(url: String, paddingValues: PaddingValues, onToBack: () -> Unit) {

    val vm = koinViewModel<RequestFrequentViewModel>()
    val state by vm.uiState.collectAsState()

    AnimatedContent(state) {
        when (it) {
            RequestFrequentUIState.Default -> DefaultScreen(paddingValues, onRetry = {
                vm.retryRequest(url)
            })

            RequestFrequentUIState.Loading -> {
                Column(
                    Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.request_frequent_testing), modifier = Modifier.padding(top = 16.dp))
                }
            }

            RequestFrequentUIState.Success -> {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.request_frequent_success), modifier = Modifier.padding(top = 16.dp))
                    Button(onClick = onToBack) {
                        Text(stringResource(R.string.request_frequent_continue))
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultScreen(
    paddingValues: PaddingValues,
    onRetry: () -> Unit = {}
) {
    // #6（2026-10-05）：排版收敛到通用提示页组件（与「大会员凭证失效」页共用一份实现）
    ASMessagePage(
        title = stringResource(R.string.request_frequent_incident),
        modifier = Modifier.padding(paddingValues),
        iconRes = R.drawable.ic_cloud_alert_24px,
        messages = listOf(
            stringResource(R.string.request_frequent_server_busy),
            stringResource(R.string.request_frequent_warning),
        ),
        actions = listOf(
            ASMessageAction(
                text = stringResource(R.string.common_retry),
                onClick = onRetry,
            ),
            // 退出APP的按钮
            ASMessageAction(
                text = stringResource(R.string.request_frequent_exit_app),
                onClick = { killProcess(android.os.Process.myPid()) },
                secondary = true,
            ),
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestFrequentScaffold(
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        content(it)
    }

}