package com.imcys.bilibilias.ui.download

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.imcys.bilibilias.R
import com.imcys.bilibilias.database.entity.download.DownloadMode

/**
 * F（2026-10-02 真机复验）：`DownloadMode.title` 是**硬编码中文**（`音视频/仅视频/仅音频`），
 * 英文界面下卡片与下拉里就漏出中文。这里做 UI 层映射 —— 枚举本身不改（它参与持久化的是 `name`，
 * 不能动），文案统一进 `strings.xml`（中英双套）。
 */
fun downloadModeLabelRes(mode: DownloadMode): Int = when (mode) {
    DownloadMode.AUDIO_VIDEO -> R.string.download_mode_audio_video
    DownloadMode.VIDEO_ONLY -> R.string.download_mode_video_only
    DownloadMode.AUDIO_ONLY -> R.string.download_mode_audio_only
}

/**
 * `@Composable` 版本（给"Composable 作用域里直接用"的地方，如下载卡片）。
 *
 * ⚠️ 下拉的 `onValue = { … }` 是**非 @Composable** 的映射 lambda，不能在这里面调 `stringResource`
 * （2026-10-02 CI 就因为这个红过一次），那种地方要先用 [downloadModeLabelRes] 拿 id 再 `getString`。
 */
@Composable
fun downloadModeLabel(mode: DownloadMode): String = stringResource(downloadModeLabelRes(mode))
