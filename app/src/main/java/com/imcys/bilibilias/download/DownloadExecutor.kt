package com.imcys.bilibilias.download

import android.util.Log
import com.imcys.bilibilias.data.download.cancel.DownloadCancellationRules
import com.imcys.bilibilias.data.download.resume.SingleConnectionResumeRules
import com.imcys.bilibilias.data.download.segmented.ContentRange
import com.imcys.bilibilias.data.download.segmented.RemoteFileProbe
import com.imcys.bilibilias.data.download.segmented.SegmentedDownloadPlan
import com.imcys.bilibilias.data.download.segmented.SegmentedDownloadResult
import com.imcys.bilibilias.data.download.segmented.SegmentedDownloader
import com.imcys.bilibilias.data.download.segmented.SegmentedTempFileReconciler
import com.imcys.bilibilias.data.repository.AppSettingsRepository
import com.imcys.bilibilias.network.config.RequestRules
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 下载执行器
 * 负责执行HTTP下载，支持断点续传和重试
 */
class DownloadExecutor(
    private val httpClient: HttpClient,
    private val appSettingsRepository: AppSettingsRepository
) {
    /** HEAD 探测远端长度与是否支持 Range（分片下载要用） */
    private val remoteFileProbe = RemoteFileProbe(httpClient)

    /** 多线程分片下载器。日志接到本项目长期保留的 ASDownload 取证行上 */
    private val segmentedDownloader = SegmentedDownloader(
        httpClient = httpClient,
        logger = { Log.i(TAG, it) },
    )

    companion object {
        const val TAG = "ASDownload"
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 3000L
        private const val DOWNLOAD_BUFFER_SIZE : Long = 64 * 1024
    }

    /**
     * 下载文件到指定路径
     * @param downloadUrl 下载URL
     * @param savePath 保存路径
     * @param referer Referer头
     * @param onProgress 进度回调 (0.0 - 1.0)
     * @return 是否下载成功
     */
    suspend fun downloadFile(
        downloadUrl: String,
        savePath: String,
        referer: String,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val file = File(savePath)
        val tempFile = File("$savePath.downloading")
        val metaFile = SegmentedDownloader.metaFileFor(tempFile)

        // 检查文件是否已完整下载
        val remote = remoteFileProbe.probe(downloadUrl, referer)
        val remoteLength = remote.length
        if (remoteLength > 0 && file.exists() && file.length() >= remoteLength) {
            onProgress(1f)
            return@withContext true
        }

        // ---- 第 2 步：多线程分片下载（第 3 步起由设置项控制，默认开、并发 4）----
        // 只在"够大 + 服务端能用 Range"时启用；**任何**不顺利都回落到下面原有的单连接逻辑，
        // 所以最坏情况与改造前完全一致。
        //
        // 两个设置值**读一次**、本次下载全程复用：
        // 中途改设置不该影响正在跑的任务；更要紧的是回落整理必须用**同一个**分片计划，
        // 否则算出来的"连续前缀"会和实际落的盘对不上。
        val segmentedEnabled = appSettingsRepository.isSegmentedDownloadEnabled()
        val segmentedConcurrency = appSettingsRepository.getSegmentedDownloadConcurrency()
        val segmentedUrl = replaceCdn(downloadUrl)
        Log.i(
            TAG,
            "开始下载: ${tempFile.name}  CDN主机=${
                segmentedUrl.substringAfter("//").substringBefore("/")
            }"
        )
        val segmentedResult = segmentedDownloader.download(
            url = segmentedUrl,
            tempFile = tempFile,
            referer = referer,
            totalLength = remoteLength,
            acceptRanges = remote.acceptRanges,
            concurrency = segmentedConcurrency,
            enabled = segmentedEnabled,
            onProgress = onProgress,
        )
        if (segmentedResult is SegmentedDownloadResult.Success) {
            if (file.exists()) file.delete()
            if (!tempFile.renameTo(file)) {
                Log.e(TAG, "分片下载完成后重命名失败: ${tempFile.name}")
                return@withContext false
            }
            return@withContext true
        }

        // 分片留下的临时文件**不能直接交给单连接**：单连接是按「文件长度」判断已下载量的，
        // 而分片是各片乱序 seek 到绝对偏移写的（文件长度可能已经满了，中间却还有洞）。
        // 这里按边车把文件截断到「从 0 起真正连续的字节数」，否则单连接会只补后半段，
        // 产出一个长度完全正确、内容却错位的坏文件。
        if (segmentedResult is SegmentedDownloadResult.Failed || metaFile.exists()) {
            val plan = SegmentedDownloadPlan.plan(remoteLength, segmentedConcurrency)
            val resumeFrom = SegmentedTempFileReconciler.reconcile(tempFile, metaFile, plan)
            Log.i(TAG, "分片未完成，回落单连接: ${file.name}  可续传位置=$resumeFrom 字节")
        } else if (segmentedResult is SegmentedDownloadResult.NotApplicable) {
            Log.d(TAG, "本次走单连接: ${file.name}  原因=${segmentedResult.reason}")
        }

        // 重试下载
        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            try {
                val success = performDownload(segmentedUrl, tempFile, referer, onProgress)
                if (success) {
                    if (file.exists()) file.delete()
                    tempFile.renameTo(file)
                    return@withContext true
                }
                Log.w(TAG, "第 ${attempt + 1}/$MAX_RETRY_ATTEMPTS 次下载未成功: ${file.name}")
            } catch (e: Exception) {
                // ⚠️ 取消（暂停/取消任务）**不是失败**：原样抛出去，别吞、别重试。
                // 原先这里一律吞掉，于是用户点暂停会白重试 5 次、最后被上层记成
                // "文件下载失败（已重试 5 次）" → 任务变 ERROR 并弹「下载失败」。见第十八轮审查。
                if (!DownloadCancellationRules.isRetryableFailure(e)) throw e
                Log.e(TAG, "第 ${attempt + 1}/$MAX_RETRY_ATTEMPTS 次下载异常: ${file.name}", e)
            }
            if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                delay(RETRY_DELAY_MS)
            }
        }
        Log.e(
            TAG,
            "下载最终失败（已重试 $MAX_RETRY_ATTEMPTS 次）: ${file.name}  CDN主机=${
                segmentedUrl.substringAfter("//").substringBefore("/")
            }"
        )
        false
    }

    private suspend fun performDownload(
        finalUrl: String,
        tempFile: File,
        referer: String,
        onProgress: (Float) -> Unit,
        progressStep: Float = 0.01f
    ): Boolean = withContext(Dispatchers.IO) {
        val downloaded = if (tempFile.exists()) tempFile.length() else 0L
        // 注意：CDN 线路替换与那行 "开始下载: … CDN主机=…" 取证日志已上移到 downloadFile，
        // 保证**一次下载只记一行**（分片与单连接共用同一行，日志口径不变）。
        var lastProgress = 0f

        try {
            httpClient.prepareGet(finalUrl) {
                header("Referer", referer)
                if (downloaded > 0) header("Range", "bytes=$downloaded-")
            }.execute { response ->
                // 关键：原来不检查状态码，403/404 会把错误页当正文写进文件并「下载成功」。
                // 这里显式拦截并记录状态码与主机，历史上 CDN 返回 403 就是这么被吞掉的。
                if (!response.status.isSuccess()) {
                    Log.e(
                        TAG,
                        "下载被拒绝: HTTP ${response.status.value} ${response.status.description}" +
                            "  CDN主机=${finalUrl.substringAfter("//").substringBefore("/")}" +
                            "  文件=${tempFile.name}"
                    )
                    return@execute false
                }

                tempFile.parentFile?.mkdirs()
                val channel = response.bodyAsChannel()

                // ⚠️ 能不能"接着写"必须先问清楚：服务端可能**忽略 Range、回 200 + 整份文件**，
                // 这时按追加写就会把整份内容接到半截文件后面 —— 长度翻倍、内容错位的坏文件，
                // 而且照样报"下载成功"（分片路径早就防了这条，单连接一直没防）。
                val writeMode = SingleConnectionResumeRules.writeMode(
                    requestedRangeFrom = downloaded,
                    statusIsPartialContent = response.status == HttpStatusCode.PartialContent,
                    contentRangeStart = ContentRange
                        .parse(response.headers[HttpHeaders.ContentRange])?.start,
                )
                val append = writeMode == SingleConnectionResumeRules.WriteMode.APPEND_TO_EXISTING
                if (downloaded > 0 && !append) {
                    Log.w(
                        TAG,
                        "服务端没按 Range 回（HTTP ${response.status.value}）→ " +
                            "丢弃已下的 $downloaded 字节、从头重下: ${tempFile.name}"
                    )
                }
                // 从头写时，"已下载"要从 0 起算，否则进度会从半截开始
                val startBytes = if (append) downloaded else 0L

                val contentLength = response.contentLength()
                val totalLength = if (contentLength != null && contentLength > 0) {
                    contentLength + startBytes
                } else {
                    -1L
                }

                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE.toInt())
                var downloadedBytes = startBytes

                FileOutputStream(tempFile, append).use { output ->
                    while (!channel.exhausted()) {
                        val bytesRead = channel.readAvailable(buffer)
                        if (bytesRead == -1) break

                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val currentProgress = if (totalLength > 0) {
                            (downloadedBytes.toFloat() / totalLength.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }

                        // 只在进度变化超过指定步长时回调
                        if (currentProgress - lastProgress >= progressStep) {
                            lastProgress = currentProgress
                            onProgress(currentProgress)
                        }
                    }
                }

                onProgress(1f)
                true
            }
        } catch (e: Exception) {
            // 同上：取消要原样抛出，不能变成"读写异常 → false → 下载失败"
            if (!DownloadCancellationRules.isRetryableFailure(e)) throw e
            Log.e(TAG, "下载流读写异常: ${tempFile.name}", e)
            false
        }
    }

    /**
     * 替换CDN
     */
    private suspend fun replaceCdn(downloadUrl: String): String {
        val lineHost = appSettingsRepository.appSettingsFlow.first().biliLineHost ?: ""
        // 主机模式来自 RequestRules（同一类"媒体主机知识"只留一处）
        val uposRegex = RequestRules.UPOS_CDN_HOST_REGEX
        return if (uposRegex.containsMatchIn(downloadUrl) && lineHost.isNotEmpty()) {
            downloadUrl.replace(uposRegex, lineHost)
        } else {
            downloadUrl
        }
    }
}
