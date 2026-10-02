package com.imcys.bilibilias.ui.setting.feedback

import kotlinx.serialization.Serializable
import com.imcys.bilibilias.datastore.AppSettings
import androidx.compose.material3.TextButton
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.imcys.bilibilias.BuildConfig
import com.imcys.bilibilias.R
import com.imcys.bilibilias.common.utils.DeviceInfoUtils
import com.imcys.bilibilias.data.diagnostics.FeedbackReportRules
import com.imcys.bilibilias.data.repository.AppSettingsRepository
import com.imcys.bilibilias.download.FileOutputManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「问题反馈」页（合并后的诊断中心）。
 *
 * 合并前的三处入口：`存储管理`（看/导出诊断日志）、`版本信息`（设备信息与复制）、
 * `工具列表` 的 BugReport —— 用户不知道该去哪、导出后还得自己去文件管理器找文件
 * （2026-10-02 用户反馈）。现在：**一屏状态自检 + 一键导出反馈包并直接分享**。
 */
@Composable
fun FeedbackContent(
    modifier: Modifier = Modifier,
    onToBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val koin = remember { org.koin.core.context.GlobalContext.get() }
    val fileOutputManager = remember { koin.get<FileOutputManager>() }
    val appSettingsRepository = remember { koin.get<AppSettingsRepository>() }

    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val deviceInfo = remember { DeviceInfoUtils.getDeviceInfo(context) }
    val deviceCopyText = remember { DeviceInfoUtils.getDeviceInfoCopyString(context) }

    var privacyAgreed by remember { mutableStateOf<Boolean?>(null) }
    var traceText by remember { mutableStateOf("") }
    var lastUpdateLine by remember { mutableStateOf<String?>(null) }
    var cacheLine by remember { mutableStateOf<String?>(null) }
    var namingRule by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { privacyAgreed = appSettingsRepository.hasAgreedPrivacyPolicy() }
        runCatching {
            // ⚠️ 这里**必须用 readTraceText**（读内容）；exportTraceLog 的语义是「导出并返回文件名」✗ ——
            // 真机复验就抓到过这个 bug：日志段为空、更新检查显示 '-'，还每次打开页面都多导出一份日志文件。
            val text = runCatching { fileOutputManager.readTraceText(500) }.getOrDefault("")
            traceText = text
            lastUpdateLine = text.lineSequence()
                .lastOrNull { it.contains("[更新检查]") }
                ?.substringAfter("[更新检查]")
                ?.trim()
        }
        runCatching {
            val settings = appSettingsRepository.appSettingsFlow.first()
            // proto: bili_line_host = 13（空串 = 默认线路）
            // L4：设置里存的是裸 host（upos-sz-mirrorali.bilivideo.com），展示时映射成品牌名（ali（阿里））
            cacheLine = com.imcys.bilibilias.data.diagnostics.LineDisplayRules
                .displayName(settings.biliLineHost)
            // proto: video_naming_rule = 11（命名规则模板，如 {title}_{p}）
            namingRule = settings.videoNamingRule.ifBlank { null }
        }
    }

    val allFilesAccess = remember { fileOutputManager.hasAllFilesAccess() }
    val notificationOn = remember {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.areNotificationsEnabled() ?: true
    }
    val status = FeedbackReportRules.buildStatus(
        appVersion = BuildConfig.VERSION_NAME,
        updateResult = lastUpdateLine,
        privacyAgreed = privacyAgreed == true,
        allFilesAccess = allFilesAccess,
        notificationEnabled = notificationOn,
        lineName = cacheLine,
        namingSummary = namingRule,
    )

    // ⚠️ 这些标签要在 Composable 作用域先取好：onClick 是普通 lambda，里面不能调 stringResource
    val labelAppVersion = stringResource(R.string.version_info_app)
    val labelDeviceModel = stringResource(R.string.version_info_device)
    val labelOsVersion = stringResource(R.string.version_info_os_version)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.feedback_subtitle), style = MaterialTheme.typography.bodyMedium)

        // ---------- 状态自检 ----------
        SectionCard(title = stringResource(R.string.feedback_section_status)) {
            status.forEach { item ->
                StatusRow(
                    label = statusLabel(item.key),
                    value = statusValue(item),
                    ok = item.ok,
                )
                // P1（大加强）：把"后果"写出来 —— 未同意/未授权/未开启时各配一句解释，
                // 让"某个功能像坏了"变成"缺一步、点哪里"（文案走 strings.xml 中英双套）
                if (!item.ok) {
                    statusHint(item.key)?.let { hint ->
                        Text(
                            hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                        )
                    }
                    // P2（大加强）：缺隐私政策同意时，直接给一键「同意隐私政策」——
                    // 由用户在这里点（不是应用自作主张 ✓），点完状态自检立刻变绿、剪贴板/更新检查随之可用
                    if (item.key == FeedbackReportRules.StatusKey.PRIVACY) {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching {
                                    appSettingsRepository.updatePrivacyPolicyAgreement(
                                        AppSettings.AgreePrivacyPolicyState.Agreed,
                                    )
                                    privacyAgreed = true
                                }
                            }
                        }) {
                            Text(stringResource(R.string.feedback_action_agree_privacy))
                        }
                    }
                }
            }
        }

        // ---------- 设备与版本信息 ----------
        SectionCard(title = stringResource(R.string.feedback_section_device)) {
            InfoRow(stringResource(R.string.version_info_app), deviceInfo.appVersion)
            InfoRow(stringResource(R.string.version_info_system), deviceInfo.systemVersion)
            InfoRow(stringResource(R.string.version_info_device), deviceInfo.model)
            InfoRow(stringResource(R.string.version_info_market_model), deviceInfo.marketModel)
            InfoRow(stringResource(R.string.version_info_manufacturer), deviceInfo.manufacturer)
            InfoRow(stringResource(R.string.version_info_brand), deviceInfo.brandName)
            InfoRow(stringResource(R.string.version_info_os_name), deviceInfo.osName)
            InfoRow(stringResource(R.string.version_info_os_version), deviceInfo.osVersionName)
        }

        // ---------- 诊断日志（尾部若干行）----------
        SectionCard(title = stringResource(R.string.feedback_section_log)) {
            val tail = traceText.lineSequence().toList().takeLast(200).joinToString("\n")
            Text(
                tail.ifBlank { "（空）" },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---------- 三个动作 ----------
        Button(
            onClick = {
                val report = FeedbackReportRules.buildReportText(
                    status = status,
                    deviceInfo = listOf(
                        labelAppVersion to deviceInfo.appVersion,
                        labelDeviceModel to deviceInfo.model,
                        labelOsVersion to deviceInfo.osVersionName,
                    ),
                    logTail = traceText.lineSequence().toList().takeLast(500).joinToString("\n"),
                    // 崩溃日志：Android/data/<pkg>/files/logs/crash.log（与 download-trace.log 同目录）
                    crashTail = runCatching {
                        java.io.File(context.getExternalFilesDir(null), "logs/crash.log")
                            .takeIf { it.exists() }
                            ?.readLines()
                            ?.takeLast(200)
                            ?.joinToString("\n")
                    }.getOrNull(),
                )
                val fileName = FeedbackReportRules.reportFileName(
                    java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                        .format(java.util.Date()),
                )
                val saved = runCatching {
                    fileOutputManager.exportTextToDownload(fileName, report, "text/plain")
                }.getOrNull()
                if (saved == null) {
                    Toast.makeText(context, context.getString(R.string.feedback_export_failed, "?"), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.feedback_export_done), Toast.LENGTH_SHORT).show()
                    runCatching {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, fileName)
                            putExtra(Intent.EXTRA_TEXT, report + "\n\n（已保存到：" + saved + "）")
                        }
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.feedback_export_share_title)))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.feedback_action_export))
        }

        OutlinedButton(
            onClick = {
                val clip = ClipData.newPlainText(
                    context.getString(R.string.feedback_title),
                    deviceCopyText + "\n\n" + FeedbackReportRules.buildReportText(status, emptyList(), null, null),
                )
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(clip)
                Toast.makeText(context, context.getString(R.string.feedback_copy_done), Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.feedback_action_copy_all))
        }

        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(FeedbackReportRules.issueUrl(BuildConfig.VERSION_NAME, status)),
                        ),
                    )
                }.onFailure {
                    Toast.makeText(context, context.getString(R.string.feedback_open_issue_failed), Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.feedback_action_open_issue))
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (ok) value else "$value（${stringResource(R.string.feedback_need_attention)}）",
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun statusLabel(key: FeedbackReportRules.StatusKey): String = stringResource(
    when (key) {
        FeedbackReportRules.StatusKey.APP_VERSION -> R.string.feedback_status_app_version
        FeedbackReportRules.StatusKey.UPDATE -> R.string.feedback_status_update
        FeedbackReportRules.StatusKey.PRIVACY -> R.string.feedback_status_privacy
        FeedbackReportRules.StatusKey.ALL_FILES_ACCESS -> R.string.feedback_status_all_files
        FeedbackReportRules.StatusKey.NOTIFICATION -> R.string.feedback_status_notification
        FeedbackReportRules.StatusKey.LINE -> R.string.feedback_status_line
        FeedbackReportRules.StatusKey.NAMING -> R.string.feedback_status_naming
    },
)

@Composable
private fun statusValue(item: FeedbackReportRules.StatusItem): String = when (item.key) {
    FeedbackReportRules.StatusKey.PRIVACY ->
        if (item.ok) stringResource(R.string.feedback_value_agreed) else stringResource(R.string.feedback_value_declined)
    FeedbackReportRules.StatusKey.ALL_FILES_ACCESS ->
        if (item.ok) stringResource(R.string.feedback_value_granted) else stringResource(R.string.feedback_value_denied)
    FeedbackReportRules.StatusKey.NOTIFICATION ->
        if (item.ok) stringResource(R.string.feedback_value_enabled) else stringResource(R.string.feedback_value_disabled)
    else -> item.value
}


/** 「问题反馈」页的路由（合并后的唯一反馈/诊断入口） */
// ⚠️ 必须 @Serializable：Navigation3 在 Activity 状态保存（后台化）时会序列化路由，
// 缺了它就会 `Serializer for class '…' is not found` 崩在 onSaveInstanceState（真机 crash.log 已复现）
@Serializable
data object FeedbackRoute : NavKey

/** 页面外壳：与版本页一致的返回 + 内容 */
@Composable
fun FeedbackScreen(
    feedbackRoute: FeedbackRoute,
    onToBack: () -> Unit = {},
) {
    FeedbackContent(onToBack = onToBack)
}

/** P1：状态不满足时的"后果说明"（只给会静默失效的三项） */
@Composable
private fun statusHint(key: FeedbackReportRules.StatusKey): String? = when (key) {
    FeedbackReportRules.StatusKey.PRIVACY -> stringResource(R.string.feedback_hint_privacy)
    FeedbackReportRules.StatusKey.ALL_FILES_ACCESS -> stringResource(R.string.feedback_hint_all_files)
    FeedbackReportRules.StatusKey.NOTIFICATION -> stringResource(R.string.feedback_hint_notification)
    else -> null
}
