package com.imcys.bilibilias.ui.download

import androidx.compose.material3.Surface
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import com.imcys.bilibilias.data.download.output.BatchRenameRules
import com.imcys.bilibilias.data.download.output.DuplicateDownloadRules
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider.getUriForFile
import androidx.core.net.toUri
import com.imcys.bilibilias.R
import com.imcys.bilibilias.common.event.sendToastEvent
import com.imcys.bilibilias.common.event.sendToastEventOnBlocking
import com.imcys.bilibilias.data.download.record.DownloadRecordDisplayRules
import com.imcys.bilibilias.database.entity.download.DownloadSegment
import com.imcys.bilibilias.datastore.AppSettings
import com.imcys.bilibilias.ui.download.navigation.DownloadRoute
import com.imcys.bilibilias.ui.widget.ASTextButton
import com.imcys.bilibilias.ui.widget.ASTopAppBar
import com.imcys.bilibilias.ui.widget.AsBackIconButton
import com.imcys.bilibilias.ui.widget.BILIBILIASTopAppBarStyle
import com.imcys.bilibilias.widget.DownloadFinishTaskCard
import com.imcys.bilibilias.widget.DownloadTaskCard
import org.koin.androidx.compose.koinViewModel
import java.io.File

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(route: DownloadRoute, onToBack: () -> Unit) {
    val vm = koinViewModel<DownloadViewModel>()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    // 收集状态
    val downloadListState by vm.downloadListState.collectAsState()
    val completedSegments by vm.completedSegments.collectAsState()
    val currentSortType by vm.downloadSortType.collectAsState()

    // 本地 UI 状态
    var selectIndex by remember { mutableIntStateOf(0) }
    var downloadFinishEditState by remember { mutableStateOf(false) }
    val selectDeleteList = remember { mutableStateListOf<DownloadSegment>() }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // 收集事件
    LaunchedEffect(Unit) {
        vm.uiEvent.collect { event ->
            when (event) {
                is DownloadUiEvent.ShowToast -> {
                    // 文案在 strings.xml：VM 只给 resId + 参数
                    sendToastEvent(context.getString(event.resId, *event.formatArgs.toTypedArray()))
                }

                is DownloadUiEvent.OpenFile -> {
                    openFile(context, event.segment)
                }

                is DownloadUiEvent.RenameFinished -> {
                    // 文案在 strings.xml；这里只负责把**回读到的真实文件名**拼进去
                    //（撞名时 MediaStore 会给它加 `(N)`，那才是磁盘上的真名）
                    val shown = event.finalNames.take(3).joinToString("、")
                    val message = when {
                        event.invalidName -> context.getString(R.string.batch_rename_bad_name)
                        event.finalNames.isEmpty() -> context.getString(R.string.batch_rename_failed)
                        event.failedCount > 0 -> context.getString(
                            R.string.batch_rename_partial,
                            event.finalNames.size,
                            event.failedCount,
                            shown,
                        )

                        else -> context.getString(
                            R.string.batch_rename_done,
                            event.finalNames.size,
                            shown,
                        )
                    }
                    sendToastEventOnBlocking(message)
                }
            }
        }
    }

    // 同步默认标签页
    LaunchedEffect(route.defaultListIndex) {
        selectIndex = route.defaultListIndex
    }

    // 当没有已完成项目时退出编辑模式
    LaunchedEffect(completedSegments) {
        if (completedSegments.isEmpty()) {
            downloadFinishEditState = false
        }
    }

    // 退出编辑模式时清空选择列表
    LaunchedEffect(downloadFinishEditState) {
        selectDeleteList.clear()
    }

    // 「文件已丢失」清单：列表变化时探一次（文件被外部删掉后，记录还在，但要让用户看得见）
    val missingFileIds by vm.missingFileIds.collectAsState()
    var showMoveSelectedDialog by remember { mutableStateOf(false) }
    var moveSubDirName by remember { mutableStateOf("") }
    var showRenameSelectedDialog by remember { mutableStateOf(false) }
    var renameBaseName by remember { mutableStateOf("") }
    val errorSegments by vm.errorSegments.collectAsState()
    LaunchedEffect(completedSegments) {
        vm.refreshMissingFiles(completedSegments)
    }

    // 重复下载文件（同一部视频多份）：进页面扫一次，清理后 VM 里会自己刷新
    val duplicateGroups by vm.duplicateGroups.collectAsState()
    LaunchedEffect(Unit) {
        vm.refreshDuplicateGroups()
    }
    var showDuplicateDialog by remember { mutableStateOf(false) }

    // 「检查重复下载文件」需要 READ_MEDIA_VIDEO 才能看到**不是本 app 创建**的副本
    //（MediaStore 默认只返回本 app 拥有的行；上一版安装留下的副本就属于这一类）。
    var hasVideoPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_VIDEO,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    // 系统删除确认框（删"不是本 app 拥有"的副本时必须走它）
    val pendingDeleteUris by vm.pendingDeleteUris.collectAsState()
    val deleteRequestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        vm.onDeleteRequestFinished()
    }
    LaunchedEffect(pendingDeleteUris) {
        val uris = pendingDeleteUris
        if (uris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val intent = MediaStore.createDeleteRequest(context.contentResolver, uris)
                deleteRequestLauncher.launch(
                    IntentSenderRequest.Builder(intent.intentSender).build(),
                )
            }.onFailure {
                vm.onDeleteRequestFinished()
                sendToastEventOnBlocking(context.getString(R.string.duplicate_delete_request_failed))
            }
        }
    }

    val videoPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasVideoPermission = granted
        if (granted) {
            vm.refreshDuplicateGroups()
        } else {
            sendToastEventOnBlocking(context.getString(R.string.duplicate_no_video_permission))
        }
    }

    // 返回键处理
    BackHandler(enabled = true) {
        if (downloadFinishEditState) {
            downloadFinishEditState = false
        } else {
            onToBack()
        }
    }

    if (showRenameSelectedDialog) {
        RenameSelectedDialog(
            name = renameBaseName,
            onNameChange = { renameBaseName = it },
            onConfirm = {
                vm.renameSelectedTasks(selectDeleteList.toList(), renameBaseName.trim())
                showRenameSelectedDialog = false
                renameBaseName = ""
                // 文件名变了，退出编辑态 —— 免得继续拿着"改了名之后"的旧选中项做批量操作
                downloadFinishEditState = false
            },
            onDismiss = { showRenameSelectedDialog = false },
        )
    }

    if (showMoveSelectedDialog) {
        MoveSelectedDialog(
            name = moveSubDirName,
            onNameChange = { moveSubDirName = it },
            onConfirm = {
                vm.moveSelectedTasks(selectDeleteList.toList(), moveSubDirName.trim())
                showMoveSelectedDialog = false
                moveSubDirName = ""
            },
            onDismiss = { showMoveSelectedDialog = false },
        )
    }

    DownloadScaffold(onToBack = onToBack) { paddingValues ->
        Column(Modifier.padding(paddingValues)) {
            AnimatedContent(downloadFinishEditState, label = "toolbar") { isEditing ->
                if (isEditing) {
                    EditTopTools(
                        completedSegments = completedSegments,
                        selectDeleteList = selectDeleteList,
                        onCancelEdit = { downloadFinishEditState = false },
                        onShowDeleteDialog = { showDeleteDialog = true },
                        onShowMoveDialog = { showMoveSelectedDialog = true },
                        onShowRenameDialog = { showRenameSelectedDialog = true },
                        onShareSelected = {
                            // 批量分享：必须带 FLAG_GRANT_READ_URI_PERMISSION，
                            // 否则接收方读不到 content://media/...（URI 授权是按 Intent 授的）
                            val uris = selectDeleteList
                                .mapNotNull { it.savePath.takeIf { p -> p.startsWith("content://") } }
                                .map { android.net.Uri.parse(it) }
                            if (uris.isEmpty()) {
                                sendToastEventOnBlocking(context.getString(R.string.batch_share_empty))
                            } else {
                                runCatching {
                                    val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                        type = "video/*"
                                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(share, context.getString(R.string.batch_share_chooser_title)))
                                }.onFailure { sendToastEventOnBlocking(context.getString(R.string.batch_share_no_app)) }
                            }
                        },
                    )
                } else {
                    PageChangeTools(
                        selectIndex = selectIndex,
                        haptics = haptics,
                        currentSortType = currentSortType,
                        onUpdateSelectIndex = { selectIndex = it },
                        onUpdateSortType = { vm.updateDownloadSortType(it) }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.padding(bottom = 10.dp, end = 10.dp, start = 10.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                // ⚠️ 放在列表最前面：重复文件是"同一个视频多份 100MB 级副本"，提示要显眼；
                // 没有重复时也保留一个"检查"入口 —— 否则用户永远不知道有这个功能，
                // 而权限（READ_MEDIA_VIDEO）没给时检测本就看不到别人创建的副本。
                item(key = "duplicate_files_card") {
                    DuplicateFilesCard(
                        groupCount = duplicateGroups.size,
                        removableCount = duplicateGroups.sumOf { it.removableNames.size },
                        hasVideoPermission = hasVideoPermission,
                        onClean = { showDuplicateDialog = true },
                        onCheck = {
                            if (hasVideoPermission) {
                                vm.refreshDuplicateGroups()
                                sendToastEventOnBlocking(context.getString(R.string.duplicate_rechecked))
                            } else {
                                videoPermissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO)
                            }
                        },
                    )
                }

                when (selectIndex) {
                    0 -> {
                        // key 用 **segmentId**（数据库主键，天然唯一），不用 platformId：
                        // platformId 是"哪一集"的标识、不是"哪一条记录"的标识，
                        // 一旦列表里出现同 platformId 的两条，Compose 会因为重复 key 直接崩。
                        // 第 5 批已经把"重复 segment"的产因修掉，但 key 本身也该选对。
                        if (errorSegments.isNotEmpty()) {
                            item(key = "retry_all_failed") {
                                RetryAllFailedCard(
                                    count = errorSegments.size,
                                    onRetry = { vm.retryAllFailed() },
                                )
                            }
                        }

                        items(downloadListState, key = { it.downloadSegment.segmentId }) { task ->
                            DownloadTaskCard(
                                modifier = Modifier.animateItem(),
                                task = task,
                                onPause = { vm.pauseDownloadTask(task.downloadSegment.segmentId) },
                                onResume = { vm.resumeDownloadTask(task.downloadSegment.segmentId) },
                                onCancel = { vm.cancelDownloadTask(task.downloadSegment.segmentId) }
                            )
                        }
                    }

                    1 -> {
                        items(completedSegments, key = { it.segmentId }) { segment ->
                            DownloadFinishTaskCard(
                                modifier = Modifier
                                    .animateItem()
                                    .combinedClickable(
                                        onLongClick = {
                                            downloadFinishEditState = !downloadFinishEditState
                                        }
                                    ) {
                                        if (downloadFinishEditState) {
                                            toggleSelection(selectDeleteList, segment)
                                        } else if (
                                            DownloadRecordDisplayRules.hasMediaFile(segment.savePath) &&
                                            segment.segmentId !in missingFileIds
                                        ) {
                                            vm.requestOpenFile(segment)
                                        } else {
                                            // 没有媒体文件（只勾了封面/弹幕/字幕）：打开必然失败。
                                            // 别再报"文件不存在，可能已被删除" —— 那会让人以为文件丢了。
                                            // 分情况说实话：纯附加内容 / 文件被外部删掉 / 其它打不开原因
                                            sendToastEventOnBlocking(
                                                // 文案在 strings.xml（规则模块只返回"原因码"）
                                                context.getString(
                                                    when (
                                                        DownloadRecordDisplayRules.unopenableReason(
                                                            savePath = segment.savePath,
                                                            fileMissing = segment.segmentId in missingFileIds,
                                                        )
                                                    ) {
                                                        DownloadRecordDisplayRules.UnopenableReason.EXTRAS_ONLY ->
                                                            R.string.download_record_unopenable_extras_only

                                                        DownloadRecordDisplayRules.UnopenableReason.MISSING_FILE ->
                                                            R.string.download_record_unopenable_missing

                                                        DownloadRecordDisplayRules.UnopenableReason.UNKNOWN ->
                                                            R.string.download_record_unopenable_unknown
                                                    }
                                                ),
                                            )
                                        }
                                    },
                                downloadSegment = segment,
                                fileMissing = segment.segmentId in missingFileIds,
                                downloadFinishEditState = downloadFinishEditState,
                                selectDeleteList = selectDeleteList,
                                onDeleteTaskAndFile = { vm.deleteDownloadSegment(segment) },
                                onSelect = { toggleSelection(selectDeleteList, segment) }
                            )
                        }
                    }
                }
            }
        }

        // 重复文件清理对话框
        if (showDuplicateDialog) {
            DuplicateFilesCleanupDialog(
                groups = duplicateGroups,
                onConfirm = { names ->
                    vm.cleanDuplicateFiles(names)
                    showDuplicateDialog = false
                },
                onDismiss = { showDuplicateDialog = false },
            )
        }

        // 删除确认对话框
        if (showDeleteDialog) {
            DeleteConfirmDialog(
                onConfirm = {
                    vm.deleteSelectedTasks(selectDeleteList.toList())
                    showDeleteDialog = false
                    downloadFinishEditState = false
                },
                onDismiss = { showDeleteDialog = false }
            )
        }
    }
}

/**
 * 「发现重复下载文件」提示卡。
 *
 * 2026-10-01 真机复现：这台 ROM 的 MediaStore 会把撞名的新文件改成 `xxx (1)/(2).mp4`，
 * 而 app 对这些 MediaProvider 拥有的文件删不动、按行删又常命中 0 —— 自动删除不可靠，
 * 于是改成"提示 + 用户确认"：默认保留正式名那份，只删副本。
 */
@Composable
private fun DuplicateFilesCard(
    groupCount: Int,
    removableCount: Int,
    hasVideoPermission: Boolean,
    onClean: () -> Unit,
    onCheck: () -> Unit,
) {
    if (groupCount == 0) {
        // 没有发现重复：给一个常驻入口（并说明为什么可能需要先授权）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            TextButton(onClick = onCheck) {
                Text(
                    if (hasVideoPermission) stringResource(R.string.duplicate_check)
                    else stringResource(R.string.duplicate_grant_and_check),
                )
            }
            Text(
                stringResource(R.string.duplicate_check_hint),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        return
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.duplicate_files_found, groupCount),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.duplicate_files_removable, removableCount),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClean) {
                Text(stringResource(R.string.duplicate_files_clean))
            }
        }
    }
}

/**
 * 重复文件清理对话框：列出每个"可删副本"（默认勾选），正式名那份只展示不可勾。
 * 用户点"删除选中"才会真的删。
 */
@Composable
private fun DuplicateFilesCleanupDialog(
    groups: List<DuplicateDownloadRules.DuplicateGroup>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // 默认全选可删项（用户可逐个取消）
    val selected = remember(groups) {
        mutableStateListOf<String>().apply {
            groups.forEach { addAll(it.removableNames) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.duplicate_cleanup_title)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { group ->
                    item(key = "keep_${group.keepName}") {
                        Text(
                            stringResource(R.string.duplicate_keep_prefix) + group.keepName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    items(group.removableNames, key = { "rm_$it" }) { name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = name in selected,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        if (name !in selected) selected.add(name)
                                    } else {
                                        selected.remove(name)
                                    }
                                },
                            )
                            Text(name, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty(),
            ) {
                Text(stringResource(R.string.duplicate_delete_selected, selected.size))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

// region 辅助函数
private fun toggleSelection(list: SnapshotStateList<DownloadSegment>, segment: DownloadSegment) {
    if (segment in list) {
        list.remove(segment)
    } else {
        list.add(segment)
    }
}

private fun openFile(context: Context, segment: DownloadSegment) {
    val savePath = segment.savePath
    val (uri, type) = if (savePath.startsWith("content://")) {
        val fileUri = savePath.toUri()
        fileUri to (context.contentResolver.getType(fileUri) ?: "")
    } else {
        val file = File(savePath)
        val fileUri = runCatching {
            getUriForFile(context, "${context.applicationContext.packageName}.provider", file)
        }.getOrNull()

        if (fileUri == null) {
            sendToastEventOnBlocking(context.getString(R.string.open_file_no_app))
            return
        }
        fileUri to (context.contentResolver.getType(fileUri) ?: "")
    }

    val intent = Intent(Intent.ACTION_VIEW).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setDataAndType(uri, type)
    }

    runCatching {
        context.startActivity(intent)
    }.onFailure {
        sendToastEventOnBlocking(context.getString(R.string.open_file_no_app))
    }
}
// endregion

// region 子组件
@Composable
private fun DeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.download_batch_delete_title)) },
        text = { Text(stringResource(R.string.download_delete_confirm_message)) },
        confirmButton = {
            ASTextButton(onClick = onConfirm) {
                Text(stringResource(R.string.common_delete))
            }
        },
        dismissButton = {
            ASTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PageChangeTools(
    selectIndex: Int,
    haptics: HapticFeedback,
    currentSortType: AppSettings.DownloadSortType,
    onUpdateSelectIndex: (Int) -> Unit,
    onUpdateSortType: (AppSettings.DownloadSortType) -> Unit
) {
    var showSortMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToggleButton(
            checked = selectIndex == 0,
            onCheckedChange = {
                if (it) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onUpdateSelectIndex(0)
                }
            },
        ) {
            Text(stringResource(R.string.status_downloading_title))
        }

        Spacer(Modifier.width(10.dp))

        ToggleButton(
            checked = selectIndex == 1,
            onCheckedChange = {
                if (it) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onUpdateSelectIndex(1)
                }
            },
        ) {
            Text(stringResource(R.string.status_completed_title))
        }

        // 排序选择器（仅在已完成标签页显示）
        if (selectIndex == 1) {
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick = { showSortMenu = true }) {
                    Text(getSortTypeDisplayName(currentSortType))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false }
                ) {
                    AppSettings.DownloadSortType.entries
                        .filter { it != AppSettings.DownloadSortType.UNRECOGNIZED }
                        .forEach { sortType ->
                            DropdownMenuItem(
                                text = { Text(getSortTypeDisplayName(sortType)) },
                                onClick = {
                                    onUpdateSortType(sortType)
                                    showSortMenu = false
                                }
                            )
                        }
                }
            }
        }
    }
}

@Composable
private fun getSortTypeDisplayName(sortType: AppSettings.DownloadSortType): String {
    return when (sortType) {
        AppSettings.DownloadSortType.DownloadSort_TimeDesc -> stringResource(R.string.sort_time_desc)
        AppSettings.DownloadSortType.DownloadSort_TimeAsc -> stringResource(R.string.sort_time_asc)
        AppSettings.DownloadSortType.DownloadSort_TitleAsc -> stringResource(R.string.sort_title_asc)
        AppSettings.DownloadSortType.DownloadSort_TitleDesc -> stringResource(R.string.sort_title_desc)
        AppSettings.DownloadSortType.DownloadSort_SizeDesc -> stringResource(R.string.sort_size_desc)
        AppSettings.DownloadSortType.DownloadSort_SizeAsc -> stringResource(R.string.sort_size_asc)
        else -> stringResource(R.string.sort_time_desc)
    }
}

@Composable
private fun EditTopTools(
    completedSegments: List<DownloadSegment>,
    selectDeleteList: SnapshotStateList<DownloadSegment>,
    onCancelEdit: () -> Unit,
    onShowDeleteDialog: () -> Unit,
    onShowMoveDialog: () -> Unit,
    onShowRenameDialog: () -> Unit,
    onShareSelected: () -> Unit,
) {
    Row(
        Modifier
            .padding(10.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            shape = CardDefaults.shape,
            onClick = {
                // ⚠️ 按钮文案是「取消全选 / Deselect All」（`download_deselect_all`），
                // 而原实现是**逐个取反 = 反选** —— 英/日/韩等 9 种语言下，用户想清空选择，
                // 结果未选中的全被选中，接着点删除就会**删掉本不想删的任务与文件**（2026-09-15 复审 M3）。
                // 行为改成与文案一致：清空选择（删得更少，是安全的方向）。
                selectDeleteList.clear()
            },
            border = CardDefaults.outlinedCardBorder()
        ) {
            Text(stringResource(R.string.download_deselect_all))
        }

        OutlinedButton(
            shape = CardDefaults.shape,
            onClick = {
                selectDeleteList.clear()
                selectDeleteList.addAll(completedSegments)
            },
        ) {
            Text(stringResource(R.string.download_select_all))
        }

        OutlinedButton(
            shape = CardDefaults.shape,
            onClick = onShowRenameDialog,
        ) {
            Text(stringResource(R.string.batch_rename))
        }

        OutlinedButton(
            shape = CardDefaults.shape,
            onClick = onShowMoveDialog,
        ) {
            Text(stringResource(R.string.batch_move))
        }

        OutlinedButton(
            shape = CardDefaults.shape,
            onClick = onShareSelected,
        ) {
            Text(stringResource(R.string.batch_share))
        }

        Button(
            shape = CardDefaults.shape,
            onClick = onCancelEdit,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            )
        ) {
            Text(stringResource(R.string.common_cancel))
        }

        Button(
            enabled = selectDeleteList.isNotEmpty(),
            shape = CardDefaults.shape,
            onClick = onShowDeleteDialog,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Text(stringResource(R.string.common_delete))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScaffold(
    onToBack: () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            ASTopAppBar(
                style = BILIBILIASTopAppBarStyle.Small,
                title = { Text(text = stringResource(R.string.download_manage_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                navigationIcon = {
                    AsBackIconButton(onClick = onToBack)
                },
            )
        },
    ) { paddingValues ->
        content(paddingValues)
    }
}
// endregion

/** 失败任务提示卡（A4）：一键重试全部失败项 */
@Composable
private fun RetryAllFailedCard(
    count: Int,
    onRetry: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = CardDefaults.shape,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.retry_failed_count, count), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.retry_failed_hint),
                    fontSize = 11.sp,
                )
            }
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry_all_failed)) }
        }
    }
}


/**
 * 批量移动的目标子目录输入框（B2）。
 *
 * 只输入**子目录名**（如 `番剧`），完整路径是 `Download/BDT/<名字>/` ——
 * 不允许 `..`：那等于让 UI 能写到下载目录外面去（FileOutputManager 里也兜了一层）。
 */
@Composable
private fun MoveSelectedDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_move)) },
        text = {
            Column {
                Text(stringResource(R.string.batch_move_hint), fontSize = 12.sp)
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.batch_move_label)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = name.isNotBlank() && !name.contains(".."),
            ) { Text(stringResource(R.string.batch_move_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/**
 * 批量重命名对话框（B2）。
 *
 * 只输入**新基名**（不含扩展名）：扩展名沿用每个文件自己的，避免把 `视频.mp4` 改成
 * `视频.txt` 这种"文件还在、却打不开了"的结果。
 *
 * ⚠️ 这台 ROM 上 MediaProvider 撞名会自己加 `(N)`，所以：
 * - 多选的后缀由 [BatchRenameRules] 主动分配（`新名` / `新名 (1)` / `新名 (2)`…）；
 * - 真正的结果由 `FileOutputManager.renameDownloadFile` **回读**后告诉用户。
 */
@Composable
private fun RenameSelectedDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_rename)) },
        text = {
            Column {
                Text(stringResource(R.string.batch_rename_hint), fontSize = 12.sp)
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.batch_rename_label)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                // 判据与 VM / FileOutputManager 用的是同一条纯规则，三处一致
                enabled = BatchRenameRules.isUsableBaseName(name),
            ) { Text(stringResource(R.string.batch_rename_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
