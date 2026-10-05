package com.imcys.bilibilias.ui.setting.storage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.imcys.bilibilias.R
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import com.imcys.bilibilias.download.FileOutputManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import com.imcys.bilibilias.common.event.sendToastEventOnBlocking
import com.imcys.bilibilias.common.utils.StorageInfoData
import com.imcys.bilibilias.common.utils.StorageUtil
import com.imcys.bilibilias.ui.utils.rememberWidthSizeClass
import com.imcys.bilibilias.ui.widget.ASIconButton
import com.imcys.bilibilias.ui.widget.ASTopAppBar
import com.imcys.bilibilias.ui.widget.AsBackIconButton
import com.imcys.bilibilias.ui.widget.BILIBILIASTopAppBarStyle
import com.imcys.bilibilias.ui.widget.tip.ASWarringTip
import com.imcys.bilibilias.widget.ASCommonLoadingScreen
import com.imcys.bilibilias.widget.AnimatedStorageRing
import com.imcys.bilibilias.widget.CommonError
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import java.io.File

@Serializable
data object StorageManagementRoute : NavKey

@Composable
fun StorageManagementScreen(
    route: StorageManagementRoute,
    onToBack: () -> Unit,
    onToDownloadList: () -> Unit,
) {
    StorageManagementScaffold(
        onToBack = onToBack,
    ) {
        StorageManagementContent(Modifier.padding(it), onToDownloadList)
    }
}

@Composable
fun StorageManagementContent(
    modifier: Modifier = Modifier,
    onToDownloadList: () -> Unit
) {
    val context = LocalContext.current
    val vm = koinViewModel<StorageManagementViewModel>()
    val uiState by vm.uiState.collectAsState()

    LaunchedEffect(Unit) {
        vm.loadStorageInfo(context)
    }


    when (val state = uiState) {
        // ⚠️ 失败分支原来是个空壳 `is Error -> {}`：整屏只剩顶栏，既没文案也没重试
        //（2026-09-15 复审 L10）。这里给出错误卡片 + 重试。
        is StorageManagementViewModel.StorageManagementUIState.Error -> {
            Box(modifier = modifier.padding(16.dp)) {
                CommonError(errorMsg = state.errorMsg, onRetry = { vm.loadStorageInfo(context) })
            }
        }
        StorageManagementViewModel.StorageManagementUIState.Loading -> {
            ASCommonLoadingScreen()
        }

        is StorageManagementViewModel.StorageManagementUIState.Success -> {
            StorageManagementSuccessScreen(
                modifier,
                state.storageInfoData,
                state.hasDownloadSAFPermission,
                onCleanCache = {
                    vm.cleanAppCache(context)
                },
                onToDownloadList = onToDownloadList,
                onSaveDownloadUri = {
                    vm.saveDownloadUri(context, it)
                }
            )
        }
    }
}

@Composable
fun StorageManagementSuccessScreen(
    modifier: Modifier = Modifier,
    data: StorageInfoData,
    hasDownloadSAFPermission: Boolean,
    onCleanCache: () -> Unit,
    onToDownloadList: () -> Unit,
    onSaveDownloadUri: (uri: Uri) -> Unit,
) {
    // 诊断日志导出：这台 ROM 会过滤 app 自己的 logcat，出问题时需要能把完整轨迹带走
    val fileOutputManager: FileOutputManager = koinInject()
    val diagnosticScope = rememberCoroutineScope()

    // 诊断日志 / 下载目录文件（2026-10-02 A 组）
    var showLocalFilesDialog by remember { mutableStateOf(false) }
    val localFiles = remember { mutableStateListOf<com.imcys.bilibilias.download.FileOutputManager.DownloadFileEntry>() }
    // 已有下载记录的文件（用来标出"没有记录的孤儿文件"）
    val downloadTaskRepository: com.imcys.bilibilias.data.repository.DownloadTaskRepository = koinInject()
    val knownFileUris by remember {
        downloadTaskRepository.getSegmentAll().map { segments ->
            segments.mapNotNull { it.savePath.takeIf { savePath -> savePath.isNotBlank() } }.toSet()
        }
    }.collectAsState(initial = emptySet<String>())

    // 「所有文件访问」状态：进页面 + 从系统设置返回（ON_RESUME）都刷新一次
    var hasAllFilesAccess by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAllFilesAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val windowWidthSizeClass = rememberWidthSizeClass()
    val context = LocalContext.current
    val downloadLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                // 先保存 URI，再尝试持久化授权。
                // 原实现把 onSaveDownloadUri 放在 takePersistableUriPermission 之后，
                // 且整个包在 try/catch 里静默吞掉异常 —— 一旦持久化授权抛异常，
                // URI 根本不会被保存，界面就永远停在「应用存储权限未完全获取」。
                onSaveDownloadUri(uri)
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }.onFailure {
                    Log.e("ASStorage", "持久化存储授权失败（重启后可能需重新授权）", it)
                }
            }
        }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = 10.dp)
            .padding(horizontal = 10.dp)
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            AnimatedStorageRing(
                data, modifier = Modifier
                    .fillMaxWidth(
                        if (windowWidthSizeClass == WindowWidthSizeClass.Compact) 0.6f else 0.4f
                    )
                    .aspectRatio(1f)
            )

        }

        if (!hasDownloadSAFPermission) {
            ASWarringTip {
                Row {
                    Text(
                        stringResource(R.string.storage_permission_incomplete),
                        Modifier.weight(1f)
                    )
                    ASIconButton(onClick = {
                        val downloadsDir =
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        val targetDir = File(downloadsDir, com.imcys.bilibilias.download.DownloadDir.NAME)
                        if (!targetDir.exists()) {
                            runCatching { targetDir.mkdirs() }
                        }
                        val relativePath =
                            targetDir.absolutePath.substringAfter("/storage/emulated/0/")
                        val downloadUri = DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents",
                            "primary:$relativePath"
                        )
                        downloadLauncher.launch(downloadUri)
                    }) {
                        Icon(Icons.Outlined.NorthEast, contentDescription = stringResource(R.string.cd_grant_permission))
                    }
                }
            }
        }


        StorageContent(
            title = stringResource(R.string.download_dir_files),
            dataNumStr = "",
            description = stringResource(R.string.download_dir_files_desc),
            buttonTextRes = R.string.view_button,
            buttonColor = MaterialTheme.colorScheme.primary,
            onClick = {
                diagnosticScope.launch {
                    val files = withContext(Dispatchers.IO) { fileOutputManager.listDownloadFiles() }
                    localFiles.clear()
                    localFiles.addAll(files)
                    showLocalFilesDialog = true
                }
            },
        )


        if (showLocalFilesDialog) {
            LocalFilesDialog(
                files = localFiles,
                knownUris = knownFileUris,
                onOpen = { file ->
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(file.uriString), "video/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }.onFailure {
                        Toast.makeText(context, context.getString(R.string.storage_no_app_to_open), Toast.LENGTH_SHORT).show()
                    }
                },
                onDelete = { file ->
                    diagnosticScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.delete(Uri.parse(file.uriString), null, null) > 0
                            }.getOrElse { false }
                        }
                        if (ok) {
                            localFiles.remove(file)
                            Toast.makeText(context, context.getString(R.string.storage_deleted), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.storage_delete_failed_not_owned),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                onDismiss = { showLocalFilesDialog = false },
            )
        }

        StorageContent(
            title = stringResource(R.string.storage_av_files),
            dataNumStr = StorageUtil.formatSize(data.downloadBytes),
            description = stringResource(R.string.storage_av_files_desc),
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    val targetDir = com.imcys.bilibilias.download.DownloadDir.dir()
                        .apply {
                        if (!exists()) mkdirs()
                    }
                    val relativePath =
                        targetDir.absolutePath.substringAfter("/storage/emulated/0/")
                    val downloadUri = DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:$relativePath"
                    )
                    setDataAndType(
                        downloadUri,
                        "vnd.android.document/directory"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    sendToastEventOnBlocking(context.getString(R.string.storage_no_file_manager))
                }
            },
        )


        // 「所有文件访问」：授权后交付阶段可以直接改名/删除，彻底绕开这台 ROM 上
        // MediaStore 的 `(N)` 改名与 `_data` 不一致问题（2026-10-01 真机复现）。
        StorageContent(
            title = stringResource(R.string.storage_all_files_access),
            dataNumStr = if (hasAllFilesAccess) stringResource(R.string.storage_granted) else stringResource(R.string.storage_not_granted),
            description = stringResource(R.string.storage_all_files_access_desc),
            buttonTextRes = R.string.storage_go_settings,
            buttonColor = MaterialTheme.colorScheme.primary,
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // ⚠️ 必须用 setData(...)：`Intent.apply { data = ... }` 里的 `data`
                    // 会被解析成外层那个 `data: StorageInfoData` 参数（编译报 'val' cannot be reassigned）
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        setData(Uri.fromParts("package", context.packageName, null))
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }.onFailure {
                        // 个别 ROM 没有这个页面，退到总列表
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                } else {
                    sendToastEventOnBlocking(context.getString(R.string.storage_below_android11))
                }
            },
        )

        StorageContent(
            title = stringResource(R.string.storage_temp_files),
            dataNumStr = "${StorageUtil.formatSize(data.cacheTotalBytes)}",
            description = stringResource(R.string.storage_temp_files_desc),
            buttonTextRes = R.string.storage_clean,
            buttonColor = MaterialTheme.colorScheme.primary,
            onClick = onCleanCache
        )

        StorageContent(
            title = stringResource(R.string.storage_core_files),
            dataNumStr = "${StorageUtil.formatSize(data.appBytes - data.cacheTotalBytes)}",
            description = stringResource(R.string.storage_core_files_desc),
            showButton = false,
            buttonColor = MaterialTheme.colorScheme.primary,
        )


    }
}

@Preview
@Composable
fun StorageContent(
    title: String = "",
    dataNumStr: String = "",
    description: String = "",
    showButton: Boolean = true,
    @StringRes buttonTextRes: Int = R.string.storage_manage,
    buttonColor: Color = MaterialTheme.colorScheme.surface,
    onClick: () -> Unit = {}
) {
    Surface(
        Modifier
            .fillMaxWidth(),
        shape = CardDefaults.shape
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier.weight(1f)
            ) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    dataNumStr,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    description,
                    fontSize = 11.sp,
                )
            }
            if (showButton) {
                Surface(
                    onClick = onClick,
                    shape = RoundedCornerShape(6.dp),
                    border = if (buttonColor == MaterialTheme.colorScheme.surface)
                        CardDefaults.outlinedCardBorder() else null,
                    modifier = Modifier.padding(0.dp),
                    color = buttonColor
                ) {
                    Text(
                        stringResource(buttonTextRes),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                }
            }


        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageManagementScaffold(
    onToBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            Column {
                ASTopAppBar(
                    style = BILIBILIASTopAppBarStyle.Small,
                    title = {
                        Text(text = stringResource(R.string.setting_storage_management))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    navigationIcon = {
                        AsBackIconButton(onClick = {
                            onToBack.invoke()
                        })
                    },
                    alwaysDisplay = false
                )
            }
        },
    ) {
        content.invoke(it)
    }


}


/**
 * 诊断日志对话框（A3）：看轨迹、导出、清空。
 *
 * 为什么要应用内能看：这台 ROM 会过滤 logcat，轨迹文件是唯一取证渠道；
 * 以前只能"导出到 Download 再用别的 App 打开"，多一步就常常没人看。
 */

/**
 * 下载目录文件对话框（A2）：列出下载目录里的文件，「孤儿」= 没有任何下载记录指向它。
 *
 * 直接解决两个真实现象：重装后记录没了、文件还在；改名后旧文件成了没人管的孤儿。
 */
@Composable
private fun LocalFilesDialog(
    files: List<com.imcys.bilibilias.download.FileOutputManager.DownloadFileEntry>,
    knownUris: Set<String>,
    onOpen: (com.imcys.bilibilias.download.FileOutputManager.DownloadFileEntry) -> Unit,
    onDelete: (com.imcys.bilibilias.download.FileOutputManager.DownloadFileEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = CardDefaults.shape) {
            Column(Modifier.padding(12.dp)) {
                val orphanCount = files.count { it.uriString !in knownUris }
                Text(stringResource(R.string.storage_download_files_title, files.size, orphanCount),
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (files.isEmpty()) {
                    Text(stringResource(R.string.storage_dir_empty))
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(files) { file ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(file.displayName, fontSize = 12.sp, maxLines = 2)
                                    Text(
                                        text = StorageUtil.formatSize(file.sizeBytes) +
                                            if (file.uriString in knownUris) {
                                                " " + stringResource(R.string.storage_has_record)
                                            } else {
                                                " " + stringResource(R.string.storage_orphan)
                                            },
                                        fontSize = 10.sp,
                                    )
                                }
                                TextButton(onClick = { onOpen(file) }) { Text(stringResource(R.string.storage_open)) }
                                TextButton(onClick = { onDelete(file) }) { Text(stringResource(R.string.common_delete)) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cd_close)) }
                }
            }
        }
    }
}
