package com.imcys.bilibilias.ui.setting

import org.koin.compose.koinInject
import kotlinx.coroutines.withContext
import android.Manifest.permission
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.outlined.AirplaneTicket
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.navigation3.runtime.NavKey
import com.imcys.bilibilias.R
import com.imcys.bilibilias.data.download.segmented.SegmentedDownloadPlan
import com.imcys.bilibilias.datastore.AppSettings
import com.imcys.bilibilias.datastore.AppSettings.AgreePrivacyPolicyState.Agreed
import com.imcys.bilibilias.datastore.AppSettings.AgreePrivacyPolicyState.Refuse
import com.imcys.bilibilias.ui.PrivacyPolicyDialog
import com.imcys.bilibilias.ui.PrivacyPolicyRefuseDialog
import com.imcys.bilibilias.ui.setting.platform.ParsePlatformRoute
import com.imcys.bilibilias.ui.utils.switchHapticFeedback
import com.imcys.bilibilias.ui.widget.ASAlertDialog
import com.imcys.bilibilias.ui.widget.ASTextButton
import com.imcys.bilibilias.ui.widget.ASTopAppBar
import com.imcys.bilibilias.ui.widget.AsBackIconButton
import com.imcys.bilibilias.ui.widget.BILIBILIASTopAppBarStyle
import com.imcys.bilibilias.ui.widget.BaseSettingsItem
import com.imcys.bilibilias.ui.widget.CategorySettingsItem
import com.imcys.bilibilias.ui.widget.SwitchSettingsItem
import com.imcys.bilibilias.widget.dialog.PermissionRequestTipDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel


@Preview
@Composable
fun SettingScreenPreview() {
    SettingScreen(
        onToBack = {},
        onToComplaint = {},
        onToLayoutTypeset = {})
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingScreen(
    onToComplaint: () -> Unit,
    onToLayoutTypeset: () -> Unit,
    onToBack: () -> Unit,
    onToAbout: () -> Unit = {},
    onToVersionInfo: () -> Unit = {},
    onToSystemExpand: () -> Unit = {},
    onToStorageManagement: () -> Unit = {},
    onToNamingConvention: () -> Unit = {},
    onToLineConfig: () -> Unit = {},
    onToPage: (navKey: NavKey) -> Unit = {},
    onLogoutFinish: (Long) -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val vm = koinViewModel<SettingViewModel>()

    // 「备份设置 / 恢复设置」：一天重装三次的人最需要这个（2026-10-02）
    val appSettingsRepository: com.imcys.bilibilias.data.repository.AppSettingsRepository = koinInject()
    val backupFileOutputManager: com.imcys.bilibilias.download.FileOutputManager = koinInject()
    val backupContext = androidx.compose.ui.platform.LocalContext.current
    val backupScope = rememberCoroutineScope()
    val currentAppSettings by appSettingsRepository.appSettingsFlow
        .collectAsState(initial = null)
    val restoreSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    backupContext.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            val backup = text?.let { com.imcys.bilibilias.data.backup.AppSettingsBackup.fromJson(it) }
            if (backup == null) {
                Toast.makeText(backupContext, backupContext.getString(R.string.setting_backup_invalid), Toast.LENGTH_LONG).show()
                return@launch
            }
            appSettingsRepository.updateSettings { current ->
                com.imcys.bilibilias.data.backup.AppSettingsBackupRules.overlay(current, backup)
            }
            Toast.makeText(backupContext, backupContext.getString(R.string.setting_restore_done), Toast.LENGTH_LONG).show()
        }
    }
    val appSettings by vm.appSettings.collectAsState(initial = AppSettings.getDefaultInstance())
    val haptics = LocalHapticFeedback.current
    var showLogoutDialog by remember { mutableStateOf(false) }
    val uiState by vm.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var showLogoutLoading by remember { mutableStateOf(false) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var showPrivacyPolicyRefuseTip by remember { mutableStateOf(false) }
    var showSegmentConcurrencyDialog by remember { mutableStateOf(false) }
    var showSpeedLimitDialog by remember { mutableStateOf(false) }

    // 下载策略（B5）。proto 里是 optional：没设置过 → 仅Wi-Fi=关、限速=不限
    val wifiOnlyDownload = if (appSettings.hasWifiOnlyDownload()) appSettings.wifiOnlyDownload else false
    val speedLimitKbps = if (appSettings.hasDownloadSpeedLimitKbps()) appSettings.downloadSpeedLimitKbps else 0
    val skipDownloaded = if (appSettings.hasSkipDownloaded()) appSettings.skipDownloaded else false

    // 分片下载的两个设置。proto 里是 `optional`，"没设置过"（initial 的 getDefaultInstance
    // 也是这个状态）要按默认走，所以统一交给 resolve* 解析 —— 与下载侧同一套函数。
    val segmentedEnabled = SegmentedDownloadPlan.resolveEnabled(
        if (appSettings.hasSegmentedDownloadEnabled()) appSettings.segmentedDownloadEnabled else null
    )
    val segmentedConcurrency = SegmentedDownloadPlan.resolveConcurrency(
        if (appSettings.hasSegmentedDownloadConcurrency()) appSettings.segmentedDownloadConcurrency
        else null
    )


    SettingScaffold(scrollBehavior, onToBack) {

        LazyColumn(
            modifier = Modifier
                .padding(it)
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {

            item {
                CategorySettingsItem(
                    text = stringResource(R.string.analysis_cache_config)
                )
            }
//            item {
//                SwitchSettingsItem(
//                    imageVector = Icons.Outlined.EnergySavingsLeaf,
//                    text = "省电模式",
//                    description = "开启后将不使用FFmpeg进行视频处理，改用原生API处理。",
//                    checked = false,
//                ) {
//
//                }
//            }
//
//            item {
//                SwitchSettingsItem(
//                    painter = rememberVectorPainter(Icons.Outlined.AudioFile),
//                    text = "音频转码",
//                    description = "启用选择仅音频缓存可以得到mp3的音频文件",
//                    checked = false,
//                ) {
//                }
//            }

//            item {
//                BaseSettingsItem(
//                    painter = rememberVectorPainter(Icons.Outlined.DriveFileRenameOutline),
//                    text = "命名规则",
//                    descriptionText = "使用自定义规则进行视频命名",
//                    onClick = {
//                    }
//                )
//            }

            item {
                BaseSettingsItem(
                    painter = painterResource(R.drawable.ic_save_24px),
                    text = stringResource(R.string.setting_storage_management),
                    descriptionText = stringResource(R.string.setting_storage_management_desc),
                    onClick = onToStorageManagement
                )
            }


            item {
                BaseSettingsItem(
                    painter = rememberVectorPainter(Icons.Outlined.Save),
                    text = stringResource(R.string.setting_cache_dir),
                    descriptionText = com.imcys.bilibilias.download.DownloadDir.RELATIVE_PATH,
                    onClick = {
                    }
                )
            }



            item {
                BaseSettingsItem(
                    painter = rememberVectorPainter(Icons.Outlined.Edit),
                    text = stringResource(R.string.setting_naming_convention),
                    descriptionText = stringResource(R.string.setting_naming_rule_desc),
                    onClick = onToNamingConvention
                )
            }

            item {
                BaseSettingsItem(
                    painter = rememberVectorPainter(Icons.Outlined.Save),
                    text = stringResource(R.string.backup_settings),
                    descriptionText = stringResource(R.string.backup_settings_desc),
                    onClick = {
                        val settings = currentAppSettings
                        if (settings == null) {
                            Toast.makeText(backupContext, backupContext.getString(R.string.setting_not_loaded), Toast.LENGTH_SHORT).show()
                        } else {
                            backupScope.launch {
                                val name = withContext(Dispatchers.IO) {
                                    val ts = java.text.SimpleDateFormat(
                                        "yyyyMMdd-HHmmss", java.util.Locale.ROOT
                                    ).format(java.util.Date())
                                    val version = runCatching {
                                        backupContext.packageManager
                                            .getPackageInfo(backupContext.packageName, 0).versionName
                                    }.getOrNull().orEmpty()
                                    val backup = com.imcys.bilibilias.data.backup.AppSettingsBackupRules
                                        .from(settings, ts, version)
                                    backupFileOutputManager.exportTextToDownload(
                                        "BDT-设置备份-$ts.json",
                                        backup.toJson(),
                                    )
                                }
                                Toast.makeText(
                                    backupContext,
                                    if (name != null) {
                                        backupContext.getString(R.string.setting_backup_exported, name)
                                    } else {
                                        backupContext.getString(R.string.setting_backup_export_failed)
                                    },
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                )
            }

            item {
                BaseSettingsItem(
                    painter = rememberVectorPainter(Icons.Outlined.Cloud),
                    text = stringResource(R.string.restore_settings),
                    descriptionText = stringResource(R.string.restore_settings_desc),
                    onClick = { restoreSettingsLauncher.launch(arrayOf("application/json", "*/*")) }
                )
            }


            item {
                SwitchSettingsItem(
                    imageVector = Icons.Outlined.Bolt,
                    text = stringResource(R.string.setting_segmented_title),
                    description = stringResource(R.string.setting_segmented_desc),
                    checked = segmentedEnabled,
                ) { check ->
                    haptics.switchHapticFeedback(check)
                    vm.updateSegmentedDownloadEnabled(check)
                }
            }

            item {
                SwitchSettingsItem(
                    imageVector = Icons.Outlined.Cloud,
                    text = stringResource(R.string.wifi_only_download),
                    description = stringResource(R.string.wifi_only_download_desc),
                    checked = wifiOnlyDownload,
                ) { check ->
                    haptics.switchHapticFeedback(check)
                    backupScope.launch { appSettingsRepository.updateWifiOnlyDownload(check) }
                }
            }

            item {
                SwitchSettingsItem(
                    imageVector = Icons.Outlined.Policy,
                    text = stringResource(R.string.skip_downloaded),
                    description = stringResource(R.string.skip_downloaded_desc),
                    checked = skipDownloaded,
                ) { check ->
                    haptics.switchHapticFeedback(check)
                    backupScope.launch { appSettingsRepository.updateSkipDownloaded(check) }
                }
            }

            item {
                BaseSettingsItem(
                    painter = rememberVectorPainter(Icons.Outlined.Speed),
                    text = stringResource(R.string.download_speed_limit),
                    descriptionText = if (speedLimitKbps <= 0) stringResource(R.string.speed_limit_none) else "$speedLimitKbps KB/s",
                    onClick = { showSpeedLimitDialog = true }
                )
            }

            if (segmentedEnabled) {
                item {
                    BaseSettingsItem(
                        painter = rememberVectorPainter(Icons.Outlined.Speed),
                        text = stringResource(R.string.setting_segmented_concurrency_title),
                        descriptionText = stringResource(R.string.setting_segmented_concurrency_desc, segmentedConcurrency),
                        onClick = { showSegmentConcurrencyDialog = true }
                    )
                }
            }


            item {
                CategorySettingsItem(
                    text = stringResource(R.string.setting_theme_category)
                )
            }
            item {
                SwitchSettingsItem(
                    imageVector = Icons.Outlined.Palette,
                    text = stringResource(R.string.setting_dynamic_color),
                    description = stringResource(R.string.setting_dynamic_color_desc),
                    checked = appSettings.enabledDynamicColor,
                ) { check ->
                    haptics.switchHapticFeedback(check)
                    vm.updateEnabledDynamicColor(check)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item {
                    CategorySettingsItem(
                        text = stringResource(R.string.setting_permission_category)
                    )
                }
            }

            item {
                DownloadPostNotifications()
            }


            item {
                CategorySettingsItem(
                    text = stringResource(R.string.setting_layout_category)
                )
            }

            item {
                BaseSettingsItem(
                    painter = rememberVectorPainter(Icons.AutoMirrored.Outlined.ListAlt),
                    text = stringResource(R.string.setting_home_layout),
                    description = {},
                    onClick = onToLayoutTypeset
                )
            }



            item {
                CategorySettingsItem(
                    text = stringResource(R.string.setting_parse_category)
                )
            }

            item {
                BaseSettingsItem(
                    painter = rememberVectorPainter(Icons.Outlined.Hub),
                    text = stringResource(R.string.setting_parse_platform),
                    descriptionText = stringResource(R.string.setting_parse_platform_desc),
                    onClick = { onToPage(ParsePlatformRoute) }
                )
            }

            item {
                SwitchSettingsItem(
                    imageVector = Icons.Default.ContentPaste,
                    text = stringResource(R.string.setting_auto_parse),
                    description = stringResource(R.string.setting_auto_parse_desc),
                    checked = appSettings.enabledClipboardAutoHandling,
                ) { check ->
                    vm.updateClipboardAutoHandling(check)
                }
            }


            item {
                CategorySettingsItem(
                    text = stringResource(R.string.setting_about_category)
                )
            }


            item {
                BaseSettingsItem(
                    painter = rememberVectorPainter(Icons.Outlined.Group),
                    text = stringResource(R.string.setting_about_item),
                    descriptionText = stringResource(R.string.setting_about_desc),
                    onClick = onToAbout
                )

            }


            item {
                BaseSettingsItem(
                    painter = painterResource(R.drawable.ic_github_24px),
                    text = stringResource(R.string.setting_github_repo),
                    description = {},
                    onClick = {
                        val intent = Intent().apply {
                            action = "android.intent.action.VIEW"
                            // 原先指向原作者仓库 1250422131/bilibilias，改为本 fork 自己的地址
                            data = "https://github.com/whyD9527/BDT".toUri()
                        }
                        context.startActivity(intent)
                    }
                )
            }

//            item {
//                BaseSettingsItem(
//                    painter = painterResource(R.drawable.ic_licens_24px),
//                    text = "第三方开源许可",
//                    description = {},
//                    onClick = {
//
//                    }
//                )
//            }

//            item {
//                CategorySettingsItem(
//                    text = "投诉与反馈"
//                )
//            }
//
//            item {
//                BaseSettingsItem(
//                    painter = rememberVectorPainter(Icons.Outlined.MoodBad),
//                    text = "投诉",
//                    descriptionText = "向BDT投诉违规行为",
//                    onClick = onToComplaint
//                )
//            }


            item {
                CategorySettingsItem(
                    text = stringResource(R.string.setting_account_category)
                )
            }

            item {
                BaseSettingsItem(
                    painter = rememberVectorPainter(Icons.Outlined.Android),
                    text = stringResource(R.string.setting_device_info),
                    descriptionText = stringResource(R.string.setting_device_info_desc),
                    onClick = onToVersionInfo
                )

            }

            item {
                BaseSettingsItem(
                    painter = rememberVectorPainter(Icons.Outlined.Policy),
                    text = stringResource(R.string.common_privacy_policy),
                    descriptionText = stringResource(
                        R.string.setting_privacy_status,
                        stringResource(
                            when (appSettings.agreePrivacyPolicy) {
                                Agreed -> R.string.setting_privacy_agreed
                                Refuse -> R.string.setting_privacy_refused
                                else -> R.string.setting_privacy_unselected
                            }
                        ),
                    ),
                    onClick = { showPrivacyPolicy = true }
                )
            }

            if (uiState.isLogin) {
                item {
                    BaseSettingsItem(
                        painter = rememberVectorPainter(Icons.AutoMirrored.Default.Logout),
                        text = stringResource(R.string.setting_logout),
                        descriptionText = stringResource(R.string.setting_logout_desc),
                        onClick = { showLogoutDialog = true }
                    )
                }
            }


            item {
                CategorySettingsItem(
                    text = stringResource(R.string.setting_advanced_category)
                )
            }

            item {
                BaseSettingsItem(
                    painter = rememberVectorPainter(Icons.Outlined.Cloud),
                    text = stringResource(R.string.developer_line_config),
                    descriptionText = stringResource(R.string.setting_line_config_desc),
                    onClick = onToLineConfig
                )
            }


//            item {
//                BaseSettingsItem(
//                    painter = rememberVectorPainter(Icons.Outlined.Extension),
//                    text = "扩展能力",
//                    descriptionText = "提交反馈时记得带上这个！",
//                    onClick = onToSystemExpand
//                )
//            }

        }

        // Dialog注册区域
        PrivacyPolicyDialog(
            showState = showPrivacyPolicy,
            onClickConfirm = {
                showPrivacyPolicy = false
                vm.updatePrivacyPolicyAgreement(Agreed)
            },
            onClickDismiss = {
                showPrivacyPolicy = false
                showPrivacyPolicyRefuseTip = true
                vm.updatePrivacyPolicyAgreement(Refuse)
            }
        )

        /**
         * 拒绝隐私政策后提示弹窗
         */
        PrivacyPolicyRefuseDialog(
            showState = showPrivacyPolicyRefuseTip,
            onClickConfirm = {
                showPrivacyPolicyRefuseTip = false
            }
        )

        // 下载限速选择（B5）：用标准 AlertDialog，别去凑旁边那个自定义组件的参数
        if (showSpeedLimitDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSpeedLimitDialog = false },
                title = { Text(stringResource(R.string.download_speed_limit)) },
                text = {
                    Column {
                        Text(stringResource(R.string.setting_speed_limit_hint))
                        listOf(0, 512, 1024, 2048, 4096).forEach { kbps ->
                            TextButton(onClick = {
                                showSpeedLimitDialog = false
                                backupScope.launch { appSettingsRepository.updateDownloadSpeedLimitKbps(kbps) }
                            }) {
                                Text(
                                    text = when {
                                        kbps <= 0 -> stringResource(R.string.speed_limit_none)
                                        kbps >= 1024 -> "${kbps / 1024} MB/s"
                                        else -> "$kbps KB/s"
                                    } + if (kbps == speedLimitKbps) "　✓" else "",
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSpeedLimitDialog = false }) { Text(stringResource(R.string.cd_close)) }
                },
            )
        }

        // 分片并发数选择
        ASAlertDialog(
            showState = showSegmentConcurrencyDialog,
            title = { Text(stringResource(R.string.setting_segmented_concurrency_title)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.setting_concurrency_hint))
                    listOf(2, 4, 6, 8).forEach { option ->
                        TextButton(onClick = {
                            vm.updateSegmentedDownloadConcurrency(option)
                            showSegmentConcurrencyDialog = false
                        }) {
                            Text(
                                text = if (option == SegmentedDownloadPlan.DEFAULT_CONCURRENCY) {
                                    stringResource(R.string.setting_concurrency_default, option)
                                } else {
                                    "$option"
                                },
                                color = if (option == segmentedConcurrency) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            },
            onDismiss = { showSegmentConcurrencyDialog = false },
            confirmButton = {
                TextButton(onClick = { showSegmentConcurrencyDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )

        // 退出登录对话框
        ASAlertDialog(
            showState = showLogoutDialog,
            title = { Text(stringResource(R.string.setting_logout)) },
            text = {
                Column(
                    Modifier
                        .animateContentSize()
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (showLogoutLoading) {
                        ContainedLoadingIndicator()
                        Text(stringResource(R.string.setting_logging_out))
                    } else {
                        Text(stringResource(R.string.setting_logout_confirm))
                    }
                }
            },
            onDismiss = {
                showLogoutDialog = false
            },
            confirmButton = {
                ASTextButton(onClick = {
                    showLogoutLoading = true
                    coroutineScope.launch(Dispatchers.IO) {
                        vm.logout()
                        showLogoutLoading = false
                        showLogoutDialog = false
                        onLogoutFinish(uiState.currentMid)
                    }
                }) {
                    Text(stringResource(R.string.setting_logout))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }

        )
    }

}

@Composable
fun DownloadPostNotifications() {
    val haptics = LocalHapticFeedback.current

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val context = LocalContext.current

        // 检查权限状态的函数
        val checkPermissionStatus = {
            ContextCompat.checkSelfPermission(
                context,
                permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }

        var hasForegroundServicePermission by remember {
            mutableStateOf(checkPermissionStatus())
        }

        var showRequestForegroundServiceTip by remember { mutableStateOf(false) }

        SwitchSettingsItem(
            imageVector = Icons.Outlined.Notifications,
            text = stringResource(R.string.setting_foreground_notification),
            // ⚠️ 文案要说清"关不掉"这件事（2026-10-01 复审）：POST_NOTIFICATIONS 一旦授予，
            // Android **不允许应用自己撤销**，只能由用户在系统设置里关掉；原来开关看起来能关，
            // 点一下却什么都不发生（checked 直接来自权限状态），用户会以为开关坏了。
            description = stringResource(R.string.setting_foreground_notification_desc),
            checked = hasForegroundServicePermission,
        ) { wanted ->
            haptics.switchHapticFeedback(wanted)
            if (wanted) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    showRequestForegroundServiceTip = true
                }
            } else if (hasForegroundServicePermission) {
                // 用户想关掉：把系统设置的通知页打开（这是唯一能真正关闭的地方）
                Toast.makeText(
                    context,
                    context.getString(R.string.setting_notification_settings_opened),
                    Toast.LENGTH_LONG,
                ).show()
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { e ->
                    Log.w("SettingScreen", "打开通知设置失败", e)
                }
            }
        }

        if (showRequestForegroundServiceTip) {
            DownloadServicePermissionRequestTipDialog(
                onDismiss = {
                    showRequestForegroundServiceTip = false
                },
                onRequest = {
                    showRequestForegroundServiceTip = false
                    hasForegroundServicePermission = true
                },
                onPermissionCheckUpdate = { hasPermission ->
                    hasForegroundServicePermission = hasPermission
                }
            )
        }
    }
}

// 前台服务权限申请对话框
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun DownloadServicePermissionRequestTipDialog(
    onDismiss: () -> Unit,
    onRequest: () -> Unit,
    onPermissionCheckUpdate: (Boolean) -> Unit
) {
    val context = LocalContext.current

    // 检查权限状态的函数
    val checkPermissionStatus = {
        ContextCompat.checkSelfPermission(
            context,
            permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    // 启动设置页面的launcher
    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // 从设置页面返回时重新检查权限状态
        val hasPermission = checkPermissionStatus()
        onPermissionCheckUpdate(hasPermission)
        if (hasPermission) {
            onRequest()
        } else {
            onDismiss()
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { result ->
        if (result) {
            onRequest()
        } else {
            // 跳转APP通知权限设置
            val intent = Intent().apply {
                action = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                data = android.net.Uri.fromParts("package", context.packageName, null)
            }
            settingsLauncher.launch(intent)
        }
    }
    PermissionRequestTipDialog(
        show = true,
        message = stringResource(R.string.setting_notification_request_tip),
        onConfirm = {
            launcher.launch(permission.POST_NOTIFICATIONS)
        },
        onDismiss = onDismiss
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScaffold(
    scrollBehavior: TopAppBarScrollBehavior,
    onToBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
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
                title = { Text(text = stringResource(R.string.cd_settings)) },
                navigationIcon = {
                    AsBackIconButton(onClick = {
                        onToBack.invoke()
                    })
                },
            )
        },
    ) {
        content(it)
    }

}