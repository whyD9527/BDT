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

    /** 刷新重复文件分组（进入页面时 / 清理后调用） */
    fun refreshDuplicateGroups() {
        viewModelScope.launch {
            val groups = withContext(Dispatchers.IO) {
                fileOutputManager.findDuplicateGroups(DOWNLOAD_RELATIVE_PATH)
            }
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
            val result = withContext(Dispatchers.IO) {
                fileOutputManager.deleteFilesByName(DOWNLOAD_RELATIVE_PATH, names)
            }
            if (result.deleted > 0) {
                sendToast("已清理 ${result.deleted} 个重复文件")
            }
            if (result.needsUserConsent.isNotEmpty()) {
                _pendingDeleteUris.value = result.needsUserConsent
            } else if (result.deleted == 0) {
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
        private const val DOWNLOAD_RELATIVE_PATH = "Download/BiliDownloader"
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

    /** 刷新"文件已丢失"清单（进列表时调用；条数很少，直接每条探一次） */
    fun refreshMissingFiles(segments: List<DownloadSegment>) {
        val targets = segments.filter { DownloadRecordDisplayRules.hasMediaFile(it.savePath) }
        viewModelScope.launch {
            val missing = withContext(Dispatchers.IO) {
                targets.filterNot { mediaFileExists(it.savePath) }.map { it.segmentId }.toSet()
            }
            _missingFileIds.value = missing
        }
    }

    private fun mediaFileExists(savePath: String): Boolean = try {
        val uri = if (savePath.startsWith("content://")) {
            Uri.parse(savePath)
        } else {
            File(savePath).toUri()
        }
        contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: FileNotFoundException) {
        // 行/文件确实不在了
        false
    } catch (e: Exception) {
        // 权限、不支持的形式……一律按"还在"处理，避免误报
        true
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