package com.imcys.bilibilias.ui.download

import android.annotation.SuppressLint
import android.content.ContentResolver
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imcys.bilibilias.data.repository.AppSettingsRepository
import com.imcys.bilibilias.data.repository.DownloadTaskRepository
import com.imcys.bilibilias.database.entity.download.DownloadSegment
import com.imcys.bilibilias.database.entity.download.DownloadState
import com.imcys.bilibilias.datastore.AppSettings
import android.net.Uri
import java.io.FileNotFoundException
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
                duplicateScanRelativePaths.forEach { relativePath ->
                    val found = runCatching { fileOutputManager.findDuplicateGroups(relativePath) }
                        .getOrElse { emptyList() }
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
                sendToast("已清理 $deleted 个重复文件")
            }
            if (consent.isNotEmpty()) {
                _pendingDeleteUris.value = consent
            } else if (deleted == 0) {
                sendToast("没有文件被删除（可能已被移动，或需要「所有文件访问」权限）")
            }
            refreshDuplicateGroups()
        }
    }

    /** 系统删除确认框结束后调用：清掉待确认列表并重新扫描 */
    fun onDeleteRequestFinished() {
        _pendingDeleteUris.value = emptyList()
        refreshDuplicateGroups()
    }

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
            sendToast("已重试 ${failed.size} 个失败任务")
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
     * ⚠️ 判据只有一条：打开文件抛 `FileNotFoundException`。
     * 权限不足（SecurityException）、URI 形式不支持等**一律当作"还在"** ——
     * 宁可多显示一条"看起来正常"的记录，也不能把好记录误标成丢失、更不能据此自动删记录。
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
                    val verdict = probeMediaFile(segment.savePath)
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

    private data class ProbeVerdict(val exists: Boolean, val reason: String)

    /**
     * 判断记录里的媒体文件**是否还在**。
     *
     * ⚠️ 关键难点（2026-10-02 真机）：`openFileDescriptor` 在两种情况下**都抛
     * `FileNotFoundException`** —— ① 文件真的没了；② 文件在、但 app 没有权限打开
     * （这台 ROM 上媒体库拥有的文件就是这样，EACCES）。只看异常会把 ② 误判成 ①，
     * 于是好好的记录被标上「文件已丢失」。
     *
     * 所以：`content://` 的 FNF 视为"没了"（行是 app 自己的，能打开）；
     * `file://` 的 FNF 则**再用"按名字问媒体库"确认一次**。
     */
    private fun probeMediaFile(savePath: String): ProbeVerdict = try {
        val isContent = savePath.startsWith("content://")
        val uri = if (isContent) Uri.parse(savePath) else File(savePath).toUri()
        val opened = contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        ProbeVerdict(opened, if (opened) "打开成功" else "打开返回空")
    } catch (e: FileNotFoundException) {
        if (savePath.startsWith("content://")) {
            ProbeVerdict(false, "content 打不开：FileNotFoundException(${e.message})")
        } else {
            val match = runCatching {
                DownloadDir.ALL_NAMES.any { name ->
                    fileOutputManager.downloadDirContains("Download/$name", File(savePath).name)
                }
            }.getOrElse { false }
            ProbeVerdict(match, "file 打不开（可能 EACCES）→按名字查媒体库=${if (match) "有行" else "无行"}")
        }
    } catch (e: Exception) {
        // 其它异常一律按"还在"处理：宁可多显示一条，也不能误标丢失
        ProbeVerdict(true, "${e.javaClass.simpleName}（按存在处理）")
    }
    // endregion

    // region 文件操作
    /**
     * 请求打开下载的文件
     * 发送事件给 UI 层处理
     */
    fun requestOpenFile(segment: DownloadSegment) {
        val savePath = segment.savePath
        // 检查文件是否存在
        if (!savePath.startsWith("content://")) {
            val file = File(savePath)
            if (!file.exists()) {
                sendToast("文件不存在，可能已被删除")
                return
            }
        }
        viewModelScope.launch {
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
            sendToast(
                if (moved > 0) "已移动 $moved 个文件到 $subDirName/"
                else "没有文件被移动（可能不是本应用的文件，需要「所有文件访问」或系统确认）"
            )
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

            val message = when (result) {
                DeleteResult.SUCCESS -> "删除成功"
                DeleteResult.FILE_NOT_EXIST -> "文件不存在"
                DeleteResult.FAILED -> "删除失败，文件可能已经被删除或不存在"
            }
            sendToast(message)
        }
    }

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

    private fun sendToast(message: String) {
        viewModelScope.launch {
            _uiEvent.emit(DownloadUiEvent.ShowToast(message))
        }
    }
    // endregion

    private enum class DeleteResult {
        SUCCESS, FILE_NOT_EXIST, FAILED
    }
}