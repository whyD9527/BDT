package com.imcys.bilibilias

import androidx.compose.foundation.verticalScroll
import com.imcys.bilibilias.common.update.GitHubUpdateChecker
import com.imcys.bilibilias.data.update.GitHubUpdateRules
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import com.imcys.bilibilias.common.event.AnalysisEvent
import com.imcys.bilibilias.common.event.StartTarget
import com.imcys.bilibilias.common.event.sendAnalysisEvent
import com.imcys.bilibilias.common.event.sendStartTargetEvent
import com.imcys.bilibilias.common.update.GooglePlayAppUpdateManage
import com.imcys.bilibilias.common.utils.Manufacturers.XIAOMI
import com.imcys.bilibilias.common.utils.createDownloadNotificationChannel
import com.imcys.bilibilias.data.repository.AppSettingsRepository
import com.imcys.bilibilias.datastore.AppSettings
import com.imcys.bilibilias.ui.BILIBILIASAppScreen
import com.imcys.bilibilias.ui.theme.BILIBILIASTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import androidx.compose.runtime.collectAsState
import com.imcys.bilibilias.common.data.CommonBuildConfig
import com.imcys.bilibilias.ui.widget.ASTextButton

class MainActivity : ComponentActivity() {

    companion object {
        /**
         * B3「点通知直达」：通知 Intent 里的"启动目标"参数名与取值。
         *
         * 放在这里是因为**读写两头都在本模块**：`DownloadService` 写、
         * `MainActivity` 自己读；用常量而不是裸字符串，改一处不会漏掉另一处。
         */
        const val EXTRA_START_TARGET = "com.imcys.bilibilias.extra.START_TARGET"
        const val START_TARGET_DOWNLOAD_LIST = "download_list"
    }

    private val appSettingsRepository: AppSettingsRepository by inject()
    private val fileOutputManager: com.imcys.bilibilias.download.FileOutputManager by inject()

    /** GitHub 检测到的新版本（非空即弹应用内提示） */
    private val githubUpdateInfo =
        MutableStateFlow<com.imcys.bilibilias.common.update.GitHubUpdateChecker.UpdateInfo?>(null)

    private val appSettingsFlow: Flow<AppSettings> = appSettingsRepository.appSettingsFlow

    // 更新相关
    private var showUpdateSnackBar = MutableStateFlow(false)
    private var showSkipVersion = MutableStateFlow(false)
    private var googlePlaySkipVersionListener: () -> Unit = {}
    private var performedInstallListen = {}

    private var agreePrivacyPolicyState: AppSettings.AgreePrivacyPolicyState =
        AppSettings.AgreePrivacyPolicyState.Default


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // GitHub 更新提示（发现新版本才显示）
            GithubUpdateDialog(
                info = githubUpdateInfo.collectAsState().value,
                onDismiss = { githubUpdateInfo.value = null },
                onSkip = { info ->
                    githubUpdateInfo.value = null
                    lifecycleScope.launch(Dispatchers.IO) {
                        appSettingsRepository.updateLastSkipUpdateVersionCode(info.version.encode())
                        fileOutputManager.logDiagnostic("更新检查", "用户跳过版本 ${info.tag}")
                    }
                },
                onDownload = { url ->
                    githubUpdateInfo.value = null
                    runCatching {
                        startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url),
                            ),
                        )
                    }
                },
            )
            var enabledDynamicColor by remember { mutableStateOf(false) }
            val updateSnackBarHostState = remember { SnackbarHostState() }
            val showSkipVersionState by showSkipVersion.collectAsState()

            LaunchedEffect(Unit) {
                appSettingsFlow.collect {
                    enabledDynamicColor = it.enabledDynamicColor
                }
            }

            LaunchedEffect(showUpdateSnackBar) {
                if (!showUpdateSnackBar.value) return@LaunchedEffect
                val result = updateSnackBarHostState.showSnackbar(
                    message = getString(R.string.update_downloaded),
                    actionLabel = getString(R.string.update_action),
                    duration = SnackbarDuration.Short
                )
                when (result) {
                    SnackbarResult.ActionPerformed -> {
                        showUpdateSnackBar.value = false
                        performedInstallListen.invoke()
                    }

                    SnackbarResult.Dismissed -> {
                        showUpdateSnackBar.value = false
                    }
                }
            }

            BILIBILIASTheme(dynamicColor = enabledDynamicColor) {
                Box {
                    BILIBILIASAppScreen()
                    SnackbarHost(
                        hostState = updateSnackBarHostState,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                    SkipVersionDialog(showSkipVersionState, onConfirm = {
                        lifecycleScope.launch {
                            googlePlaySkipVersionListener.invoke()
                            showSkipVersion.value = false
                        }
                    }, onDismiss = {
                        showSkipVersion.value = false
                    })
                }
            }
        }
        // 处理特殊厂商的适配选项
        specialManufacturersOption()
        // 初始化设置
        initAppSetting()
        // 初始化通知渠道
        initNotificationChannel()
        // 更新检查
        initUpdateCheck()
        handleShareInfo(intent)
    }

    @Composable
    fun SkipVersionDialog(value: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit = {}) {
        if (value) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.update_skip_title)) },
                text = { Text(stringResource(R.string.update_skip_message)) },
                confirmButton = {
                    ASTextButton(onClick = onConfirm) {
                        Text(stringResource(R.string.update_skip_this_version))
                    }

                },
                dismissButton = {
                    ASTextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.update_later))
                    }
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareInfo(intent)
    }

    private fun handleShareInfo(incoming: Intent?) {
        if (incoming == null) return
        // B3 点通知直达：通知里带的"启动目标" → 发事件让导航层跳下载管理页。
        // ⚠️ 消费掉这个 extra（removeExtra）：MainActivity 是 singleTask，
        // 同一个 Intent 在重建/再次 onNewIntent 时会被复用，不清理会重复触发导航。
        incoming.getStringExtra(EXTRA_START_TARGET)?.let { target ->
            if (target == START_TARGET_DOWNLOAD_LIST) {
                sendStartTargetEvent(StartTarget.DOWNLOAD_LIST)
            }
            incoming.removeExtra(EXTRA_START_TARGET)
        }
        val action = incoming.action
        val type = incoming.type
        when (action) {
            Intent.ACTION_SEND -> {
                if (type?.startsWith("text/") == true) {
                    val sharedText = incoming.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                    sendAnalysisEvent(AnalysisEvent(analysisText = sharedText))
                }
            }

            Intent.ACTION_MAIN -> {
                // 正常启动
            }
        }
    }


    private fun specialManufacturersOption() {
        val manufacturer = Build.MANUFACTURER.lowercase()
        when {
            manufacturer.contains(XIAOMI) -> {
                // 设置沉浸式虚拟键，在MIUI系统中，虚拟键背景透明。原生系统中，虚拟键背景半透明。
                window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            }
        }
    }

    /**
     * 初始化APP设置
     */
    private fun initAppSetting() {
        lifecycleScope.launch(Dispatchers.IO) {
            appSettingsFlow.collect {
                agreePrivacyPolicyState = it.agreePrivacyPolicy
                CommonBuildConfig.agreedPrivacyPolicy = it.agreePrivacyPolicy ==
                        AppSettings.AgreePrivacyPolicyState.Agreed
            }
        }
    }

    private fun initNotificationChannel() {
        // 创建文件下载进度渠道
        createDownloadNotificationChannel(
            getString(R.string.notification_channel_name),
            getString(R.string.notification_channel_desc),
        )
    }


    /**
     * 初始化更新检查
     */
    private fun initUpdateCheck() {
        // ⚠️ 2026-10-02：更新检查**从 Google Play 换成 GitHub Releases**。
        // 原因：alpha 渠道 `ENABLED_PLAY_APP_MODE = false` → 原生 Play 检查从不执行；
        // 而且侧载安装的包 Play 也查不到新版本（用户反映"根本没有检测更新"）。
        lifecycleScope.launch(Dispatchers.IO) {
            // 隐私门槛：未同意隐私政策不发请求（与剪贴板自动识别同一口径）
            if (!GitHubUpdateRules.shouldCheck(appSettingsRepository.hasAgreedPrivacyPolicy())) {
                fileOutputManager.logDiagnostic("更新检查", "未同意隐私政策，跳过（不发请求）")
                return@launch
            }
            val info = GitHubUpdateChecker.check(
                currentVersionName = BuildConfig.VERSION_NAME,
                abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                lastSkippedCode = appSettingsRepository.getLastSkipUpdateVersionCode(),
            ) { tag, message -> fileOutputManager.logDiagnostic(tag, message) }
            if (info != null) {
                githubUpdateInfo.value = info
            }
        }
    }

    private val appUpdateLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result: ActivityResult ->
            // handle callback
            if (result.resultCode != RESULT_OK) {
                // 详见：https://developer.android.google.cn/guide/playcore/in-app-updates/kotlin-java?hl=zh-cn#setup
                showSkipVersion.value = true
            }
        }

    /**
     * 处理Google Play更新
     */
    private fun GooglePlayAppUpdateManage.handleGooglePlayUpdate() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (checkAppImmediateUpdate()) {
                startUpdate(appUpdateLauncher, updateFinish = {
                    showGooglePlayUpdateSnackBar()
                })
            }
        }
    }

    /**
     * 显示Google Play更新完成提示
     */
    private fun GooglePlayAppUpdateManage.showGooglePlayUpdateSnackBar() {
        lifecycleScope.launch {
            // 提示更新完成
            showUpdateSnackBar.value = true
            performedInstallListen = {
                // 重启更新
                completeUpdate()
            }
        }
    }

}


@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    BILIBILIASTheme {
        BILIBILIASAppScreen()
    }


}

/** GitHub 更新提示：版本号 + 更新要点 + 下载 / 跳过此版本 / 稍后 */
@androidx.compose.runtime.Composable
private fun GithubUpdateDialog(
    info: com.imcys.bilibilias.common.update.GitHubUpdateChecker.UpdateInfo?,
    onDismiss: () -> Unit,
    onSkip: (com.imcys.bilibilias.common.update.GitHubUpdateChecker.UpdateInfo) -> Unit,
    onDownload: (String) -> Unit,
) {
    val current = info ?: return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            androidx.compose.material3.Text("发现新版本 ${current.tag}")
        },
        text = {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                androidx.compose.material3.Text(
                    current.notes.ifBlank { "有可用的新版本，建议更新。" },
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
                if (current.apkUrl == null) {
                    androidx.compose.material3.Text(
                        "\n（该版本没有匹配本机架构的 APK，请到 Releases 页面手动下载）",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (current.apkUrl != null) {
                androidx.compose.material3.TextButton(onClick = { onDownload(current.apkUrl) }) {
                    androidx.compose.material3.Text(stringResource(R.string.update_download))
                }
            }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                androidx.compose.material3.TextButton(onClick = { onSkip(current) }) {
                    androidx.compose.material3.Text(stringResource(R.string.update_skip_version))
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    androidx.compose.material3.Text(stringResource(R.string.common_cancel))
                }
            }
        },
    )
}
