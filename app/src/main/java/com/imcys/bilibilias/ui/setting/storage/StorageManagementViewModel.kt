package com.imcys.bilibilias.ui.setting.storage

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imcys.bilibilias.common.event.sendToastEvent
import com.imcys.bilibilias.common.utils.StorageInfoData
import com.imcys.bilibilias.common.utils.StorageUtil
import com.imcys.bilibilias.data.repository.AppSettingsRepository
import com.imcys.bilibilias.data.repository.DownloadTaskRepository
import com.imcys.bilibilias.data.util.readOnce
import com.imcys.bilibilias.database.entity.download.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StorageManagementViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    private val downloadTaskRepository: DownloadTaskRepository,
) : ViewModel() {

    companion object {
        /** 还没终结、或者暂停着等续传的状态：这些任务的临时文件都在 `files/video|audio` 里 */
        private val ACTIVE_STATES = setOf(
            DownloadState.PRE_TASK,
            DownloadState.WAITING,
            DownloadState.DOWNLOADING,
            DownloadState.MERGING,
            DownloadState.POST_TASK,
            DownloadState.PAUSE,
        )
    }

    sealed interface StorageManagementUIState {
        object Loading : StorageManagementUIState
        data class Success(
            val storageInfoData: StorageInfoData,
            val hasDownloadSAFPermission: Boolean
        ) : StorageManagementUIState

        data class Error(val errorMsg: String) : StorageManagementUIState
    }

    private val _uiState =
        MutableStateFlow<StorageManagementUIState>(StorageManagementUIState.Loading)

    val uiState = _uiState.asStateFlow()

    fun loadStorageInfo(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val storageInfoData = StorageUtil.getStorageInfoData(context)
                val hasDownloadSAFPermission = StorageUtil.hasASDownloadSAFPermission(context)
                _uiState.emit(
                    StorageManagementUIState.Success(
                        storageInfoData,
                        hasDownloadSAFPermission
                    )
                )
            } catch (e: Exception) {
                _uiState.emit(StorageManagementUIState.Error(e.message ?: "未知错误"))
            }
        }
    }

    fun cleanAppCache(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value is StorageManagementUIState.Success) {
                // ⚠️ 有在途/已暂停的下载任务时**不能清**（2026-09-15 复审 M4）：
                // `StorageUtil.clearCache` 会递归删 `files/video`、`files/audio`，
                // 而那正是下载的工作目录（`.downloading` / `.downloadpart` / 分片都在里面）——
                // 清掉会让在途任务失败、暂停任务的分片全丢（得从头下）。
                val busy = runCatching {
                    downloadTaskRepository.getSegmentAll().readOnce()
                        .any { it.downloadState in ACTIVE_STATES }
                }.getOrDefault(false)
                if (busy) {
                    sendToastEvent("有下载任务正在进行或已暂停，请先让它结束或取消，再清缓存")
                    return@launch
                }
                StorageUtil.clearCache(context)
                val storageInfoData = StorageUtil.getStorageInfoData(context)
                val hasDownloadSAFPermission = StorageUtil.hasASDownloadSAFPermission(context)
                _uiState.value =
                    StorageManagementUIState.Success(storageInfoData, hasDownloadSAFPermission)
            }
        }
    }

    fun saveDownloadUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            appSettingsRepository.saveDownloadSAFUriString(uri.toString())
            loadStorageInfo(context)
        }
    }


}