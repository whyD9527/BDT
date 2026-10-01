package com.imcys.bilibilias.download

import android.app.Application
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.imcys.bilibilias.data.download.merge.FfmpegCommandBuilder
import com.imcys.bilibilias.data.download.merge.SubtitleSpec
import com.imcys.bilibilias.data.model.download.DownloadSubTask
import com.imcys.bilibilias.data.model.download.MediaContainerConfig
import com.imcys.bilibilias.data.repository.DownloadTaskRepository
import com.imcys.bilibilias.database.entity.download.DownloadMode
import com.imcys.bilibilias.database.entity.download.DownloadSubTaskType
import com.imcys.bilibilias.database.entity.download.NamingConventionInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * FFmpeg合并器
 * 负责视频/音频合并和字幕/封面嵌入
 */
class FfmpegMerger(
    private val context: Application,
    private val downloadTaskRepository: DownloadTaskRepository
) {

    /**
     * 合并视频和音频
     */
    suspend fun mergeMedia(
        appDownloadTask: AppDownloadTask,
        subTasks: List<DownloadSubTask>,
        downloadMode: DownloadMode,
        outputFile: File,
        subtitles: List<LocalSubtitle> = emptyList(),
        coverPath: String = "",
        duration: Long? = null,
        onProgress: (Float) -> Unit,
        mediaContainerConfig: MediaContainerConfig,
        namingConventionInfo: NamingConventionInfo?
    ) {
        val command = FfmpegCommandBuilder.build(
            subTasks = subTasks,
            downloadMode = downloadMode,
            outputPath = outputFile.absolutePath,
            mediaContainerConfig = mediaContainerConfig,
            title = appDownloadTask.downloadSegment.title,
            description = appDownloadTask.downloadTask.description,
            copyright = NewDownloadManager.buildRefererUrl(downloadTaskRepository, appDownloadTask),
            subtitles = subtitles.map { SubtitleSpec(it.path, it.lang, it.langDoc) },
            coverPath = coverPath,
        )

        // ⚠️ 单位必须统一成**毫秒**（2026-09-15 复审 M7）：
        // ffmpeg 的 `statistics.time` 是毫秒，而 `duration` 参数来自 B 站接口（**秒**），
        // `getMediaDuration()` 返回的又是毫秒 —— 原来直接混用，于是"毫秒/秒"把进度
        // 一开就夹到 1.0，合并阶段通知恒显示 100%（用户以为卡死）。
        val actualDurationMs = duration?.let { it * 1000L }
            ?: getMediaDuration(subTasks.firstOrNull()?.savePath)

        // 只有"独立音频子任务"才要求成品必须有音轨（durl 单文件可能本来就没音轨，不能误判）
        val expectAudio = subTasks.any { it.subTaskType == DownloadSubTaskType.AUDIO }

        executeFfmpegCommand(command, outputFile, actualDurationMs, expectAudio, onProgress)
    }

    /** 合并产物里有没有音轨（只有"确实下载过独立音频子任务"时才需要校验） */
    private fun hasAudioStream(file: File): Boolean {
        return runCatching {
            val session = FFprobeKit.getMediaInformation(file.absolutePath)
            ReturnCode.isSuccess(session.returnCode) &&
                session.mediaInformation?.streams?.any { it.type == "audio" } == true
        }.getOrDefault(false)
    }

    /**
     * 获取媒体时长
     */
    private suspend fun getMediaDuration(filePath: String?): Long {
        return suspendCancellableCoroutine { continuation ->
            if (filePath.isNullOrBlank()) {
                continuation.resume(0L)
                return@suspendCancellableCoroutine
            }

            try {
                val mediaInfoSession = FFprobeKit.getMediaInformation(filePath)

                if (ReturnCode.isSuccess(mediaInfoSession.returnCode)) {
                    val mediaInfo = mediaInfoSession.mediaInformation
                    if (mediaInfo != null) {
                        val durationInSeconds = mediaInfo.duration?.toDoubleOrNull() ?: 0.0
                        val durationInMillis = (durationInSeconds * 1000).toLong()
                        continuation.resume(durationInMillis)
                    } else {
                        continuation.resume(0L)
                    }
                } else {
                    Log.w("FFmpeg", "FFprobe failed: ${mediaInfoSession.returnCode}")
                    continuation.resume(0L)
                }
            } catch (e: Exception) {
                continuation.resume(0L)
            }
        }
    }

    /**
     * 执行FFmpeg命令。
     *
     * 注意用的是 [FFmpegKit.executeWithArgumentsAsync]（**参数数组**）而不是 `executeAsync(命令串)`：
     * 后者会把命令串重新分词，标题/简介里的英文双引号会把参数切碎（见 [FfmpegCommandBuilder] 的说明）。
     * 数组形式不经过分词，参数原样传给 native。
     */
    private suspend fun executeFfmpegCommand(
        arguments: List<String>,
        outputFile: File,
        durationMs: Long,
        expectAudio: Boolean,
        onProgress: (Float) -> Unit
    ) {
        // 保留这行取证日志（格式与改造前一致，只是元数据不再手工加引号了）
        Log.d("FFmpeg", "执行命令: ${arguments.joinToString(" ")}")

        suspendCancellableCoroutine { continuation ->
            var lastProgressEmit = 0L

            val session = FFmpegKit.executeWithArgumentsAsync(
                arguments.toTypedArray(),
                { session ->
                    when {
                        ReturnCode.isSuccess(session.returnCode) -> {
                            if (!outputFile.exists() || outputFile.length() == 0L) {
                                continuation.resumeWithException(Exception("输出文件生成失败"))
                            } else if (expectAudio && !hasAudioStream(outputFile)) {
                                // ⚠️ 返回码为 0 不等于产物对（2026-09-15 复审 M8）：
                                // 独立音频子任务明明下好了，成品却没有音轨 → 无声视频 + COMPLETED + 源文件被删，
                                // 事后完全无法补救。这里让它**失败**（源文件会被保留，重试只需再合并一次）。
                                // 只在"确实有独立音频子任务"时校验，避免把 durl 这类合法无声源误判成失败。
                                outputFile.deleteIfExists()
                                Log.e("FFmpeg", "合并结果没有音轨，判定失败: ${outputFile.name}")
                                continuation.resumeWithException(
                                    Exception("合并结果没有音轨（音频子任务已下载，但没被映射进成品）")
                                )
                            } else {
                                Log.d("FFmpeg", "合并完成: ${outputFile.absolutePath}")
                                continuation.resume(Unit)
                            }
                        }

                        ReturnCode.isCancel(session.returnCode) -> {
                            outputFile.deleteIfExists()
                            Log.w("FFmpeg", "任务被取消")
                            continuation.resumeWithException(CancellationException("任务被取消"))
                        }

                        else -> {
                            outputFile.deleteIfExists()
                            Log.e("FFmpeg", "执行失败: ${session.failStackTrace}")
                            continuation.resumeWithException(
                                Exception("FFmpeg执行失败: ${session.failStackTrace}")
                            )
                        }
                    }
                },
                { log ->
                    Log.d("FFmpeg", "${log}")
                },
                { statistics ->
                    if (statistics.time > 0 && duration > 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastProgressEmit > 100) {
                            val progress = (statistics.time.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                            onProgress(progress)
                            lastProgressEmit = now
                        }
                    }
                }
            )

            continuation.invokeOnCancellation {
                Log.w("FFmpeg", "协程取消，停止FFmpeg会话")
                FFmpegKit.cancel(session.sessionId)
            }
        }
    }

    private fun File.deleteIfExists() {
        if (exists()) delete()
    }
}
