package com.imcys.bilibilias.ui.setting.about

import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import com.imcys.bilibilias.R
import com.imcys.bilibilias.ui.widget.ASTopAppBar
import com.imcys.bilibilias.ui.widget.AsBackIconButton
import com.imcys.bilibilias.ui.widget.BILIBILIASTopAppBarStyle
import com.imcys.bilibilias.widget.maybeNestedScroll
import kotlinx.serialization.Serializable

@Serializable
data object AboutRouter : NavKey

@Preview
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(aboutRouter: AboutRouter = AboutRouter, onToBack: () -> Unit = {}) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            ASTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                scrollBehavior = scrollBehavior,
                style = BILIBILIASTopAppBarStyle.Large,
                title = { Text(text = stringResource(R.string.setting_about_item)) },
                navigationIcon = {
                    AsBackIconButton { onToBack.invoke() }
                },
                alwaysDisplay = false
            )
        },
    ) { paddingValues ->
        AboutContent(
            modifier = Modifier.maybeNestedScroll(scrollBehavior),
            paddingValues = paddingValues,
        )
    }

}

@Composable
fun AboutContent(
    modifier: Modifier = Modifier,
    paddingValues: PaddingValues,
) {
    LazyColumn(
        modifier = modifier
            .padding(paddingValues)
            .fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            // A-②：手动「检查更新」。**同样遵守隐私门槛**（未同意时不发请求）；
            // 结果与失败原因都会写进诊断日志（download-trace.log 里搜「更新检查」即可核验）。
            CheckUpdateButton()
        }
        item {
            IconArea()
        }
        item {
            Spacer(Modifier.height(10.dp))
            TitleArea()
        }
    }
}

@Composable
fun TitleArea(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            text = stringResource(id = R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = stringResource(R.string.about_slogan),
            textAlign = TextAlign.Center,
            fontSize = 17.sp
        )

        Card(
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    modifier = Modifier.padding(top = 12.dp),
                    text = stringResource(R.string.about_open_source_notice),
                )
            }
        }
    }
}

@Composable
fun IconArea() {
    Icon(
        modifier = Modifier
            .padding(4.dp)
            .size(60.dp),
        painter = painterResource(id = R.drawable.ic_logo_mini),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
    )
}

/** 关于页的「检查更新」按钮（A-②）。手动触发，但**仍受隐私门槛约束**。 */
@Composable
private fun CheckUpdateButton() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.material3.OutlinedButton(
        onClick = {
            scope.launch {
                val koin = org.koin.core.context.GlobalContext.get()
                val repo = koin.get<com.imcys.bilibilias.data.repository.AppSettingsRepository>()
                val log = koin.get<com.imcys.bilibilias.download.FileOutputManager>()
                val agreed = runCatching { repo.hasAgreedPrivacyPolicy() }.getOrDefault(false)
                if (!agreed) {
                    // 与自动检查同一门槛：未同意就不发请求（并留下日志）
                    log.logDiagnostic("更新检查", "手动检查：未同意隐私政策，跳过（不发请求）")
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.about_need_privacy),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }
                val info = com.imcys.bilibilias.common.update.GitHubUpdateChecker.check(
                    currentVersionName = com.imcys.bilibilias.BuildConfig.VERSION_NAME,
                    abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                    lastSkippedCode = runCatching { repo.getLastSkipUpdateVersionCode() }.getOrDefault(0),
                ) { tag, message -> log.logDiagnostic(tag, message) }
                android.widget.Toast.makeText(
                    context,
                    if (info != null) context.getString(R.string.about_update_found, info.tag)
                    else context.getString(R.string.about_update_latest),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        },
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
    ) {
        androidx.compose.material3.Text(stringResource(R.string.about_check_update))
    }
}
