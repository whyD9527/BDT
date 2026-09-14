package com.imcys.bilibilias.data.download.segmented

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference

/**
 * 分片下载的结果。
 *
 * 调用方（`DownloadExecutor`）只需区分"成功"与"没成功"：
 * 没成功就回落现有的单连接逻辑，**保证不比改造前差**。
 */
sealed interface SegmentedDownloadResult {

    /** 所有片都下完，临时文件内容完整 */
    data object Success : SegmentedDownloadResult

    /** 本次不满足分片条件（文件太小 / 长度未知 / 开关关 / 服务端不支持），**没有改动任何文件** */
    data class NotApplicable(val reason: String) : SegmentedDownloadResult

    /** 分片失败。边车元数据已尽力落盘，调用方可据此整理临时文件后回落单连接 */
    data class Failed(val reason: String) : SegmentedDownloadResult
}

/**
 * 多线程分片下载器：把一个文件的下载切成 N 片**并发**拉取，各自写到自己那段的偏移上。
 *
 * ## 为什么不改上层
 * 上层调用链、状态机、Room、FFmpeg 合并**全都不动**。本类只负责"把一个 URL 变成本地文件"，
 * 对外语义与 `DownloadExecutor.performDownload` 一致：成功时临时文件内容完整，
 * 失败时留下可供下次续传的现场。
 *
 * ## 三条保命规则（都是"写坏文件"级别的）
 * 1. **只在 206 上写**：发了 `Range` 却收到 200，说明服务端忽略 Range 把整个文件发来了 ——
 *    此时若按片偏移写下去，文件会被彻底写乱。这种情况直接放弃分片。
 * 2. **核对 `Content-Range` 起点**：响应体必须确实是我们请求的那一段。起点对不上就不写。
 * 3. **写多少算多少**：Range 的结尾也可能被服务端忽略（发多了），所以**写入严格截断在片尾**；
 *    反过来，body 被截断（发少了）绝不能算成功，要重试该片。
 *
 * ## 进度
 * 总进度 = Σ 各片已落盘字节 / 总长度，只增不减、夹在 0..1（沿用原有 1% 节流）。
 *
 * ## 取消
 * 各片协程都挂在调用方的作用域下，**取消即全部停止**（暂停/取消任务因此天然生效）；
 * 取消时会把各片进度写入边车文件，供下次续传。
 *
 * ## 为什么"放弃分片"不走异常传递给兄弟协程
 * 让子协程抛异常去中止同级协程，会经过结构化并发的取消转换 —— 在处理位置看到的
 * 可能是 `CancellationException` 而不是原始异常，于是**协议不支持被误判成"用户取消"**，
 * 把一次普通的下载失败变成任务被取消。这里改成**共享中止标志**：
 * 发现问题的那片把原因记进 [AtomicReference]，其余片在读写循环里主动退出，
 * 全程**没有任何异常离开子协程**，也不影响外部取消（那个仍然是真正的 `CancellationException`）。
 *
 * @param maxPartAttempts 单片的尝试次数（失败只重试该片，不像单连接那样整文件重来）
 * @param retryDelayMs 单片重试间隔
 * @param logger 日志出口。**刻意不直接用 `android.util.Log`** —— 那样本模块的单测
 *        （纯 JVM）会因为 "not mocked" 直接抛异常，反而逼着把逻辑挪出可测范围。
 */
class SegmentedDownloader(
    private val httpClient: HttpClient,
    private val maxPartAttempts: Int = DEFAULT_MAX_PART_ATTEMPTS,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    private val logger: (String) -> Unit = {},
) {

    suspend fun download(
        url: String,
        tempFile: File,
        referer: String,
        totalLength: Long,
        acceptRanges: Boolean?,
        concurrency: Int = SegmentedDownloadPlan.DEFAULT_CONCURRENCY,
        enabled: Boolean = true,
        onProgress: (Float) -> Unit,
    ): SegmentedDownloadResult = withContext(Dispatchers.IO) {
        if (!SegmentedDownloadPlan.shouldSegment(totalLength, acceptRanges, enabled, concurrency)) {
            return@withContext SegmentedDownloadResult.NotApplicable(
                "不满足分片条件（长度=$totalLength, Accept-Ranges=$acceptRanges, " +
                    "并发=$concurrency, 开关=$enabled）"
            )
        }

        val plan = SegmentedDownloadPlan.plan(totalLength, concurrency)
        if (plan.size < 2) {
            return@withContext SegmentedDownloadResult.NotApplicable("只能切出 ${plan.size} 片，不值当")
        }

        val metaFile = metaFileFor(tempFile)
        // 必须先建目录：RandomAccessFile 不会自己造父目录。
        // 建不出来也不在这里炸：后面开文件自然会失败 → 走"分片失败 → 回落单连接"的老路。
        runCatching { tempFile.parentFile?.mkdirs() }

        val parts = resolveParts(plan, tempFile, readMeta(metaFile))
        val tracker = ProgressTracker(plan.size, totalLength, onProgress)
        parts.forEach { tracker.setInitial(it.segment.index, it.alreadyDownloaded) }

        logger(
            "分片下载开始: ${tempFile.name}  ${plan.size} 片 / 共 $totalLength 字节" +
                "  续传=${parts.count { it.alreadyDownloaded > 0 }}/${parts.size} 片" +
                "  已完成=${parts.count { it.isComplete }} 片"
        )

        // 起手先把"每片已下多少"落盘：万一进程之后被杀，下次还能靠它对上号。
        // 写不出来也只是丢掉续传能力（下次从 0 重下），**绝不能因此让下载失败**。
        saveMetaQuietly(metaFile, plan, tracker.snapshot())

        // 非空 = 协议层面根本不能用分片（重试无意义）
        val abortSignal = AtomicReference<String?>(null)

        val failureReason: String? = try {
            val results = coroutineScope {
                parts.filter { !it.isComplete }
                    .map { part ->
                        async {
                            downloadPartWithRetry(
                                segment = part.segment,
                                url = url,
                                tempFile = tempFile,
                                referer = referer,
                                tracker = tracker,
                                abortSignal = abortSignal,
                            )
                        }
                    }
                    .awaitAll()
            }
            if (results.any { !it }) {
                abortSignal.get() ?: "有分片未能完成（已重试 $maxPartAttempts 次）"
            } else {
                null
            }
        } catch (e: CancellationException) {
            // 暂停/取消：保存现场后**原样抛出**，不能变成"下载失败"
            withContext(NonCancellable) { saveMetaQuietly(metaFile, plan, tracker.snapshot()) }
            throw e
        } catch (e: Exception) {
            "分片下载异常：${e.javaClass.simpleName}: ${e.message}"
        }

        val reason: String = failureReason ?: run {
            val actualLength = tempFile.length()
            if (actualLength >= totalLength) {
                // 比目标长只可能来自上一次遗留的尾巴，必须裁掉，否则合并/播放会拿到多余字节
                if (actualLength > totalLength) {
                    runCatching {
                        RandomAccessFile(tempFile, "rw").use { it.setLength(totalLength) }
                    }
                }
                metaFile.delete()
                onProgress(1f)
                logger("分片下载完成: ${tempFile.name}  $totalLength 字节")
                return@withContext SegmentedDownloadResult.Success
            }
            "分片全部返回成功，但文件长度不足（$actualLength < $totalLength）"
        }

        saveMetaQuietly(metaFile, plan, tracker.snapshot())
        logger("分片下载失败，将回落单连接: ${tempFile.name}  原因=$reason")
        SegmentedDownloadResult.Failed(reason)
    }

    // ------------------------------------------------------------------ 单片

    /**
     * 重试单片，直到写满或尝试次数用尽。
     *
     * 返回 false 表示这片没下完（**不向同级协程抛异常**，见类注释）。
     */
    private suspend fun downloadPartWithRetry(
        segment: Segment,
        url: String,
        tempFile: File,
        referer: String,
        tracker: ProgressTracker,
        abortSignal: AtomicReference<String?>,
    ): Boolean {
        repeat(maxPartAttempts) { attempt ->
            if (abortSignal.get() != null) return false

            // 起点始终由 tracker 推导：重试时接着已成功落盘的字节继续，绝不复写/少写。
            // （正因为如此，第一次尝试的 `part.writeOffset` 不在这里用，避免重试时用旧偏移。）
            val startOffset = segment.start + tracker.downloadedOf(segment.index)
            if (startOffset > segment.endInclusive) return true

            try {
                fetchRange(segment, startOffset, url, tempFile, referer, tracker, abortSignal)
                return true
            } catch (e: SegmentedAbortException) {
                // 协议不支持：记下原因让别的片也尽快收手，但**不抛出**
                abortSignal.compareAndSet(null, e.reason)
                return false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger(
                    "分片 ${segment.index} 第 ${attempt + 1}/$maxPartAttempts 次失败: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
            }
            if (attempt < maxPartAttempts - 1 && retryDelayMs > 0) delay(retryDelayMs)
        }
        return false
    }

    /**
     * 拉取 `[startOffset, segment.endInclusive]` 并写到文件的绝对偏移上。
     *
     * 写满这一段就算成功；body 提前结束（服务端截断/连接断）会抛异常 → 由调用方重试该片。
     */
    private suspend fun fetchRange(
        segment: Segment,
        startOffset: Long,
        url: String,
        tempFile: File,
        referer: String,
        tracker: ProgressTracker,
        abortSignal: AtomicReference<String?>,
    ) {
        httpClient.prepareGet(url) {
            header("Referer", referer)
            header(HttpHeaders.Range, "bytes=$startOffset-${segment.endInclusive}")
        }.execute { response ->
            // 规则 1：只认 206。200 表示服务端忽略了 Range，拿到的是整个文件
            if (response.status != HttpStatusCode.PartialContent) {
                throw SegmentedAbortException(
                    "服务端未按 Range 返回分片（HTTP ${response.status.value} ${response.status.description}）"
                )
            }

            // 规则 2：回的必须是我们请求的那一段起点
            val contentRange = ContentRange.parse(response.headers[HttpHeaders.ContentRange])
            if (contentRange != null && contentRange.start != startOffset) {
                throw SegmentedAbortException(
                    "服务端返回的 Content-Range 起点(${contentRange.start}) 与请求($startOffset) 不符，拒绝写入"
                )
            }

            val channel = response.bodyAsChannel()
            val buffer = ByteArray(BUFFER_SIZE)

            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.seek(startOffset)
                var position = startOffset
                while (position <= segment.endInclusive) {
                    // 别的片已经判定协议不支持 → 立刻收手，别把整个文件再拉一遍
                    val abort = abortSignal.get()
                    if (abort != null) throw SegmentedAbortException(abort)

                    // 规则 3：写入严格截断在片尾 —— 服务端多发的那部分属于下一片，不能写进来
                    val want =
                        minOf(BUFFER_SIZE.toLong(), segment.endInclusive - position + 1).toInt()
                    val read = channel.readAvailable(buffer, 0, want)
                    if (read <= 0) break
                    raf.write(buffer, 0, read)
                    position += read
                    tracker.add(segment.index, read.toLong())
                }
                if (position <= segment.endInclusive) {
                    throw IncompletePartException(
                        "分片 ${segment.index} 数据不完整：只写到 $position，应为 ${segment.endInclusive}"
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ 元数据

    /**
     * 算出本次每片从哪写起。
     *
     * 多了一道"文件长度"校验：边车说某片下了 N 字节，但文件根本没有那么长
     * （被清理、被截断）时，**只能相信文件里真实存在的那一段**，否则会算出文件外的写偏移。
     */
    private fun resolveParts(
        plan: List<Segment>,
        tempFile: File,
        meta: SegmentDownloadMeta?,
    ): List<PartResume> {
        val fileLength = if (tempFile.exists()) tempFile.length() else 0L
        return SegmentResumePlanner.resolve(plan, meta).map { part ->
            val usable = (fileLength - part.segment.start).coerceIn(0L, part.segment.length)
            if (part.alreadyDownloaded > usable) {
                logger(
                    "分片 ${part.segment.index} 的边车进度(${part.alreadyDownloaded}) " +
                        "超出文件实际长度，按 $usable 重下"
                )
                part.copy(alreadyDownloaded = usable)
            } else {
                part
            }
        }
    }

    private fun readMeta(metaFile: File): SegmentDownloadMeta? {
        if (!metaFile.exists()) return null
        return runCatching { SegmentDownloadMetaCodec.decode(metaFile.readText()) }.getOrNull()
    }

    private fun saveMetaQuietly(metaFile: File, plan: List<Segment>, downloaded: LongArray) {
        runCatching { saveMeta(metaFile, plan, downloaded) }
            .onFailure { logger("边车元数据写入失败（不影响下载本身）: ${it.message}") }
    }

    /**
     * 原子写入：先写临时文件再改名。直接覆写的话，进程在写到一半时被杀会留下
     * **半截 JSON** —— 虽然解析失败也只是退化成"从头下"，但白白丢掉全部进度。
     */
    private fun saveMeta(metaFile: File, plan: List<Segment>, downloaded: LongArray) {
        val meta = SegmentDownloadMeta(
            totalLength = plan.sumOf { it.length },
            parts = plan.map { segment ->
                SegmentDownloadMeta.PartProgress(
                    index = segment.index,
                    start = segment.start,
                    endInclusive = segment.endInclusive,
                    downloaded = downloaded.getOrElse(segment.index) { 0L }
                        .coerceIn(0L, segment.length),
                )
            },
        )
        val text = SegmentDownloadMetaCodec.encode(meta)
        val tmp = File("${metaFile.path}.tmp")
        metaFile.parentFile?.mkdirs()
        tmp.writeText(text)
        if (!tmp.renameTo(metaFile)) {
            // 某些文件系统上 rename 到已存在的目标会失败：退化成直接写
            metaFile.writeText(text)
            tmp.delete()
        }
    }

    // ------------------------------------------------------------------ 进度

    /**
     * 并发进度聚合。各片用原子计数累加，只有跨过 1% 才回调一次。
     *
     * 单调性来自"计数只增"：不会出现进度回退（现有单连接实现也是这个语义）。
     */
    private class ProgressTracker(
        partCount: Int,
        private val totalLength: Long,
        private val onProgress: (Float) -> Unit,
    ) {
        private val downloaded = AtomicLongArray(partCount)
        private var lastReported = 0f
        private val emitLock = Any()

        fun setInitial(index: Int, value: Long) {
            downloaded.set(index, value)
        }

        fun downloadedOf(index: Int): Long = downloaded.get(index)

        fun add(index: Int, delta: Long) {
            if (delta <= 0) return
            downloaded.addAndGet(index, delta)
            val current = SegmentedDownloadPlan.progressOf(sum(), totalLength)
            synchronized(emitLock) {
                if (current - lastReported >= PROGRESS_STEP) {
                    lastReported = current
                    onProgress(current)
                }
            }
        }

        fun snapshot(): LongArray = LongArray(downloaded.length()) { downloaded.get(it) }

        private fun sum(): Long {
            var total = 0L
            for (i in 0 until downloaded.length()) total += downloaded.get(i)
            return total
        }
    }

    companion object {
        const val DEFAULT_MAX_PART_ATTEMPTS: Int = 3
        const val DEFAULT_RETRY_DELAY_MS: Long = 3_000L
        private const val BUFFER_SIZE: Int = 64 * 1024
        private const val PROGRESS_STEP: Float = 0.01f

        /** 边车元数据文件：与现有 `.downloading` 临时文件同目录、同前缀，便于一起清理 */
        fun metaFileFor(tempFile: File): File = File("${tempFile.path}.downloadpart")
    }
}

/** 分片在协议层面不可用（服务端忽略 Range / 回的段不对）—— 重试无意义，直接回落 */
private class SegmentedAbortException(val reason: String) : Exception(reason)

/** 本片数据没收全（body 被截断）—— 可重试 */
private class IncompletePartException(message: String) : Exception(message)
