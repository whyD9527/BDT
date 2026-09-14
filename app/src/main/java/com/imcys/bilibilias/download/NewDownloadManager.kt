package com.imcys.bilibilias.download

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.imcys.bilibilias.common.event.sendToastEvent
import com.imcys.bilibilias.common.utils.download.DanmakuXmlUtil
import com.imcys.bilibilias.common.utils.toHttps
import com.imcys.bilibilias.data.download.cache.EmbedCacheRules
import com.imcys.bilibilias.data.download.merge.DownloadSuccessorRules
import com.imcys.bilibilias.data.download.predecessor.DownloadPredecessorRules
import com.imcys.bilibilias.data.download.queue.DownloadQueueRules
import com.imcys.bilibilias.data.download.segmented.SegmentedDownloader
import com.imcys.bilibilias.data.download.startup.DownloadStartupRules
import com.imcys.bilibilias.data.model.download.DownloadSubTask
import com.imcys.bilibilias.data.model.download.DownloadTaskTree
import com.imcys.bilibilias.data.model.download.DownloadTreeNode
import com.imcys.bilibilias.data.model.download.DownloadViewInfo
import com.imcys.bilibilias.data.model.download.MediaContainerConfig
import com.imcys.bilibilias.data.util.readOnce
import com.imcys.bilibilias.data.model.video.ASLinkResultType
import com.imcys.bilibilias.data.repository.AppSettingsRepository
import com.imcys.bilibilias.data.repository.DownloadTaskRepository
import com.imcys.bilibilias.data.repository.VideoInfoRepository
import com.imcys.bilibilias.database.entity.download.DownloadMode
import com.imcys.bilibilias.database.entity.download.DownloadSegment
import com.imcys.bilibilias.database.entity.download.DownloadStage
import com.imcys.bilibilias.database.entity.download.DownloadState
import com.imcys.bilibilias.database.entity.download.DownloadSubTaskType
import com.imcys.bilibilias.database.entity.download.DownloadTaskNodeType
import com.imcys.bilibilias.database.entity.download.DownloadTaskType
import com.imcys.bilibilias.database.entity.download.NamingConventionInfo
import com.imcys.bilibilias.download.service.DownloadService
import com.imcys.bilibilias.network.model.video.BILIVideoDash
import com.imcys.bilibilias.network.model.video.BILIVideoDurl
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 新的下载管理器 - 使用重构后的组件
 */
class NewDownloadManager(
    private val context: Application,
    private val downloadTaskRepository: DownloadTaskRepository,
    private val videoInfoRepository: VideoInfoRepository,
    private val httpClient: HttpClient,
    private val okHttpClient: OkHttpClient,
    private val appSettingsRepository: AppSettingsRepository,
    // 重构后的组件
    private val videoInfoFetcher: VideoInfoFetcher,
    private val fileOutputManager: FileOutputManager,
    private val downloadExecutor: DownloadExecutor,
    private val ffmpegMerger: FfmpegMerger,
    private val namingConventionHandler: NamingConventionHandler,
    private val subtitleDownloader: SubtitleDownloader
) {
    companion object {
        const val TAG = "ASDownload"
        private const val MAX_CONCURRENT_DOWNLOADS = 1
        private const val QUEUE_CHECK_INTERVAL_MS = 1000L

        suspend fun buildRefererUrl(downloadTaskRepository: DownloadTaskRepository, task: AppDownloadTask): String {
            return when (task.downloadTask.type) {
                DownloadTaskType.BILI_VIDEO,
                DownloadTaskType.BILI_DONGHUA -> {
                    val platformId = task.downloadTask.platformId
                    if (platformId.all { it.isDigit() }) {
                        "https://www.bilibili.com/bangumi/play/ss$platformId"
                    } else {
                        "https://www.bilibili.com/video/$platformId"
                    }
                }

                DownloadTaskType.BILI_VIDEO_SECTION if task.downloadSegment.taskId != 0L -> {
                    val realTask = downloadTaskRepository.getTaskById(task.downloadSegment.taskId ?: 0L)
                    "https://www.bilibili.com/video/${realTask?.platformId}"
                }

                DownloadTaskType.BILI_VIDEO_SECTION -> error("构造Referer URL失败")
            }
        }
    }

    private val _downloadTasks = MutableStateFlow<List<AppDownloadTask>>(emptyList())
    private var isInit = false
    private var isDownloading = false
    private val activeDownloadJobs = ConcurrentHashMap<Long, Job>()

    /** 保护「挑任务 + 占位」这段临界区（见 checkAndStartNextDownload）：不加锁会出现同一 segmentId 被起两个 Job */
    private val queueLock = Any()
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 单个下载子任务（视频/音频）的结果。
     * 之所以要带上 [failureReason]，是因为原先失败原因只存在于一个被
     * `printStackTrace()` 吞掉的异常里，用户在界面上只能看到一个「错误」，
     * 无从判断是网络、鉴权还是存储问题。
     */
    private data class SubTaskResult(
        val success: Boolean,
        val quality: String? = null,
        val failureReason: String? = null,
    )

    /**
     * 统一下载失败处理：写日志 + 弹提示 + 置为错误态。
     * 保证任何一条失败路径都不会再「静默变红」。
     */
    private fun failTask(task: AppDownloadTask, reason: String, error: Throwable? = null) {
        Log.e(
            TAG,
            "下载失败 platformId=${task.downloadSegment.platformId} " +
                "mode=${task.downloadSegment.downloadMode} 原因=$reason",
            error
        )
        updateTaskState(task, DownloadState.ERROR)
        downloadScope.launch {
            // ⚠️ 失败态**也要落盘**。原先只改内存态，于是 DB 里那行永远停在 WAITING：
            // ①「DB 与界面分裂」（界面显示错误、DB 却说没失败过）；
            // ② 重启清理是按 DB 状态判断的，会把这条失败记录当成"被中断"而**保留**它的临时文件 ——
            //    "失败任务的 .downloading/.downloadpart 永不清理"那条就是这么来的。
            runCatching {
                downloadTaskRepository.updateSegment(
                    task.downloadSegment.copy(downloadState = DownloadState.ERROR)
                )
            }
            runCatching { sendToastEvent("下载失败：$reason") }
        }
    }

    private var downloadService: DownloadService? = null
    private val downloadConn = object : ServiceConnection {
        @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
        override fun onServiceConnected(p0: ComponentName?, iBinder: IBinder?) {
            val binder = iBinder as DownloadService.DownloadBinder
            downloadService = binder.service
            downloadScope.launch {
                binder.service?.let { startDownloadQueue(it) }
            }
        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            downloadService = null
        }
    }

    /**
     * 启动时收拾上次没下完的记录。
     *
     * 原先这里只做 `if (state !in listOf(PAUSE, COMPLETED)) deleteSegment(...)`，
     * 于是：PAUSE 行留在 DB 里却谁都看不到、恢复不了（幽灵）；WAITING/ERROR 被**静默**删掉；
     * 失败任务的 `.downloading` / `.downloadpart` 永远留在磁盘上。
     *
     * 现在每条记录的处理方式由 `DownloadStartupRules` 决定（有单测）：
     * 只有 COMPLETED 保留；其余一律丢记录（不再有幽灵），其中**已经终结**的
     * （失败/取消）连临时文件一起清掉，**被中断**的（暂停/排队/下载中…）保留文件
     * 以便下次添加同一内容时接着下。丢弃过东西就告诉用户一声，不再静默。
     */
    suspend fun initDownloadList() {
        if (isInit) return
        isInit = true

        // ⚠️ 必须 `readOnce()`（= first()），**不能** `.last()`：
        // getSegmentAll() 是 Room 的热流、永远不会 complete，而 last() 要等流结束 ——
        // 于是这段清理**一次都没执行过**（第二十四轮真机验证：重启后没有任何清理日志）。
        val segments = downloadTaskRepository.getSegmentAll().readOnce()
        var discarded = 0
        var cleanedFiles = 0

        segments.forEach { segment ->
            when (DownloadStartupRules.actionFor(segment.downloadState)) {
                DownloadStartupRules.StartupAction.KEEP -> Unit

                DownloadStartupRules.StartupAction.DISCARD_KEEP_FILES -> {
                    discarded++
                    downloadTaskRepository.deleteSegment(segment.segmentId)
                }

                DownloadStartupRules.StartupAction.DISCARD_AND_CLEAN -> {
                    cleanedFiles += cleanTempFilesOf(segment)
                    discarded++
                    downloadTaskRepository.deleteSegment(segment.segmentId)
                }
            }
        }

        if (discarded > 0) {
            Log.d(TAG, "启动清理: 丢弃 $discarded 条未完成记录，删除 $cleanedFiles 个临时文件")
            runCatching { sendToastEvent("已清理 $discarded 个上次未完成的下载") }
        }

        sweepEmbedCacheDirs()
    }

    /**
     * 清掉应用缓存里残留的内嵌封面 / 内嵌字幕。
     *
     * 这两类临时件只在"前置任务 → 合并"这段时间里有意义，合并完就没用了。
     * 它们原先**只有**「存储管理 → 清空缓存」会删，于是下一集、下一集地攒，
     * 用户也不知道该去清（第十八轮审查的低危项）。
     *
     * 放在启动时扫：这时还没有任何合并在进行，整目录都是上次会话的残留。
     * 正在下、被中断的那些临时件由 [DownloadStartupRules] 按状态处理，
     * 与这里扫的目录不同（那些在 `files/video` / `files/audio`）。
     */
    private fun sweepEmbedCacheDirs() {
        var deleted = 0
        EmbedCacheRules.dirNames.forEach { dirName ->
            val dir = File(context.externalCacheDir, dirName)
            dir.listFiles()?.forEach { file ->
                if (EmbedCacheRules.isEmbedCacheFile(dirName, file.name) && file.delete()) {
                    deleted++
                }
            }
        }
        if (deleted > 0) {
            Log.d(TAG, "启动清理: 删除 $deleted 个内嵌封面/字幕临时文件")
        }
    }

    /**
     * 删掉某个任务写出来的内嵌封面 / 内嵌字幕临时文件。
     *
     * 只认领 `TaskRuntimeInfo` 里记着的**本任务自己的**路径，不做任何模式匹配 ——
     * 这样即使目录里同时躺着别的任务的临时件也不会被误删。
     * 失败路径也调用它：合并已经结束了（成功或失败），留着这两份临时件没有意义。
     */
    private fun cleanupEmbedCache(task: AppDownloadTask) {
        val paths = EmbedCacheRules.taskOwnedPaths(
            coverPath = task.taskRuntimeInfo.coverPath,
            subtitlePaths = task.taskRuntimeInfo.subtitles.map { it.path },
            parentDirNameOf = { path ->
                path.substringBeforeLast('/', missingDelimiterValue = "")
                    .substringAfterLast('/', missingDelimiterValue = "")
            },
        )
        var deleted = 0
        paths.forEach { path ->
            val file = File(path)
            if (file.exists() && file.delete()) deleted++
        }
        if (deleted > 0) {
            Log.d(TAG, "清理内嵌封面/字幕临时文件: $deleted 个 platformId=" +
                "${task.downloadSegment.platformId}")
        }
    }

    /**
     * 删掉某个 segment 在私有目录里的全部中间产物（含 `.downloading` 与 `.downloadpart` 边车）。
     *
     * 用**前缀**而不是拼具体路径：子任务类型（VIDEO/AUDIO）与扩展名都可以从 DB 推出来，
     * 但推错了就会漏删；文件名的前缀 `<platformId>_` 是 `createSubTask` 定死的，一网打尽更稳。
     */
    private fun cleanTempFilesOf(segment: DownloadSegment): Int {
        var deleted = 0
        listOf("video", "audio").forEach { dirName ->
            val dir = context.getExternalFilesDir(dirName) ?: return@forEach
            dir.listFiles()?.forEach { file ->
                if (DownloadStartupRules.isTempFileOf(file.name, segment.platformId) && file.delete()) {
                    deleted++
                }
            }
        }
        return deleted
    }

    fun getAllDownloadTasks(): StateFlow<List<AppDownloadTask>> = _downloadTasks.asStateFlow()

    fun getDownloadTask(segmentId: Long): Flow<AppDownloadTask?> {
        return _downloadTasks.map { tasks ->
            tasks.find { it.downloadSegment.segmentId == segmentId }
        }
    }

    suspend fun addDownloadTask(
        asLinkResultType: ASLinkResultType,
        downloadViewInfo: DownloadViewInfo
    ) {
        val taskResult =
            downloadTaskRepository.createDownloadTask(asLinkResultType, downloadViewInfo)

        taskResult.onSuccess { taskTree ->
            processDownloadTree(taskTree, downloadViewInfo)
        }.onFailure { error ->
            throw error
        }

        // 队列没在跑就把它拉起来（统一入口，见 ensureQueueRunning 的注释）
        ensureQueueRunning()
    }

    suspend fun pauseTask(segmentId: Long) {
        val task = findTaskById(segmentId) ?: return
        if (!DownloadQueueRules.canPause(task.downloadState)) return

        cancelActiveJob(segmentId)
        updateTaskState(task, DownloadState.PAUSE)
        downloadTaskRepository.updateSegment(task.downloadSegment.copy(downloadState = DownloadState.PAUSE))
    }

    suspend fun cancelTask(segmentId: Long) {
        val task = findTaskById(segmentId) ?: return

        cancelActiveJob(segmentId)
        deleteTaskFiles(task)
        updateTaskState(task, DownloadState.CANCELLED)
        downloadTaskRepository.updateSegment(task.downloadSegment.copy(downloadState = DownloadState.CANCELLED))
        removeTaskFromList(segmentId)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    suspend fun resumeTask(segmentId: Long) {
        val task = findTaskById(segmentId) ?: return
        if (!DownloadQueueRules.canResume(task.downloadState)) return

        updateTaskState(task, DownloadState.WAITING)
        downloadTaskRepository.updateSegment(task.downloadSegment.copy(downloadState = DownloadState.WAITING))

        // ⚠️ 这里**绝不能**自己置 `isDownloading = true`。
        // 那个标志的语义是"队列循环正在跑"，只由 startDownloadQueue 设置。
        // 曾经这里自己置上之后：① 队列循环其实并不存在（暂停时它已退出并解绑服务），
        // 任务跑完没人再推进队列；② addDownloadTask 看到 isDownloading==true 就跳过启动服务 ——
        // 结果**之后新增的任务永远停在「等待中」**，并且 onDownloadFinished()（唯一会
        // stopForeground 的地方）再也不会被调用 →「缓存通知」永久常驻，只能杀进程。
        // 见交接文档第十八轮审查、第十九轮修复。
        ensureQueueRunning()
    }

    /**
     * 确保下载队列在跑：没跑就启动服务（进而启动循环），在跑就让循环下一拍捡到新任务。
     *
     * 这是"队列生命周期"的唯一入口 —— `addDownloadTask` 与 `resumeTask` 都走它，
     * 免得再出现"标志是 true、但循环并不存在"这种自相矛盾的状态。
     */
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun ensureQueueRunning() {
        if (isDownloading) {
            // 循环在跑：立刻叫一次，省掉最多 1 秒等待（循环自己也会捡到）
            checkAndStartNextDownload()
        } else {
            startDownloadQueueService()
        }
    }

    suspend fun pauseAllTasks() {
        val downloadingTasks = _downloadTasks.value.filter {
            it.downloadState in listOf(DownloadState.DOWNLOADING, DownloadState.MERGING)
        }
        downloadingTasks.forEach { pauseTask(it.downloadSegment.segmentId) }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    suspend fun resumeAllTasks() {
        val pausedTasks = _downloadTasks.value.filter { it.downloadState == DownloadState.PAUSE }
        pausedTasks.forEach { resumeTask(it.downloadSegment.segmentId) }
    }

    suspend fun downloadImageToAlbum(imageUrl: String, fileName: String, saveDirName: String) =
        withContext(Dispatchers.IO) {
            val response = okHttpClient.newCall(
                okhttp3.Request.Builder().url(imageUrl).build()
            ).execute()

            if (!response.isSuccessful) return@withContext

            val body = response.body ?: return@withContext
            val imageBytes = body.bytes()

            fileOutputManager.downloadImageToAlbum(imageBytes, fileName, saveDirName)
        }

    fun startDownloadQueueService() {
        if (isDownloading) return
        if (!isAppInForeground(context)) return

        val intent = Intent(context, DownloadService::class.java)

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        val bindResult = context.bindService(intent, downloadConn, Context.BIND_AUTO_CREATE)
        if (!bindResult) {
            downloadService?.let {
                downloadScope.launch {
                    startDownloadQueue(it)
                }
            }
        }
    }

    private fun isAppInForeground(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false
        val packageName = context.packageName
        for (appProcess in appProcesses) {
            if (appProcess.processName == packageName &&
                appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            ) {
                return true
            }
        }
        return false
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private suspend fun startDownloadQueue(downloadService: DownloadService) {
        isDownloading = true

        while (true) {
            checkAndStartNextDownload()
            delay(QUEUE_CHECK_INTERVAL_MS)

            // 收工判据抽到 DownloadQueueRules（有单测）：没有在跑的、没有等待的，
            // 且剩下的都已终结或暂停。注意 PAUSE 也算"可收工" —— 用户暂停后循环会退出并解绑服务，
            // 所以"继续"时**必须**重新把队列拉起来（见 resumeTask / ensureQueueRunning）。
            if (DownloadQueueRules.isQueueDrained(
                    items = _downloadTasks.value.map {
                        it.downloadSegment.segmentId to it.downloadState
                    },
                    activeCount = activeDownloadJobs.size,
                )
            ) {
                break
            }
        }

        downloadService.onDownloadFinished()
        runCatching { context.unbindService(downloadConn) }
        isDownloading = false
    }

    /**
     * 挑下一个该开始的任务并起 Job。
     *
     * 「检查 + 占位」必须在同一把锁里完成：`checkAndStartNextDownload` 会被队列循环（每 1 秒）、
     * 每个 Job 的 finally、以及 resume/add 三条路径并发调用，而"读 activeDownloadJobs → 挑任务 →
     * 写 activeDownloadJobs"中间隔着一次 `launch`。不加锁的话两次调用可能挑中**同一个** segmentId，
     * 于是同一个文件被两个协程同时写（产物损坏），且 `[id] = job` 会覆盖前者，
     * 导致"取消"只取消得掉后一个。见第十八轮审查。
     */
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun checkAndStartNextDownload() {
        synchronized(queueLock) {
            val snapshot = _downloadTasks.value
            val nextIndex = DownloadQueueRules.nextTaskIndex(
                items = snapshot.map { it.downloadSegment.segmentId to it.downloadState },
                activeIds = activeDownloadJobs.keys.toSet(),
                maxConcurrent = MAX_CONCURRENT_DOWNLOADS,
            ) ?: return
            val nextTask = snapshot[nextIndex]

            val job = downloadScope.launch {
                try {
                    executeTaskDownload(nextTask)
                } catch (e: CancellationException) {
                    // 协程被取消
                } catch (e: Exception) {
                    handleTaskError(nextTask, e)
                } finally {
                    activeDownloadJobs.remove(nextTask.downloadSegment.segmentId)
                    checkAndStartNextDownload()
                }
            }

            // 占位在锁内完成：下一次并发调用在锁外等待，看得到这个占位
            activeDownloadJobs[nextTask.downloadSegment.segmentId] = job
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private suspend fun executeTaskDownload(task: AppDownloadTask) {
        downloadService?.let { service ->
            // 前置任务
            handlePredecessor(task, service)


            // 下载
            var quality: String? = null
            if (task.downloadViewInfo.downloadMedia) {
                val result = downloadAppTask(task, service)
                if (!result.success) {
                    // 原来这里是 throw Exception("下载失败")，异常被 printStackTrace 吞掉，
                    // 用户在界面上只看到一个「错误」，完全不知道原因。
                    failTask(task, result.failureReason ?: "文件下载失败")
                    return
                }
                quality = result.quality
            }

            // 后置任务
            handleSuccessor(task, service, quality)

            val finalTask = findTaskById(task.downloadSegment.segmentId)
            if (finalTask?.downloadState == DownloadState.COMPLETED) {
                removeTaskFromList(task.downloadSegment.segmentId)
            }
        }
    }

    private suspend fun handlePredecessor(task: AppDownloadTask, service: DownloadService) {
        updateTaskState(task, DownloadState.PRE_TASK)

        // 下载嵌入字幕
        if (task.downloadViewInfo.embedCC) {
            val subtitles = subtitleDownloader.downloadSubtitlesForEmbed(
                task.downloadViewInfo.videoPlayerInfoV2,
                task.downloadSegment.segmentId
            )
            task.updateRuntimeInfo(task.taskRuntimeInfo.copy(subtitles = subtitles))
        }

        // 下载嵌入封面
        if (task.downloadViewInfo.embedCover) {
            // ⚠️ 封面可能是 null（有些稿件/番剧没有封面字段）。原先无条件拼 `?: ""` 去请求，
            // 而空 URL 会让 Ktor 直接抛异常 → 一路冒到任务层 → **整集下载失败**。
            // 附加内容是可选活儿，没封面就跳过（用户要的是视频，不是封面）。
            val coverUrl = task.cover?.toHttps()
            if (DownloadPredecessorRules.canFetchCover(coverUrl)) {
                val coverBytes = httpClient.get(coverUrl.orEmpty()).bodyAsBytes()
                val tempDir = File(context.externalCacheDir, "cover")
                if (!tempDir.exists()) tempDir.mkdirs()
                val tempFile = File(tempDir, "embed_cover_${task.downloadSegment.segmentId}.jpg")
                tempFile.writeBytes(coverBytes)
                task.updateRuntimeInfo(task.taskRuntimeInfo.copy(coverPath = tempFile.absolutePath))
            } else {
                Log.w(
                    TAG,
                    "没有封面地址，跳过内嵌封面 platformId=${task.downloadSegment.platformId}"
                )
            }
        }

        // 下载封面到相册
        if (task.downloadViewInfo.downloadCover) {
            downloadCoverImageForTask(task)
        }

        // 下载弹幕
        if (task.downloadViewInfo.downloadDanmaku) {
            downloadDanmakuForTask(task)
        }

        // 下载字幕文件
        if (task.downloadViewInfo.downloadCC) {
            subtitleDownloader.downloadSubtitlesToFile(
                task.downloadViewInfo.videoPlayerInfoV2,
                task.downloadSegment.title,
                task.downloadViewInfo.ccFileType
            )
        }

        updateTaskState(task, DownloadState.WAITING)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private suspend fun downloadAppTask(
        task: AppDownloadTask,
        downloadService: DownloadService
    ): SubTaskResult {
        if (task.downloadSubTasks.isEmpty()) {
            return SubTaskResult(false, failureReason = "下载子任务为空（解析结果异常）")
        }

        val progressCallback = createProgressCallback(task, downloadService, "下载阶段")

        return if (task.downloadSubTasks.size >= 2) {
            downloadMultipleSubTasks(task, progressCallback)
        } else {
            downloadSingleSubTask(task, progressCallback)
        }
    }

    private suspend fun downloadMultipleSubTasks(
        task: AppDownloadTask,
        progressCallback: (Float) -> Unit
    ): SubTaskResult = withContext(Dispatchers.IO) {
        val videoTask = task.downloadSubTasks.first()
        val audioTask = task.downloadSubTasks.last()

        var videoProgress = 0f
        var audioProgress = 0f

        val videoResult = async {
            downloadSubTask(videoTask, task, task.downloadSegment.namingConventionInfo) {
                videoProgress = it
                progressCallback((videoProgress + audioProgress) / 2f)
            }
        }

        val audioResult = async {
            downloadSubTask(audioTask, task, task.downloadSegment.namingConventionInfo) {
                audioProgress = it
                progressCallback((videoProgress + audioProgress) / 2f)
            }
        }

        val videoOutcome = videoResult.await()
        val audioOutcome = audioResult.await()

        if (videoOutcome.success && audioOutcome.success) {
            SubTaskResult(true, videoOutcome.quality ?: audioOutcome.quality)
        } else {
            val failed = listOf(videoOutcome, audioOutcome).first { !it.success }
            SubTaskResult(
                false,
                failureReason = failed.failureReason ?: "音视频下载失败"
            )
        }
    }

    private suspend fun downloadSingleSubTask(
        task: AppDownloadTask,
        progressCallback: (Float) -> Unit
    ): SubTaskResult {
        val subTask = task.downloadSubTasks.first()
        // ⚠️ 这里**只下载，不写任何状态**。
        //
        // 原先下载成功后就地把任务写成 COMPLETED（内存 ＋ DB 都写），但那时
        // ①产物还在私有目录（合并与移动都还没跑）、②savePath 指的是临时文件。
        // 后果是「DB 与界面分裂」：合并/移动一旦失败，failTask 只改内存态，
        // **DB 那行永远停在 COMPLETED** —— 完成列表里留下一条指向私有临时文件的假记录，
        // 重启后还被 initDownloadList 当"已完成"保留下来。
        // 现在 COMPLETED 只有一个入口：handleSuccessor 里**移动成功之后**那一次写入。
        val outcome = downloadSubTask(
            subTask,
            task,
            task.downloadSegment.namingConventionInfo,
            progressCallback
        )

        return outcome
    }

    private suspend fun downloadSubTask(
        subTask: DownloadSubTask,
        task: AppDownloadTask,
        namingConventionInfo: NamingConventionInfo?,
        onUpdateProgress: (Float) -> Unit
    ): SubTaskResult = withContext(Dispatchers.IO) {
        val nodeType =
            downloadTaskRepository.getTaskNodeByNodeId(task.downloadSegment.nodeId)?.nodeType
                ?: return@withContext SubTaskResult(
                    false,
                    failureReason = "无法确定下载节点类型（本地数据异常）"
                )

        val playerInfo = videoInfoFetcher.fetchVideoPlayerInfo(
            task.downloadSegment,
            nodeType,
            task.downloadViewInfo
        )

        val videoData = videoInfoFetcher.extractVideoData(playerInfo, task.downloadViewInfo)
            ?: return@withContext SubTaskResult(
                false,
                failureReason = "解析视频流失败（可能是接口变化，或该内容需要登录/大会员）"
            )

        var quality: String? = null
        val downloadUrl = videoInfoFetcher.getDownloadUrl(
            videoData,
            subTask.subTaskType,
            task.downloadViewInfo,
            namingConventionInfo,
            onQuality = { quality = it }
        ) ?: return@withContext SubTaskResult(
            false,
            failureReason = "获取下载地址失败（可能是接口变化、账号未登录或被风控）"
        )

        Log.d("downloadUrl", "下载地址: $downloadUrl -- ${subTask.subTaskType}")

        val referer = buildRefererUrl(downloadTaskRepository,task)

        val result = downloadExecutor.downloadFile(
            downloadUrl = downloadUrl,
            savePath = subTask.savePath,
            referer = referer,
            onProgress = onUpdateProgress
        )

        SubTaskResult(
            success = result,
            quality = quality,
            failureReason = if (result) null else
                "文件下载失败（已重试 5 次，多为 CDN 拒绝或网络问题）"
        )
    }

    private suspend fun handleSuccessor(
        task: AppDownloadTask,
        service: DownloadService,
        quality: String?
    ) {
        // 没有媒体要合并（用户只勾了封面/弹幕/字幕，或解析没给出媒体子任务）：
        // 附加内容已经在前置阶段写好了，直接按"已完成"收尾。
        // 原先这里**无条件**走合并，而 createTempOutputFile 会取 downloadSubTasks.first() ——
        // 空列表直接抛 NoSuchElementException，一个本来成功的任务必然变 ERROR。
        if (!DownloadSuccessorRules.needsMerge(
                downloadMedia = task.downloadViewInfo.downloadMedia,
                subTaskCount = task.downloadSubTasks.size,
            )
        ) {
            markCompletedWithoutMerge(task)
            return
        }

        val progressCallback = createProgressCallback(task, service, "合并阶段")
        val tempOutputFile = createTempOutputFile(task)

        try {
            ffmpegMerger.mergeMedia(
                task,
                subTasks = task.downloadSubTasks,
                downloadMode = task.downloadSegment.downloadMode,
                outputFile = tempOutputFile,
                subtitles = task.taskRuntimeInfo.subtitles,
                coverPath = task.taskRuntimeInfo.coverPath,
                duration = task.downloadSegment.duration,
                onProgress = progressCallback,
                task.downloadViewInfo.mediaContainerConfig,
                task.downloadSegment.namingConventionInfo,
            )
        } catch (e: Exception) {
            tempOutputFile.deleteIfExists()
            // 合并失败也要收拾内嵌临时件：合并这一步已经结束了，
            // 留下封面/字幕文件只会在缓存目录里攒着（下次重试会重新下载它们）。
            cleanupEmbedCache(task)
            throw e
        }

        val segment = downloadTaskRepository.getSegmentBySegmentId(task.downloadSegment.segmentId)
        if (segment == null) {
            // 本地数据异常：合并产物留在私有目录没意义（重新合并会换个新文件名），
            // 但**源文件必须留着** —— 它们已经下完了，重试时能跳过下载。
            val abandonedTemp = tempOutputFile.absolutePath
            DownloadSuccessorRules.filesToDeleteAfterMove(
                moveSucceeded = false,
                subTaskPaths = task.downloadSubTasks.map { it.savePath },
                tempOutputPath = abandonedTemp,
            ).forEach { File(it).deleteIfExists() }
            cleanupEmbedCache(task)
            failTask(task, "合并后找不到下载记录（本地数据库异常）")
            return
        }

        // ⚠️ 长度必须在移动**之前**读：移动成功后临时文件就没了。
        val fileSize = tempOutputFile.length()
        val mimeType =
            getMimeType(
                task.downloadSegment.downloadMode,
                task.downloadViewInfo.mediaContainerConfig
            )
        val extension = getResExtension(
            task.downloadSegment.downloadMode,
            task.downloadViewInfo.mediaContainerConfig
        )
        val lastFileName = namingConventionHandler.buildFileName(
            task.downloadSegment.namingConventionInfo,
            extension
        )

        // ⚠️ 顺序是这个函数的核心：**先移动、拿到结果，再决定删什么**。
        // 改造前是"合并成功 → 立刻删源文件（updateTaskAndCleanup）→ 再移动"，
        // 而移动这一步在 try 之外：移动失败（空间不足/目录被占用/无权限）时
        // 源文件已经删了、成品孤留在私有目录，用户重试只能整集重下。
        // 删什么由 DownloadSuccessorRules 决定，规则要求先把"移动是否成功"交进去。
        val uriStr = runCatching {
            fileOutputManager.moveToDownloadAndRegister(tempOutputFile, lastFileName, mimeType)
        }.getOrNull()

        if (uriStr == null) {
            DownloadSuccessorRules.filesToDeleteAfterMove(
                moveSucceeded = false,
                subTaskPaths = task.downloadSubTasks.map { it.savePath },
                tempOutputPath = tempOutputFile.absolutePath,
            ).forEach { File(it).deleteIfExists() }
            cleanupEmbedCache(task)
            failTask(
                task,
                "文件移入下载目录失败（存储空间不足、目录被占用或缺权限）"
            )
            return
        }

        val newTask = task.copy(
            downloadSegment = segment.copy(
                savePath = uriStr,
                qualityDescription = quality,
                fileSize = fileSize,
                downloadState = DownloadState.COMPLETED
            )
        )
        downloadTaskRepository.updateSegment(newTask.downloadSegment)
        updateTaskState(newTask, DownloadState.COMPLETED)

        // 落盘与入库都成功了，源文件才可以删（移动本身已删掉临时产物，这里的 deleteIfExists 是幂等的兜底）
        DownloadSuccessorRules.filesToDeleteAfterMove(
            moveSucceeded = true,
            subTaskPaths = task.downloadSubTasks.map { it.savePath },
            tempOutputPath = tempOutputFile.absolutePath,
        ).forEach { File(it).deleteIfExists() }

        // 合并已经彻底结束（产物都进下载目录了），内嵌封面/字幕的临时件就没用了。
        // 原先它们要等用户去点「存储管理 → 清空缓存」才会消失，等于不清理。
        cleanupEmbedCache(task)
    }

    /**
     * 没有媒体文件可下载（用户只勾了封面/弹幕/字幕）时的收尾。
     *
     * 附加产物（相册封面、弹幕 xml、字幕文件）已在前置任务里写好，所以这里按**已完成**收尾，
     * 而不是让任务卡在 ERROR。`savePath` 保持为空（新建 segment 时就是 `""`）——
     * 界面点「打开」会走 `File("").exists() == false` 那条分支，提示"文件不存在"，不会崩。
     */
    private suspend fun markCompletedWithoutMerge(task: AppDownloadTask) {
        Log.d(
            TAG,
            "无媒体子任务（仅封面/弹幕/字幕），跳过合并直接标记完成 platformId=" +
                "${task.downloadSegment.platformId}"
        )
        val latest = downloadTaskRepository.getSegmentBySegmentId(task.downloadSegment.segmentId)
        val completedTask = task.copy(
            downloadSegment = (latest ?: task.downloadSegment).copy(
                downloadState = DownloadState.COMPLETED
            )
        )
        downloadTaskRepository.updateSegment(completedTask.downloadSegment)
        updateTaskState(completedTask, DownloadState.COMPLETED)
    }

    private fun createTempOutputFile(task: AppDownloadTask): File {
        val saveDir = task.downloadSubTasks.first().savePath.substringBeforeLast("/")
        val extension =
            getResExtension(
                task.downloadSegment.downloadMode,
                task.downloadViewInfo.mediaContainerConfig
            )
        val timestamp = System.currentTimeMillis()

        return File(saveDir, "${task.downloadSegment.segmentId}_$timestamp.$extension").apply {
            parentFile?.mkdirs()
        }
    }

    private fun getMimeType(
        mode: DownloadMode,
        mediaContainerConfig: MediaContainerConfig
    ): String {
        return when (mode) {
            DownloadMode.AUDIO_VIDEO,
            DownloadMode.VIDEO_ONLY -> mediaContainerConfig.videoContainer.mimeType

            DownloadMode.AUDIO_ONLY -> mediaContainerConfig.audioContainer.mimeType
        }
    }

    /**
     * 获取资源后缀
     */
    private fun getResExtension(
        mode: DownloadMode,
        mediaContainerConfig: MediaContainerConfig
    ): String {
        return when (mode) {
            DownloadMode.AUDIO_VIDEO,
            DownloadMode.VIDEO_ONLY -> mediaContainerConfig.videoContainer.extension

            DownloadMode.AUDIO_ONLY -> mediaContainerConfig.audioContainer.extension
        }
    }

    private suspend fun downloadDanmakuForTask(task: AppDownloadTask) {
        val oid = task.downloadSegment.platformUniqueId.toLong()
        val title = namingConventionHandler.buildFileName(
            task.downloadSegment.namingConventionInfo,
            "xml"
        )

        val elms = flow {
            var page = 0
            while (true) {
                val list = videoInfoRepository.getDanmaku(oid = oid, segmentIndex = page)
                    .getOrNull()?.elems
                if (list.isNullOrEmpty()) break
                emitAll(list.asFlow())
                page++
            }
        }.toList()

        val xml = DanmakuXmlUtil.toBilibiliDanmakuXml(elms, oid)
        fileOutputManager.createDanmakuOutputStream(title).use { os ->
            os.write(xml.toByteArray(Charsets.UTF_8))
        }
    }

    private suspend fun downloadCoverImageForTask(task: AppDownloadTask) {
        // 同 handlePredecessor：没有封面地址就别去请求空 URL
        // （`downloadImageToAlbum` 里 OkHttp 的 `Request.url("")` 会直接抛）。
        val coverUrl = task.cover?.toHttps()
        if (!DownloadPredecessorRules.canFetchCover(coverUrl)) {
            Log.w(TAG, "没有封面地址，跳过封面下载 platformId=${task.downloadSegment.platformId}")
            return
        }

        val type = DownloadPredecessorRules.coverExtension(coverUrl)
        val fileName = when (task.downloadTask.type) {
            DownloadTaskType.BILI_DONGHUA,
            DownloadTaskType.BILI_VIDEO -> "${task.downloadSegment.platformId}_pic.$type"

            DownloadTaskType.BILI_VIDEO_SECTION if task.downloadSegment.taskId != null -> {
                val realTask = downloadTaskRepository.getTaskById(task.downloadSegment.taskId ?: 0L)
                "${realTask?.platformId}_pic.$type"
            }

            DownloadTaskType.BILI_VIDEO_SECTION -> error("封面所属任务类型异常")
        }
        downloadImageToAlbum(coverUrl.orEmpty(), fileName, "BiliDownloader")
    }

    private suspend fun processDownloadTree(
        taskTree: DownloadTaskTree,
        downloadViewInfo: DownloadViewInfo
    ) {
        // 先在本地把这一批要加的任务建出来，**不要**拿着旧快照去覆盖全局列表：
        // 这中间有网络调用（createSubTasksForSegment）与 delay，而进度回调、队列协程
        // 会在窗口期内写 `_downloadTasks` —— 直接覆盖会把它们的结果整份抹掉（任务消失或状态回退）。
        val incoming = mutableListOf<AppDownloadTask>()

        // 已经"在处理中"的 platformId 不必再走一遍建子任务（那是网络请求）。
        // ERROR 的除外：失败的内容允许重下，所以它不参与跳过（去重规则见 DownloadQueueRules）。
        val skipPlatformIds = _downloadTasks.value
            .filter { it.downloadState != DownloadState.ERROR }
            .map { it.downloadSegment.platformId }
            .toMutableSet()

        suspend fun processNode(node: DownloadTreeNode) {
            node.segments.forEach { segment ->
                if (skipPlatformIds.add(segment.platformId)) {
                    val downloadSubTasks =
                        createSubTasksForSegment(segment, node.node.nodeType, downloadViewInfo)
                    val cover = getCoverForSegment(segment)

                    incoming.add(
                        AppDownloadTask(
                            downloadTask = taskTree.task,
                            downloadSegment = segment,
                            downloadSubTasks = downloadSubTasks,
                            downloadViewInfo = downloadViewInfo,
                            downloadStage = DownloadStage.DOWNLOAD,
                            cover = cover,
                        )
                    )
                }
            }
            node.children.forEach { processNode(it) }
        }

        taskTree.roots.forEach { processNode(it) }

        // 原子合并（去重规则有单测）：失败的可重下、重复 platformId 只留一条
        _downloadTasks.update { current ->
            DownloadQueueRules.mergeTasks(
                existing = current,
                incoming = incoming,
                platformIdOf = { it.downloadSegment.platformId },
                stateOf = { it.downloadState },
            )
        }
    }

    private suspend fun createSubTasksForSegment(
        segment: DownloadSegment,
        nodeType: DownloadTaskNodeType,
        downloadViewInfo: DownloadViewInfo
    ): List<DownloadSubTask> {
        val playerInfo = videoInfoFetcher.fetchVideoPlayerInfo(segment, nodeType, downloadViewInfo)
        val videoData = videoInfoFetcher.extractVideoData(playerInfo, downloadViewInfo)
            ?: return emptyList()

        val subTasks = when (videoData) {
            is BILIVideoDash -> {
                when (segment.downloadMode) {
                    DownloadMode.AUDIO_VIDEO -> listOf(
                        createSubTask(
                            segment,
                            DownloadSubTaskType.VIDEO,
                            downloadViewInfo.mediaContainerConfig
                        ),
                        createSubTask(
                            segment,
                            DownloadSubTaskType.AUDIO,
                            downloadViewInfo.mediaContainerConfig
                        )
                    )

                    DownloadMode.VIDEO_ONLY -> listOf(
                        createSubTask(
                            segment,
                            DownloadSubTaskType.VIDEO,
                            downloadViewInfo.mediaContainerConfig
                        )
                    )

                    DownloadMode.AUDIO_ONLY -> listOf(
                        createSubTask(
                            segment,
                            DownloadSubTaskType.AUDIO,
                            downloadViewInfo.mediaContainerConfig
                        )
                    )
                }
            }

            is BILIVideoDurl -> {
                downloadTaskRepository.updateSegment(segment.copy(downloadMode = DownloadMode.VIDEO_ONLY))
                listOf(
                    createSubTask(
                        segment,
                        DownloadSubTaskType.VIDEO,
                        downloadViewInfo.mediaContainerConfig
                    )
                )
            }

            else -> throw IllegalStateException("不支持的下载数据类型")
        }

        delay(500L)
        return subTasks
    }

    private fun createSubTask(
        segment: DownloadSegment,
        type: DownloadSubTaskType,
        mediaContainerConfig: MediaContainerConfig
    ): DownloadSubTask {
        val savePath = getSaveTempSubTaskPath(type) + "/${segment.platformId}_${type.name}.${
            when (type) {
                DownloadSubTaskType.VIDEO -> mediaContainerConfig.videoContainer.extension
                DownloadSubTaskType.AUDIO -> mediaContainerConfig.audioContainer.extension
            }
        }"
        return DownloadSubTask(
            segmentId = segment.segmentId,
            savePath = savePath,
            subTaskType = type,
            downloadState = DownloadState.WAITING
        )
    }

    private fun getSaveTempSubTaskPath(subTaskType: DownloadSubTaskType): String {
        val dirName = when (subTaskType) {
            DownloadSubTaskType.VIDEO -> "video"
            DownloadSubTaskType.AUDIO -> "audio"
        }
        return context.getExternalFilesDir(dirName)?.absolutePath!!
    }

    private suspend fun getCoverForSegment(segment: DownloadSegment): String? {
        return if (segment.taskId != null && segment.taskId != 0L) {
            downloadTaskRepository.getTaskById(segment.taskId!!)?.cover
        } else {
            segment.cover
        }
    }

    private fun createProgressCallback(
        task: AppDownloadTask,
        downloadService: DownloadService,
        stage: String
    ): (Float) -> Unit = { progress ->
        downloadService.updateNotification(
            task.downloadSegment.title,
            stage,
            (progress * 100).toInt()
        )
        val state = if (stage == "合并阶段") DownloadState.MERGING else DownloadState.DOWNLOADING
        updateTaskState(task.copy(progress = progress), state)
    }

    private fun findTaskById(segmentId: Long): AppDownloadTask? {
        return _downloadTasks.value.find { it.downloadSegment.segmentId == segmentId }
    }

    private suspend fun cancelActiveJob(segmentId: Long) {
        activeDownloadJobs[segmentId]?.cancelAndJoin()
        activeDownloadJobs.remove(segmentId)
    }

    private fun deleteTaskFiles(task: AppDownloadTask) {
        task.downloadSubTasks.forEach { subTask ->
            File(subTask.savePath).delete()
            val tempFile = File("${subTask.savePath}.downloading")
            tempFile.delete()
            // 分片下载的边车元数据必须一起删：只删临时文件而留下边车的话，
            // 下次下同一个文件会拿一份"过去某次分片"的进度来续传（元数据与本次计划一致时
            // 是会被信任的），而那份进度对应的临时文件已经不存在了。
            SegmentedDownloader.metaFileFor(tempFile).delete()
        }
    }

    private fun handleTaskError(task: AppDownloadTask, error: Exception) {
        failTask(
            task,
            "下载过程异常：${error.javaClass.simpleName}: ${error.message ?: "无详细信息"}",
            error
        )
    }

    /**
     * 更新某个任务的状态。
     *
     * ⚠️ 必须用 `update {}`（原子 CAS），不能写成 `_downloadTasks.value = _downloadTasks.value.map { … }`：
     * 后者是"读-改-写"，而进度回调（音/视频两个子协程、FFmpeg 统计线程）与队列协程会并发调用它，
     * 交错时后写的那次会把前一次的更新整份覆盖掉（任务状态回退甚至消失）。见第十八轮审查。
     */
    private fun updateTaskState(task: AppDownloadTask, state: DownloadState) {
        _downloadTasks.update { tasks ->
            tasks.map { existingTask ->
                if (existingTask.downloadSegment.segmentId == task.downloadSegment.segmentId) {
                    task.copy(
                        downloadSegment = task.downloadSegment.copy(downloadState = state),
                        downloadState = state
                    )
                } else {
                    existingTask
                }
            }
        }
    }

    private fun removeTaskFromList(segmentId: Long) {
        _downloadTasks.update { tasks -> tasks.filterNot { it.downloadSegment.segmentId == segmentId } }
    }

    private fun File.deleteIfExists() {
        if (exists()) delete()
    }


}
