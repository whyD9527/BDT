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

        val actualDuration = duration ?: getMediaDuration(subTasks.firstOrNull()?.savePath)

        executeFfmpegCommand(command, outputFile, actualDuration, onProgress)
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
        duration: Long,
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
