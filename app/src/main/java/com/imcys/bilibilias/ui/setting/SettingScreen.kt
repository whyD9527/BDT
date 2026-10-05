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
import androidx.compose.material.icons.outlined.BugReport
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.imcys.bilibilias.ui.widget.CollapsibleCategorySettingsItem
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
        onToLayoutTypeset = {})
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingScreen(
    onToLayoutTypeset: () -> Unit,
    onToBack: () -> Unit,
    onToAbout: () -> Unit = {},
    onToFeedback: () -> Unit = {},
    onToSystemExpand: () -> Unit = {},
    onToStorageManagement: () -> Unit = {},
    onToNamingConvention: () -> Unit = {},
    onToLineConfig: () -> Unit = {},
    onToLogin: () -> Unit = {},
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
    // #3：下载速度（限速 + 分片并发）合成一个弹窗；备份与恢复合成一个弹窗
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    // #3 四组折叠的展开状态。用 rememberSaveable：后台化回来不会全部塌回去。
    // 下载组默认展开（最常用），其余三组折叠 —— 首屏从 ~20 行降到 ~7 行。
    var downloadGroupExpanded by rememberSaveable { mutableStateOf(true) }
    var appearanceGroupExpanded by rememberSaveable { mutableStateOf(false) }
    var dataGroupExpanded by rememberSaveable { mutableStateOf(false) }
    var aboutGroupExpanded by rememberSaveable { mutableStateOf(false) }

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

    // 「下载速度」一行的摘要：限速 +（开了分片下载时）并发数
    val speedLimitText =
        if (speedLimitKbps <= 0) stringResource(R.string.speed_limit_none) else "$speedLimitKbps KB/s"
    val speedSummary = stringResource(R.string.setting_speed_limit_short, speedLimitText) +
        if (segmentedEnabled) {
            " · " + stringResource(R.string.setting_concurrency_short, segmentedConcurrency)
        } else {
            ""
        }

    // 「备份与恢复」的导出动作（原来挂在"备份设置"那一行上，现在弹窗里复用同一份实现）
    val exportBackupSettings: () -> Unit = {
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


    SettingScaffold(scrollBehavior, onToBack) {

        LazyColumn(
            modifier = Modifier
                .padding(it)
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {

            // ==================== 分组一：下载 ====================
            // #3（2026-10-05）：原来 ~20 行平铺（缓存配置/缓存目录/存储管理 三个入口说的是一件事），
            // 现在四组折叠：下载组默认展开（最常用），其余三组折叠，首屏 ~7 行。
            item(key = "group_download") {
                CollapsibleCategorySettingsItem(
                    text = stringResource(R.string.setting_group_download),
                    description = stringResource(R.string.setting_group_download_desc),
                    expanded = downloadGroupExpanded,
                    onToggle = { downloadGroupExpanded = !downloadGroupExpanded },
                )
            }
            if (downloadGroupExpanded) {
                // 「下载与缓存」＝ 合并原来的 缓存配置 + 缓存目录 + 存储管理：
                // 三行本来就是一件事（都指向 Download/BDT），描述直接显示真实目录
                item(key = "download_cache") {
                    BaseSettingsItem(
                        painter = painterResource(R.drawable.ic_save_24px),
                        text = stringResource(R.string.setting_download_cache),
                        descriptionText = com.imcys.bilibilias.download.DownloadDir.RELATIVE_PATH,
                        onClick = onToStorageManagement
                    )
                }

                item(key = "naming_convention") {
                    BaseSettingsItem(
                        painter = rememberVectorPainter(Icons.Outlined.Edit),
                        text = stringResource(R.string.setting_naming_convention),
                        descriptionText = stringResource(R.string.setting_naming_rule_desc),
                        onClick = onToNamingConvention
                    )
                }

                item(key = "line_config") {
                    BaseSettingsItem(
                        painter = rememberVectorPainter(Icons.Outlined.Cloud),
                        text = stringResource(R.string.developer_line_config),
                        descriptionText = stringResource(R.string.setting_line_config_desc),
                        onClick = onToLineConfig
                    )
                }

                item(key = "parse_platform") {
                    BaseSettingsItem(
                        painter = rememberVectorPainter(Icons.Outlined.Hub),
                        text = stringResource(R.string.setting_parse_platform),
                        descriptionText = stringResource(R.string.setting_parse_platform_desc),
                        onClick = { onToPage(ParsePlatformRoute) }
                    )
                }

                // 「下载速度」＝ 合并 限速 + 分片并发：两个数都是"下载多快"，合成一个弹窗选择
                item(key = "download_speed") {
                    BaseSettingsItem(
                        painter = rememberVectorPainter(Icons.Outlined.Speed),
                        text = stringResource(R.string.setting_download_speed),
                        descriptionText = speedSummary,
                        onClick = { showSpeedDialog = true }
                    )
                }

                item(key = "segmented_download") {
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

                item(key = "wifi_only_download") {
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

                item(key = "skip_downloaded") {
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
            }

            // ==================== 分组二：外观 ====================
            item(key = "group_appearance") {
                CollapsibleCategorySettingsItem(
                    text = stringResource(R.string.setting_group_appearance),
                    description = stringResource(R.string.setting_group_appearance_desc),
                    expanded = appearanceGroupExpanded,
                    onToggle = { appearanceGroupExpanded = !appearanceGroupExpanded },
                )
            }
            if (appearanceGroupExpanded) {
                item(key = "dynamic_color") {
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

                item(key = "home_layout") {
                    BaseSettingsItem(
                        painter = rememberVectorPainter(Icons.AutoMirrored.Outlined.ListAlt),
                        text = stringResource(R.string.setting_home_layout),
                        description = {},
                        onClick = onToLayoutTypeset
                    )
                }
            }

            // ==================== 分组三：数据 ====================
            item(key = "group_data") {
                CollapsibleCategorySettingsItem(
                    text = stringResource(R.string.setting_group_data),
                    description = stringResource(R.string.setting_group_data_desc),
                    expanded = dataGroupExpanded,
                    onToggle = { dataGroupExpanded = !dataGroupExpanded },
                )
            }
            if (dataGroupExpanded) {
                // 「备份与恢复」合一：导出/导入都是一次性动作，一个弹窗里放两个按钮
                item(key = "backup_and_restore") {
                    BaseSettingsItem(
                        painter = rememberVectorPainter(Icons.Outlined.Save),
                        text = stringResource(R.string.setting_backup_and_restore),
                        descriptionText = stringResource(R.string.setting_backup_and_restore_desc),
                        onClick = { showBackupDialog = true }
                    )
                }

                item(key = "feedback") {
                    // 唯一的反馈/诊断入口：状态自检 + 设备与版本信息 + 诊断日志 + 一键导出反馈包
                    // （原先散在三处：存储管理导出日志、版本信息复制信息、工具列表的 BugReport）
                    BaseSettingsItem(
                        painter = rememberVectorPainter(Icons.Outlined.BugReport),
                        text = stringResource(R.string.feedback_title),
                        descriptionText = stringResource(R.string.feedback_subtitle),
                        onClick = onToFeedback
                    )
                }

                item(key = "clipboard_auto") {
                    SwitchSettingsItem(
                        imageVector = Icons.Default.ContentPaste,
                        text = stringResource(R.string.setting_auto_parse),
                        description = stringResource(R.string.setting_auto_parse_desc),
                        checked = appSettings.enabledClipboardAutoHandling,
                    ) { check ->
                        vm.updateClipboardAutoHandling(check)
                    }
                }

                item(key = "download_notification") {
                    DownloadPostNotifications()
                }
            }

            // ==================== 分组四：关于 ====================
            item(key = "group_about") {
                CollapsibleCategorySettingsItem(
                    text = stringResource(R.string.setting_group_about),
                    description = stringResource(R.string.setting_group_about_desc),
                    expanded = aboutGroupExpanded,
                    onToggle = { aboutGroupExpanded = !aboutGroupExpanded },
                )
            }
            if (aboutGroupExpanded) {
                // 版本 + 检查更新都在「关于」页里（A-②）
                item(key = "about") {
                    BaseSettingsItem(
                        painter = rememberVectorPainter(Icons.Outlined.Group),
                        text = stringResource(R.string.setting_about_item),
                        descriptionText = stringResource(R.string.setting_about_desc),
                        onClick = onToAbout
                    )
                }

                item(key = "privacy_policy") {
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
                    item(key = "logout") {
                        BaseSettingsItem(
                            painter = rememberVectorPainter(Icons.AutoMirrored.Default.Logout),
                            text = stringResource(R.string.setting_logout),
                            descriptionText = stringResource(R.string.setting_logout_desc),
                            onClick = { showLogoutDialog = true }
                        )
                    }
                } else {
                    // #5：没登录时给一条去登录的路（设置里的「账户」行与合并后的登录页对齐）
                    item(key = "login") {
                        BaseSettingsItem(
                            painter = rememberVectorPainter(Icons.AutoMirrored.Outlined.AirplaneTicket),
                            text = stringResource(R.string.login_title),
                            descriptionText = stringResource(R.string.setting_login_desc),
                            onClick = onToLogin
                        )
                    }
                }

                item(key = "github_repo") {
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
            }
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

        // 下载速度（#3 合并：限速 + 分片并发，原来各占一行、各开一个弹窗）
        if (showSpeedDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSpeedDialog = false },
                title = { Text(stringResource(R.string.setting_download_speed)) },
                text = {
                    Column {
                        Text(stringResource(R.string.setting_speed_limit_hint))
                        listOf(0, 512, 1024, 2048, 4096).forEach { kbps ->
                            TextButton(onClick = {
                                showSpeedDialog = false
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
                        // 并发数只在开了分片下载时才有意义（与原来那个单独弹窗的条件一致）
                        if (segmentedEnabled) {
                            Text(stringResource(R.string.setting_segmented_concurrency_title))
                            Text(
                                stringResource(R.string.setting_concurrency_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            listOf(2, 4, 6, 8).forEach { option ->
                                TextButton(onClick = {
                                    vm.updateSegmentedDownloadConcurrency(option)
                                    showSpeedDialog = false
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
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSpeedDialog = false }) { Text(stringResource(R.string.cd_close)) }
                },
            )
        }

        // 备份与恢复（#3 合并：一行入口 → 弹窗里两个动作）
        if (showBackupDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showBackupDialog = false },
                title = { Text(stringResource(R.string.setting_backup_and_restore)) },
                text = {
                    Column {
                        Text(stringResource(R.string.setting_backup_and_restore_desc))
                        TextButton(onClick = {
                            showBackupDialog = false
                            exportBackupSettings()
                        }) {
                            Text(stringResource(R.string.backup_settings))
                        }
                        Text(
                            stringResource(R.string.backup_settings_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        TextButton(onClick = {
                            showBackupDialog = false
                            restoreSettingsLauncher.launch(arrayOf("application/json", "*/*"))
                        }) {
                            Text(stringResource(R.string.restore_settings))
                        }
                        Text(
                            stringResource(R.string.restore_settings_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showBackupDialog = false }) { Text(stringResource(R.string.cd_close)) }
                },
            )
        }

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