package com.imcys.bilibilias.ui.download

import android.annotation.SuppressLint
import android.content.ContentResolver
import androidx.annotation.StringRes
import com.imcys.bilibilias.R
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imcys.bilibilias.data.repository.AppSettingsRepository
import com.imcys.bilibilias.data.repository.DownloadTaskRepository
import com.imcys.bilibilias.database.entity.download.DownloadSegment
import com.imcys.bilibilias.database.entity.download.DownloadState
import com.imcys.bilibilias.datastore.AppSettings
import com.imcys.bilibilias.data.download.record.DownloadDirFilesRules
import com.imcys.bilibilias.data.download.record.DownloadRecordDisplayRules
import com.imcys.bilibilias.download.DownloadDir
import com.imcys.bilibilias.data.download.output.BatchRenameRules
import com.imcys.bilibilias.data.download.output.FinalNameVerifyRules
import com.imcys.bilibilias.data.download.output.DuplicateDownloadRules
import com.imcys.bilibilias.download.FileOutputManager
import com.imcys.bilibilias.download.NewDownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class DownloadViewModel(
    private val downloadManager: NewDownloadManager,
    private val downloadTaskRepository: DownloadTaskRepository,
    private val contentResolver: ContentResolver,
    private val appSettingsRepository: AppSettingsRepository,
    /** 交付/清理都走它；这里只用来做"重复下载文件"的扫描与用户确认后的删除 */
    private val fileOutputManager: FileOutputManager,
) : ViewModel() {

    /**
     * 下载目录里"同一部视频有多份"的分组。
     *
     * ⚠️ 为什么要在界面上让用户确认，而不是自动删（2026-10-01 真机复现）：
     * 这台 ROM 的 MediaStore 会把撞名的新文件改成 `xxx (1)/(2).mp4`，而 app 对这些
     * MediaProvider 拥有的文件既删不动（EACCES）、事后按行删又常常命中 0 —— 自动判定
     * "哪个是旧文件"被 ROM 的实现细节堵死了。于是退一步：**列出来让用户点一下**，
     * 默认勾选"保留正式名、删掉副本"，比默默删错安全得多。
     */
    private val _duplicateGroups = MutableStateFlow<List<DuplicateDownloadRules.DuplicateGroup>>(emptyList())
    val duplicateGroups = _duplicateGroups.asStateFlow()

    /** 需要用户确认才能删除的那些行（不是本 app 拥有的副本）→ 界面去发起系统确认框 */
    private val _pendingDeleteUris = MutableStateFlow<List<android.net.Uri>>(emptyList())
    val pendingDeleteUris = _pendingDeleteUris.asStateFlow()

    /**
     * 重复检测要扫的目录：**新目录 + 旧目录**。
     *
     * 2026-10-01 目录由 `BiliDownloader` 改名为 `BDT` 后**不做搬移**（老文件留在原地），
     * 只扫新目录的话，用户旧目录里的历史副本**永远发现不了**。
     */
    private val duplicateScanRelativePaths: List<String>
        get() = DownloadDir.ALL_NAMES.map { "Download/$it" }

    /** 名字 → 它所在的相对路径（清理时必须知道从哪个目录删，否则删错目录 = 静默 no-op） */
    private val duplicateNameDir = mutableMapOf<String, String>()

    /** 刷新重复文件分组（进入页面时 / 清理后调用） */
    fun refreshDuplicateGroups() {
        viewModelScope.launch {
            val (groups, nameDirs) = withContext(Dispatchers.IO) {
                val collected = mutableListOf<DuplicateDownloadRules.DuplicateGroup>()
                val dirOf = mutableMapOf<String, String>()
                // ⚠️ B（2026-10-02 真机复现）：清理时必须保留"**记录实际引用的那一份**"。
                // 真机上出现过"记录引用 `… (2).m4a`、而正式名那份反而是孤儿"，
                // 原来只按名字挑正式名保留 → 照它清理会把记录删成「文件已丢失」。
                val referencedNames = _allDownloadSegment.value
                    .mapNotNull { runCatching { fileOutputManager.displayNameOf(it.savePath) }.getOrNull() }
                    .toSet()
                duplicateScanRelativePaths.forEach { relativePath ->
                    val found = runCatching {
                        fileOutputManager.findDuplicateGroups(relativePath, referencedNames)
                    }.getOrElse { emptyList() }
                    found.forEach { group ->
                        collected += group
                        dirOf[group.keepName] = relativePath
                        group.removableNames.forEach { dirOf[it] = relativePath }
                    }
                }
                collected to dirOf
            }
            duplicateNameDir.clear()
            duplicateNameDir.putAll(nameDirs)
            _duplicateGroups.value = groups
        }
    }

    /**
     * 用户确认后清理选中的重复文件。
     *
     * ⚠️ 分两种情况：**本 app 自己下载的文件**可以直接删；**不属于本 app 的行**
     * （上一版安装留下的、别的 App 写进来的副本）在 Android 11+ 必须走
     * `MediaStore.createDeleteRequest` 让系统弹确认框 —— 所以这里把这类 uri 通过
     * [pendingDeleteUris] 交给界面去发起系统确认，而不是静默失败。
     */
    fun cleanDuplicateFiles(names: List<String>) {
        if (names.isEmpty()) return
        viewModelScope.launch {
            // 名字可能来自新目录、也可能来自旧目录：**按所在目录分组**分别清理
            val byDir = names.groupBy { duplicateNameDir[it] ?: DOWNLOAD_RELATIVE_PATH }
            val results = withContext(Dispatchers.IO) {
                byDir.map { (relativePath, namesInDir) ->
                    fileOutputManager.deleteFilesByName(relativePath, namesInDir)
                }
            }
            val deleted = results.sumOf { it.deleted }
            val consent = results.flatMap { it.needsUserConsent }
            if (deleted > 0) {
                sendToast(R.string.duplicate_cleaned, deleted)
            }
            if (consent.isNotEmpty()) {
                _pendingDeleteUris.value = consent
            } else if (deleted == 0) {
                sendToast(R.string.duplicate_none_deleted)
            }
            refreshDuplicateGroups()
        }
    }

    /** 系统删除确认框结束后调用：清掉待确认列表并重新扫描 */
    fun onDeleteRequestFinished() {
        _pendingDeleteUris.value = emptyList()
        refreshDuplicateGroups()
    }

    // region 下载目录文件（A-② / #2：原来在「设置 → 存储管理」，现并入下载管理同一张卡片）

    /**
     * 下载目录（新 + 旧）里的**全部**文件。
     *
     * 与重复检查同源（同一个 `listDownloadFiles` + 同一组目录）：用户在一张卡片上
     * 既能清理重复副本、也能看清目录里到底有什么。
     */
    private val _downloadDirFiles = MutableStateFlow<List<FileOutputManager.DownloadFileEntry>>(emptyList())
    val downloadDirFiles = _downloadDirFiles.asStateFlow()

    /** 下载记录当前引用的显示名（用来标"无记录（孤儿）"） */
    private val _referencedDownloadNames = MutableStateFlow<Set<String>>(emptySet())
    val referencedDownloadNames = _referencedDownloadNames.asStateFlow()

    /**
     * 刷新下载目录清单。
     *
     * ⚠️ 与重复检查一样要扫**新目录 + 旧目录**：只扫新目录的话，改名（`BiliDownloader` → `BDT`）
     * 前留下的文件永远不会被列出来 —— 而"重装/改名后留下的孤儿"正是这张清单要回答的问题。
     *
     * 每次刷新都写轨迹（`[目录文件] …`）：真机核验就靠这一行。
     */
    fun refreshDownloadDirFiles() {
        viewModelScope.launch {
            val (files, names) = withContext(Dispatchers.IO) {
                // 记录引用的显示名：content:// 路径能问到真实显示名（file:// 问不到，跳过）
                val names = runCatching {
                    downloadTaskRepository.getSegmentAll().first()
                        .mapNotNull { runCatching { fileOutputManager.displayNameOf(it.savePath) }.getOrNull() }
                        .toSet()
                }.getOrElse { emptySet<String>() }
                val list = duplicateScanRelativePaths
                    .flatMap { path ->
                        runCatching { fileOutputManager.listDownloadFiles(path) }.getOrElse { emptyList() }
                    }
                    .distinctBy { it.uriString }
                    .sortedByDescending { it.addedMs }
                list to names
            }
            _downloadDirFiles.value = files
            _referencedDownloadNames.value = names
            val orphanCount = DownloadDirFilesRules.summarize(
                dirFileDisplayNames = files.map { it.displayName },
                referencedNames = names,
            ).orphan
            runCatching {
                fileOutputManager.logDiagnostic(
                    "目录文件",
                    "列出 ${files.size} 个文件，其中 $orphanCount 个无记录（目录：${duplicateScanRelativePaths.joinToString("、")}）",
                )
            }
        }
    }

    /**
     * 删除下载目录里的一个文件（用户在该清单里点"删除"）。
     *
     * 删不动时**如实说**：本 app 没有"所有文件访问"时，媒体库里由系统/别的 App 拥有的行
     * 只能走系统确认框，直接删会命中 0 行（这台 ROM 上踩过），所以不能报"已删除"。
     */
    fun deleteDownloadDirFile(file: FileOutputManager.DownloadFileEntry) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.delete(android.net.Uri.parse(file.uriString), null, null) > 0
                }.getOrElse { false }
            }
            runCatching {
                fileOutputManager.logDiagnostic(
                    "目录文件",
                    "删除${if (deleted) "成功" else "失败"} 名称=${file.displayName}",
                )
            }
            if (deleted) {
                _downloadDirFiles.value = _downloadDirFiles.value.filterNot { it.uriString == file.uriString }
                sendToast(R.string.storage_deleted)
            } else {
                sendToast(R.string.storage_delete_failed_not_owned)
            }
        }
    }
    // endregion

    // region 事件流
    private val _uiEvent = MutableSharedFlow<DownloadUiEvent>()
    val uiEvent = _uiEvent.asSharedFlow()
    // endregion

    // region 下载任务状态
    val downloadListState = downloadManager.getAllDownloadTasks()

    private val _allDownloadSegment = downloadTaskRepository.getSegmentAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val downloadSortType = appSettingsRepository.appSettingsFlow
        .map { it.downloadSortType }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppSettings.DownloadSortType.DownloadSort_TimeDesc
        )

    /**
     * 已完成的下载列表（已排序）
     */
    val completedSegments = combine(
        _allDownloadSegment,
        downloadSortType
    ) { segments, sortType ->
        segments
            .filter { it.downloadState == DownloadState.COMPLETED }
            .sortedWith(getSortComparator(sortType))
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    // endregion

    companion object {
        /** 下载目录（与交付时用的 MediaStore RELATIVE_PATH 一致） */
        private val DOWNLOAD_RELATIVE_PATH = com.imcys.bilibilias.download.DownloadDir.RELATIVE_PATH
    }

    // region 排序
    fun updateDownloadSortType(sortType: AppSettings.DownloadSortType) {
        viewModelScope.launch {
            appSettingsRepository.updateDownloadSortType(sortType)
        }
    }

    private fun getSortComparator(sortType: AppSettings.DownloadSortType): Comparator<DownloadSegment> {
        return when (sortType) {
            AppSettings.DownloadSortType.DownloadSort_TimeDesc -> compareByDescending { it.updateTime }
            AppSettings.DownloadSortType.DownloadSort_TimeAsc -> compareBy { it.updateTime }
            AppSettings.DownloadSortType.DownloadSort_TitleAsc -> compareBy { it.title }
            AppSettings.DownloadSortType.DownloadSort_TitleDesc -> compareByDescending { it.title }
            AppSettings.DownloadSortType.DownloadSort_SizeDesc -> compareByDescending { it.fileSize }
            AppSettings.DownloadSortType.DownloadSort_SizeAsc -> compareBy { it.fileSize }
            else -> compareByDescending { it.updateTime }
        }
    }
    // endregion

    /** 失败（ERROR）的任务：给「全部重试」用 */
    val errorSegments = _allDownloadSegment
        .map { list -> list.filter { it.downloadState == DownloadState.ERROR } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 一键重试所有失败任务 */
    fun retryAllFailed() {
        val failed = errorSegments.value
        if (failed.isEmpty()) return
        viewModelScope.launch {
            failed.forEach { downloadManager.resumeTask(it.segmentId) }
            sendToast(R.string.retry_failed_done, failed.size)
        }
    }

    // region 下载任务控制
    fun pauseDownloadTask(segmentId: Long) {
        viewModelScope.launch { downloadManager.pauseTask(segmentId) }
    }

    @SuppressLint("MissingPermission")
    fun resumeDownloadTask(segmentId: Long) {
        viewModelScope.launch { downloadManager.resumeTask(segmentId) }
    }

    fun cancelDownloadTask(segmentId: Long) {
        viewModelScope.launch { downloadManager.cancelTask(segmentId) }
    }
    // endregion

    // region 文件已丢失探测
    /**
     * 记录里有媒体路径、但**文件已经不在了**的那些 segmentId（用于列表上标「文件已丢失」）。
     *
     * ⚠️ 判据已统一到 `FileOutputManager.probeSavePath`（⑥）：**问 MediaStore 优先** ——
     * 这台 ROM 上"文件真没了"与"文件在、但没权限打开"**抛的是同一个 `FileNotFoundException`**，
     * 只看异常分不出来（2026-10-02 就误报过一次）。宽判据 = 媒体库行 / 能打开 / 磁盘存在 / 未知异常，
     * 任一为正就算"还在"；其中 `file://` 路径的"有行"算正信号、`content://` 的不算
     * （理由见 `SavePathProbeRules` 的类注释：content 的行是本 app 自己的，能开就该开得开）。
     * 并且**绝不据此自动删记录**。
     */
    private val _missingFileIds = MutableStateFlow<Set<Long>>(emptySet())
    val missingFileIds = _missingFileIds.asStateFlow()

    /**
     * 刷新"文件已丢失"清单（进列表时调用；条数很少，直接每条探一次）。
     *
     * 每次探测结果都写进轨迹文件（`[文件探测] … 结果=存在/缺失 原因=… 路径=…`）：
     * 这类"误报/漏报"只可能在真机上出现，没有轨迹就只能靠猜（2026-10-02 就踩过一次）。
     */
    fun refreshMissingFiles(segments: List<DownloadSegment>) {
        val targets = segments.filter { DownloadRecordDisplayRules.hasMediaFile(it.savePath) }
        viewModelScope.launch {
            val missing = withContext(Dispatchers.IO) {
                val result = mutableSetOf<Long>()
                targets.forEach { segment ->
                    // ⑥ 统一判据在 FileOutputManager 里（问 MediaStore 优先），
                    // 这里只负责把结果落轨迹
                    val verdict = fileOutputManager.probeSavePath(segment.savePath)
                    if (!verdict.exists) result += segment.segmentId
                    fileOutputManager.logDiagnostic(
                        "文件探测",
                        "「${segment.title}」结果=${if (verdict.exists) "存在" else "缺失"} " +
                            "原因=${verdict.reason} 路径=${segment.savePath}"
                    )
                }
                result
            }
            _missingFileIds.value = missing
        }
    }

    // endregion

    // region 文件操作
    /**
     * 请求打开下载的文件
     * 发送事件给 UI 层处理
     */
    fun requestOpenFile(segment: DownloadSegment) {
        // ⑥ 统一判据：MediaStore 行 / 能打开 / 磁盘存在，任一为正就认为还在。
        // 原来只对非 content:// 的路径做 `File.exists()` —— 那对媒体库拥有的文件**恒 false**，
        // 会把"文件其实在、只是不让 stat"的记录拦下并提示"文件不存在"。
        // 探测要查 MediaStore，放到 IO 线程做。
        viewModelScope.launch {
            val exists = withContext(Dispatchers.IO) {
                fileOutputManager.probeSavePath(segment.savePath).exists
            }
            if (!exists) {
                sendToast(R.string.file_not_exist_may_deleted)
                return@launch
            }
            _uiEvent.emit(DownloadUiEvent.OpenFile(segment))
        }
    }

    /**
     * 删除多个下载任务及文件
     */
    /**
     * 批量移动选中的文件到下载目录下的子目录（B2）。
     *
     * 只移动文件、不动记录：content URI 不变，所以记录照旧有效（见 FileOutputManager 的注释）。
     */
    fun moveSelectedTasks(segments: List<DownloadSegment>, subDirName: String) {
        if (segments.isEmpty()) return
        viewModelScope.launch {
            val moved = withContext(Dispatchers.IO) {
                segments.count { fileOutputManager.moveDownloadFileToSubDir(it.savePath, subDirName) }
            }
            if (moved > 0) {
                sendToast(R.string.batch_move_done, moved, subDirName)
            } else {
                sendToast(R.string.batch_move_none)
            }
        }
    }

    /**
     * 批量重命名选中的文件（B2）。
     *
     * ⚠️ 与"移动"的关键差异：改名会撞上这台 ROM 的 `(N)` 自动后缀
     * （`update(DISPLAY_NAME)` 撞名不报错，MediaProvider 自己加 `xxx (1).mp4`），
     * 所以：
     * 1. **后缀由我们自己按选中顺序分配**（[BatchRenameRules]）：第 1 个用新名、
     *    第 2..N 个 `新名 (1)`/`(2)`…，避免选中项互相撞名、序号被 ROM 打乱；
     * 2. 每个文件改完都由 `FileOutputManager.renameDownloadFile` **回读真实显示名**，
     *    结果通过 [DownloadUiEvent.RenameFinished] 原样告诉用户。
     *
     * `savePath` 不用动（content URI，行的 `_ID` 不变）；但**记录标题会跟着文件名一起改**
     * （2026-10-02 用户决定）：只更新 `title` 一列，见 `DownloadTaskDao.updateSegmentTitle`。
     */
    fun renameSelectedTasks(segments: List<DownloadSegment>, newBaseName: String) {
        if (segments.isEmpty()) return
        // 名字不可用时返回 null → 一个文件都不动（no-op 比改出半个名字安全），
        // 由 UI 层用 strings.xml 的文案提示（VM 里不写死中文）
        val baseNames = BatchRenameRules.assignBaseNames(newBaseName, segments.size)
        if (baseNames == null) {
            viewModelScope.launch {
                _uiEvent.emit(
                    DownloadUiEvent.RenameFinished(
                        finalNames = emptyList(),
                        failedCount = segments.size,
                        invalidName = true,
                    )
                )
            }
            return
        }
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                segments.mapIndexed { index, segment ->
                    val actual = fileOutputManager.renameDownloadFile(segment.savePath, baseNames[index])
                    if (actual != null) {
                        // 记录标题跟着**回读到的真实文件名**走（去掉扩展名），
                        // 这样列表显示的名字与文件管理器里看到的完全一致（含撞名后的 `(N)`）。
                        val newTitle = FinalNameVerifyRules.splitName(actual).first
                        if (newTitle.isNotBlank() && newTitle != segment.title) {
                            runCatching {
                                downloadTaskRepository.updateSegmentTitle(segment.segmentId, newTitle)
                            }
                        }
                    }
                    actual
                }
            }
            _uiEvent.emit(
                DownloadUiEvent.RenameFinished(
                    finalNames = results.filterNotNull(),
                    failedCount = results.count { it == null },
                )
            )
        }
    }

    fun deleteSelectedTasks(segments: List<DownloadSegment>) {
        viewModelScope.launch(Dispatchers.IO) {
            segments.forEach { segment ->
                deleteFileInternal(segment.savePath)
                downloadTaskRepository.deleteSegment(segment.segmentId)
            }
        }
    }

    /**
     * 删除单个下载任务及文件
     */
    fun deleteDownloadSegment(segment: DownloadSegment) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = deleteFileInternal(segment.savePath)
            downloadTaskRepository.deleteSegment(segment.segmentId)

            val messageRes = when (result) {
                DeleteResult.SUCCESS -> R.string.delete_done
                DeleteResult.FILE_NOT_EXIST -> R.string.delete_file_not_exist
                DeleteResult.FAILED -> R.string.delete_failed
            }
            sendToast(messageRes)
        }
    }

    /**
     * 删除某条记录指向的文件。
     *
     * ⚠️ 这里**刻意不用** [FileOutputManager.probeSavePath] 的宽判据：删除的语义是
     * "这个文件现在能不能删"，不是"记录还算不算有效"。宽判据会把"行还在、文件已没了"
     * 说成"在"，于是只能报"删除失败"；而现在的 `File.exists()` 判据会如实报"文件不存在"，
     * 对用户更准确。**判据统一 ≠ 所有地方都用同一个**，用途不同就该分开（见 SavePathProbeRules 注释）。
     */
    private fun deleteFileInternal(savePath: String): DeleteResult {
        return runCatching {
            if (savePath.startsWith("content://")) {
                val uri = savePath.toUri()
                val rows = contentResolver.delete(uri, null, null)
                if (rows > 0) DeleteResult.SUCCESS else DeleteResult.FAILED
            } else {
                val file = File(savePath)
                when {
                    !file.exists() -> DeleteResult.FILE_NOT_EXIST
                    file.delete() -> DeleteResult.SUCCESS
                    else -> DeleteResult.FAILED
                }
            }
        }.getOrElse { DeleteResult.FAILED }
    }

    /** 弹 toast：只传**资源 id + 参数**，文案在 `strings.xml`（VM 里不再写死中文） */
    private fun sendToast(@StringRes resId: Int, vararg formatArgs: Any) {
        viewModelScope.launch {
            _uiEvent.emit(DownloadUiEvent.ShowToast(resId, formatArgs.toList()))
        }
    }
    // endregion

    private enum class DeleteResult {
        SUCCESS, FILE_NOT_EXIST, FAILED
    }
}