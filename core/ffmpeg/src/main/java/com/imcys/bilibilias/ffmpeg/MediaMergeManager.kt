package com.imcys.bilibilias.ffmpeg

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File

object MediaMergeManager {
    interface MediaMergeListener {
        fun onProgress(progress: Int)
        fun onError(errorMsg: String)
        fun onComplete()
    }

    suspend fun mergeVideoAndAudioSuspend(
        videoPath: String,
        audioPath: String,
        outputPath: String,
        listener: MediaMergeListener
    ): Result<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            mergeVideoAndAudioInternal(videoPath, audioPath, outputPath, listener)
            Result.success("成功")
        } catch (e: Exception) {
            listener.onError(e.message ?: "合并失败")
            Result.failure(e)
        }
    }

    private fun mergeVideoAndAudioInternal(
        videoPath: String,
        audioPath: String,
        outputPath: String,
        listener: MediaMergeListener
    ) {
        // ⚠️ 2026-10-01 复审（core:ffmpeg 模块，当前未进构建）三处修正：
        // 1. **资源必须放进 finally**：原来只在"全部写完"那条路径上 release()，
        //    中途任何一步抛异常（writeSampleData 失败、磁盘写满、muxer.stop 抛错）
        //    都会漏掉 MediaMuxer + 两个 MediaExtractor 的 native 资源 —— 反复重试会把 fd 耗光；
        // 2. **进度 100% 必须放在 muxer.stop() 之后**：原来先报 100% 再 stop，
        //    stop 失败时用户已经看到"100% 完成"，而产物其实没收尾；
        // 3. **失败要删掉半成品**：否则下次"已有文件"的判断会把截断文件当成完整成品。
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            val vEx = MediaExtractor()
            val aEx = MediaExtractor()
            val mx = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoExtractor = vEx
            audioExtractor = aEx
            muxer = mx
            vEx.setDataSource(videoPath)
            aEx.setDataSource(audioPath)

            var videoTrackIndex = -1
            var audioTrackIndex = -1

            // 选取视频轨道
            for (i in 0 until vEx.trackCount) {
                val format = vEx.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("video/")) {
                    vEx.selectTrack(i)
                    videoTrackIndex = mx.addTrack(format)
                    break
                }
            }
            // 选取音频轨道
            for (i in 0 until aEx.trackCount) {
                val format = aEx.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    aEx.selectTrack(i)
                    audioTrackIndex = mx.addTrack(format)
                    break
                }
            }
            if (videoTrackIndex == -1 || audioTrackIndex == -1) {
                throw Exception("找不到音视频轨道")
            }
            mx.start()
            muxerStarted = true

            val bufferSize = 1024 * 1024
            val buffer = ByteArray(bufferSize)
            val bufferInfo = android.media.MediaCodec.BufferInfo()

            // 获取总时长用于进度计算
            fun findTrackIndex(extractor: MediaExtractor, mimePrefix: String): Int {
                for (i in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    if (mime != null && mime.startsWith(mimePrefix)) return i
                }
                return -1
            }

            val videoTrackIdx = findTrackIndex(vEx, "video/")
            val audioTrackIdx = findTrackIndex(aEx, "audio/")
            val videoDuration =
                if (videoTrackIdx != -1) vEx.getTrackFormat(videoTrackIdx)
                    .getLong(MediaFormat.KEY_DURATION) else 0L
            val audioDuration =
                if (audioTrackIdx != -1) aEx.getTrackFormat(audioTrackIdx)
                    .getLong(MediaFormat.KEY_DURATION) else 0L
            val totalDuration = maxOf(videoDuration, audioDuration)
            var lastProgress = -1
            // 写入视频
            var videoDone = false
            while (!videoDone) {
                bufferInfo.offset = 0
                bufferInfo.size = vEx.readSampleData(java.nio.ByteBuffer.wrap(buffer), 0)
                if (bufferInfo.size < 0) {
                    videoDone = true
                    bufferInfo.size = 0
                } else {
                    bufferInfo.presentationTimeUs = vEx.sampleTime
                    // 只保留关键帧标志
                    bufferInfo.flags =
                        if ((vEx.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                            android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else 0
                    mx.writeSampleData(
                        videoTrackIndex,
                        java.nio.ByteBuffer.wrap(buffer, 0, bufferInfo.size),
                        bufferInfo
                    )
                    vEx.advance()
                    // 进度回调
                    if (totalDuration > 0) {
                        val progress = (bufferInfo.presentationTimeUs * 100 / totalDuration).toInt()
                            .coerceIn(0, 99)
                        if (progress != lastProgress) {
                            listener.onProgress(progress)
                            lastProgress = progress
                        }
                    }
                }
            }
            // 写入音频
            var audioDone = false
            while (!audioDone) {
                bufferInfo.offset = 0
                bufferInfo.size = aEx.readSampleData(java.nio.ByteBuffer.wrap(buffer), 0)
                if (bufferInfo.size < 0) {
                    audioDone = true
                    bufferInfo.size = 0
                } else {
                    bufferInfo.presentationTimeUs = aEx.sampleTime
                    bufferInfo.flags = 0 // 音频一般不需要关键帧标志
                    mx.writeSampleData(
                        audioTrackIndex,
                        java.nio.ByteBuffer.wrap(buffer, 0, bufferInfo.size),
                        bufferInfo
                    )
                    aEx.advance()
                    // 进度回调
                    if (totalDuration > 0) {
                        val progress = (bufferInfo.presentationTimeUs * 100 / totalDuration).toInt()
                            .coerceIn(0, 99)
                        if (progress != lastProgress) {
                            listener.onProgress(progress)
                            lastProgress = progress
                        }
                    }
                }
            }
            // 收尾：**先 stop 落盘成功，再报 100% / 完成**
            mx.stop()
            muxerStarted = false
            listener.onProgress(100)
            listener.onComplete()
        } catch (e: Exception) {
            // 半成品不能留（否则会被当成"下好了"的文件）
            runCatching { File(outputPath).delete() }
            throw e
        } finally {
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
        }
    }
}


object MediaProcessorFactory {
    enum class ProcessorType { FFMPEG, EXTRACTOR }

    fun create(type: ProcessorType): IMediaProcessor = when (type) {
        ProcessorType.FFMPEG -> FFmpegMediaProcessor()
        ProcessorType.EXTRACTOR -> MediaExtractorProcessor()
    }
}
