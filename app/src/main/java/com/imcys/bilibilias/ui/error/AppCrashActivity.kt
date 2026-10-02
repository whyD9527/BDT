package com.imcys.bilibilias.ui.error

import android.content.ClipData
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.imcys.bilibilias.R
import com.imcys.bilibilias.ui.theme.BILIBILIASTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/**
 * APP 未捕获异常崩溃页。
 *
 * 2026-10-02 随 `AppCrashHandler` 一起重新启用：页面由 handler 拉起，显示**截断后的堆栈**
 * （完整报告在 `logs/crash.log`，可用「存储管理 → 导出诊断日志」导出），并提供复制/退出。
 */
class AppCrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // extra 名与 AppCrashHandler 共用一个常量（写/读两头不会走偏）
        val appErrorMsg = intent.getStringExtra(AppCrashHandler.EXTRA_ERROR_MSG)
            ?: getString(R.string.crash_unknown_error)
        setContent {
            BILIBILIASTheme {
                Scaffold {
                    Column(
                        modifier = Modifier
                            .padding(it)
                            .fillMaxSize()
                    ) {
                        AppErrorPage(appErrorMsg)
                    }
                }
            }
        }
    }

    @Composable
    fun AppErrorPage(appErrorMsg: String) {
        val clipboardManager = LocalClipboard.current
        val coroutineScope = rememberCoroutineScope()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 10.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.crash_error_title, appErrorMsg),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.crash_log_saved_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.Center
            ) {
                Button(onClick = {
                    exitProcess(0)
                }) {
                    Text(stringResource(R.string.error_exit_app))
                }
                Spacer(Modifier.width(10.dp))
                Button(onClick = {
                    val clipData = ClipData.newPlainText(getString(R.string.clip_error_label), appErrorMsg)
                    val clipEntry = ClipEntry(clipData)
                    coroutineScope.launch(Dispatchers.IO) {
                        clipboardManager.setClipEntry(clipEntry)
                        delay(2000)
                    }
                }) {
                    Text(stringResource(R.string.error_copy_error))
                }
                Spacer(Modifier.width(10.dp))
                Button(onClick = {
                    // 合并（2026-10-02 用户反馈）：崩溃时**一键**导出反馈包并分享 ——
                    // 不再让用户自己跑去「存储管理 → 导出诊断日志」找文件（原先提示就是这么写的 ✗）。
                    coroutineScope.launch {
                        runCatching {
                            val manager = org.koin.core.context.GlobalContext.get()
                                .get<com.imcys.bilibilias.download.FileOutputManager>()
                            val crashTail = java.io.File(getExternalFilesDir(null), "logs/crash.log")
                                .takeIf { it.exists() }
                                ?.readLines()
                                ?.takeLast(200)
                                ?.joinToString("\n")
                            val report = com.imcys.bilibilias.data.diagnostics.FeedbackReportRules.buildReportText(
                                status = emptyList(),
                                deviceInfo = com.imcys.bilibilias.common.utils.DeviceInfoUtils
                                    .getDeviceInfoCopyString(this@AppCrashActivity)
                                    .lines()
                                    .mapNotNull { line ->
                                        val i = line.indexOf('：').takeIf { it > 0 } ?: line.indexOf(':')
                                        if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else null
                                    },
                                logTail = appErrorMsg,
                                crashTail = crashTail,
                            )
                            val fileName = com.imcys.bilibilias.data.diagnostics.FeedbackReportRules.reportFileName(
                                java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                                    .format(java.util.Date()),
                            )
                            manager.exportTextToDownload(fileName, report, "text/plain")
                            startActivity(
                                android.content.Intent.createChooser(
                                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_SUBJECT, fileName)
                                        putExtra(android.content.Intent.EXTRA_TEXT, report)
                                    },
                                    getString(R.string.feedback_export_share_title),
                                ),
                            )
                        }
                    }
                }) {
                    Text(stringResource(R.string.crash_export_report))
                }


            }
        }
    }
}

