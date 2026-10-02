package com.imcys.bilibilias.ui.download

import com.imcys.bilibilias.database.entity.download.DownloadSegment

/**
 * 下载页面 UI 事件
 */
sealed interface DownloadUiEvent {
    data class ShowToast(val message: String) : DownloadUiEvent
    data class OpenFile(val segment: DownloadSegment) : DownloadUiEvent

    /**
     * 批量重命名结束（B2）。
     *
     * ⚠️ 携带的是**回读到的真实文件名**（撞名时带 `(N)`），不是用户输入的期望名 ——
     * 这台 ROM 上 MediaProvider 会自己加后缀，只有回读值是真的（交接文档 §14.2）。
     * 文案在 UI 层用 `strings.xml` 拼，VM 里不写死中文。
     */
    data class RenameFinished(
        val finalNames: List<String>,
        val failedCount: Int,
        /** 名字本身不可用（空/含 `..`）→ 一个文件都没动，UI 提示"新名字不可用" */
        val invalidName: Boolean = false,
    ) : DownloadUiEvent
}
