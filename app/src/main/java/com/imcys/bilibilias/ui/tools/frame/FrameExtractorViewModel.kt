package com.imcys.bilibilias.ui.tools.frame

import com.imcys.bilibilias.download.FileOutputManager
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.imcys.bilibilias.data.repository.DownloadTaskRepository
import com.imcys.bilibilias.database.entity.download.DownloadMode
import com.imcys.bilibilias.database.entity.download.DownloadSegment
import com.imcys.bilibilias.database.entity.download.DownloadState
import com.imcys.bilibilias.download.NewDownloadManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FrameExtractorViewModel(
    private val downloadManager: NewDownloadManager,
    private val downloadTaskRepository: DownloadTaskRepository,
    private val contentResolver: ContentResolver,
    /** 只用来统一"文件到底还在不在"的判据（I，与全应用同一套） */
    private val fileOutputManager: FileOutputManager,
) : ViewModel() {

    sealed interface UIState {
        data object Default : UIState

        data class Importing(
            val progress: Float = 0f,
            val selectVideoPath: String? = null,
        ) : UIState

        data class ImportSuccess(
            val videoPath: String? = null,
            val frameList: List<Bitmap> = emptyList(),
            /**
             * 与 [frameList] 一一对应的**原始 PNG 路径**（导出用全分辨率那份，见 H6 的修复说明）。
             * 老状态里没有这个字段时导出会退回旧的"直接压缩预览位图"逻辑。
             */
            val framePaths: List<String> = emptyList(),
            val videoDuration: Int = 0,
            val videoFps: Int,
            val selectFps: Int = 1
        ) : UIState

        data class Exporting(
            val progress: Float = 0f,
            val exportPath: String = "",
        ) : UIState
    }

    private val _uiState = MutableStateFlow<UIState>(UIState.Default)
    val uiState: StateFlow<UIState> = _uiState.asStateFlow()

    private val _allDownloadSegment = MutableStateFlow<List<DownloadSegment>>(emptyList())
    val allDownloadSegment = _allDownloadSegment.asStateFlow()

    private var extractionJob: Job? = null
    private var currentVideoPath: String? = null
    private var extractedFramesDir: File? = null

    /** 抽帧用的 applicationContext（只为拿 cache 目录；持 application context 不会泄漏 Activity） */
    private var appContext: Context? = null

    /** 本次实际用的抽取帧率（可能因磁盘上限被降过，挑帧间隔要按它算） */
    private var extractedFps: Int = 0

    fun importVideo(context: Context, videoPath: String, fps: Int) {
        appContext = context.applicationContext
        extractionJob?.cancel()
        extractionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = "${context.externalCacheDir?.absolutePath}/frameTemp"
                deleteCacheDir(context)
                File(cacheDir).mkdirs()

                val tempVideoPath = if (videoPath.startsWith("content://")) {
                    val tempFile = File(cacheDir, "temp_video.mp4")
                    context.contentResolver.openInputStream(videoPath.toUri())?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    tempFile.absolutePath
                } else {
                    videoPath
                }

                currentVideoPath = tempVideoPath
                extractedFramesDir = null
                extractFramesWithFps(tempVideoPath, fps)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("FrameExtractor", "Import failed", e)
                _uiState.value = UIState.Default
            }
        }
    }

    fun updateSelectFps(currentFps: Int) {
        val videoPath = currentVideoPath ?: return

        extractionJob?.cancel()
        extractionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                extractFramesWithFps(videoPath, currentFps)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("FrameExtractor", "Update fps failed", e)
                _uiState.value = UIState.Default
            }
        }
    }

    private suspend fun extractFramesWithFps(videoPath: String, fps: Int) {
        _uiState.value = UIState.Importing(progress = 0f, selectVideoPath = videoPath)

        val videoFps = getVideoFrameRate(videoPath)
        // ⚠️ 抽出来的帧**只能放缓存目录**（2026-09-15 复审 U3）：原来真实文件路径的视频会落到
        // `文件所在目录/frames` —— 那是用户的 Download/BiliDownloader，上万个 PNG 永远不会被清理。
        val framesDir = extractedFramesDir ?: run {
            val cacheRoot = appContext?.externalCacheDir ?: return
            File(cacheRoot, "frameTemp/frames").apply { mkdirs() }
        }

        val frameFiles = framesDir.listFiles()?.sortedBy { it.name }
        if (frameFiles.isNullOrEmpty()) {
            extractAllFrames(videoPath, framesDir, videoFps) { progress ->
                _uiState.value = UIState.Importing(
                    progress = progress * 0.5f,
                    selectVideoPath = videoPath
                )
            }
            extractedFramesDir = framesDir
        }

        val (bitmapList, framePaths) = loadFramesWithFps(framesDir, videoFps, fps) { progress ->
            _uiState.value = UIState.Importing(
                progress = 0.5f + progress * 0.45f,
                selectVideoPath = videoPath
            )
        }

        _uiState.value = UIState.Importing(progress = 1f, selectVideoPath = videoPath)

        _uiState.value = UIState.ImportSuccess(
            videoFps = videoFps,
            frameList = bitmapList,
            framePaths = framePaths,
            selectFps = fps,
            videoPath = videoPath
        )
    }

    private suspend fun getVideoFrameRate(videoPath: String): Int = withContext(Dispatchers.IO) {
        val session = FFprobeKit.getMediaInformation(videoPath)
        if (ReturnCode.isSuccess(session.returnCode)) {
            val mediaInfo = session.mediaInformation
            val videoStream = mediaInfo?.streams?.firstOrNull { it.type == "video" }
            val fpsStr = videoStream?.averageFrameRate
            if (fpsStr != null && fpsStr.contains("/")) {
                val parts = fpsStr.split("/")
                val num = parts[0].toDoubleOrNull() ?: 30.0
                val den = parts[1].toDoubleOrNull() ?: 1.0
                (num / den).toInt()
            } else {
                30
            }
        } else {
            30
        }
    }

    /**
     * 抽帧的两条"别把机器搞死"的上限（2026-09-15 复审 H6 / U2 / U3）：
     * - 落盘的全帧 PNG 最多这么多张（10 分钟 30fps 视频原样抽是 1.8 万张、几十 GB）；
     * - 内存里同时持有的预览 Bitmap 最多这么多张（原来把每一张都按原分辨率 decode 进 list，
     *   1080p × 600 张 ≈ 5GB，必然 OOM —— 而 `OutOfMemoryError` 是 `Error`，`catch (Exception)` 兜不住）。
     */
    private companion object {
        const val MAX_EXTRACT_FRAMES = 600
        const val MAX_PREVIEW_FRAMES = 80
        /** 预览位图的长边上限（只影响界面预览；导出走原始 PNG 文件，不受影响） */
        const val PREVIEW_MAX_DIMENSION = 960
    }

    private suspend fun extractAllFrames(
        videoPath: String,
        framesDir: File,
        videoFps: Int,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        framesDir.listFiles()?.forEach { it.delete() }

        onProgress(0.1f)
        // 先估一下"全帧抽取"会有多少张：超过上限就按比例降低抽取帧率，
        // 否则磁盘会被写满、任务也会因为 ENOSPC 失败（而且失败前已经写出几万个文件）。
        val durationSec = probeDurationSeconds(videoPath)
        val estimatedFrames = durationSec * videoFps
        val effectiveFps = if (estimatedFrames <= MAX_EXTRACT_FRAMES || durationSec <= 0) {
            videoFps.coerceAtLeast(1)
        } else {
            (MAX_EXTRACT_FRAMES / durationSec).coerceAtLeast(1)
        }
        extractedFps = effectiveFps
        Log.i(
            "FrameExtractor",
            "抽帧: 时长=${durationSec}s 原帧率=$videoFps 预计=$estimatedFrames 张 → 实际抽取帧率=$effectiveFps",
        )
        // ⚠️ 用 **executeWithArguments**（参数数组，同步版）而不是 `execute(命令串)`：
        // 后者会重新分词，路径里含 `"` 就会被切碎 —— 合并那条路早就改成 argv 了（2026-09-15 复审 L19）。
        val session = FFmpegKit.executeWithArguments(
            arrayOf(
                "-i", videoPath,
                "-vf", "fps=$effectiveFps",
                "${framesDir.absolutePath}/frame_%04d.png",
            )
        )
        if (!ReturnCode.isSuccess(session.returnCode)) {
            Log.e("FrameExtractor", "FFmpeg failed: ${session.failStackTrace}")
            throw Exception("FFmpeg extraction failed")
        }
        onProgress(1f)
    }

    /** 视频时长（秒）：估抽帧张数用；探测失败返回 0（那就按原帧率抽） */
    private fun probeDurationSeconds(videoPath: String): Int = runCatching {
        val session = FFprobeKit.getMediaInformation(videoPath)
        if (!ReturnCode.isSuccess(session.returnCode)) return@runCatching 0
        session.mediaInformation?.duration?.toDoubleOrNull()?.toInt() ?: 0
    }.getOrDefault(0)

    /**
     * 按 `selectFps` 从已抽取的帧里挑出候选帧，并解码成**有内存上限的预览位图**。
     *
     * @return 预览位图（可能被降采样）+ 对应的**原始 PNG 路径**（导出用全分辨率的那份）
     */
    private suspend fun loadFramesWithFps(
        framesDir: File,
        videoFps: Int,
        selectFps: Int,
        onProgress: (Float) -> Unit
    ): Pair<List<Bitmap>, List<String>> = withContext(Dispatchers.IO) {
        val allFrameFiles = framesDir.listFiles()?.sortedBy { it.name }
            ?: return@withContext emptyList<Bitmap>() to emptyList()
        if (allFrameFiles.isEmpty()) return@withContext emptyList<Bitmap>() to emptyList()

        // 抽取帧率可能因为上面的磁盘上限被降过，间隔要按它算
        val baseFps = if (extractedFps > 1) extractedFps else videoFps
        var interval = (baseFps.toFloat() / selectFps.coerceAtLeast(1)).coerceAtLeast(1f)
        // 候选帧数也要设上限：间隔乘以一个系数，让选中数 ≤ MAX_PREVIEW_FRAMES
        val maxSelectable = allFrameFiles.size / interval
        if (maxSelectable > MAX_PREVIEW_FRAMES) {
            interval *= (maxSelectable / MAX_PREVIEW_FRAMES.toFloat())
        }
        val selectedFrames = mutableListOf<File>()
        var currentIndex = 0f
        while (currentIndex.toInt() < allFrameFiles.size) {
            selectedFrames.add(allFrameFiles[currentIndex.toInt()])
            currentIndex += interval
        }

        val bitmapList = mutableListOf<Bitmap>()
        selectedFrames.forEachIndexed { index, file ->
            decodePreview(file)?.let { bitmapList += it }
            onProgress((index + 1).toFloat() / selectedFrames.size)
        }
        Log.i(
            "FrameExtractor",
            "预览帧: 选中=${selectedFrames.size} 张（总帧文件=${allFrameFiles.size}，间隔=$interval）",
        )
        bitmapList to selectedFrames.map { it.absolutePath }
    }

    /**
     * 解码预览位图：先量尺寸再算 `inSampleSize`，并且用 RGB_565（这些帧本来就是无 alpha 的图片，
     * 内存直接减半）。**导出用的是原始 PNG 文件**，所以这里降采样不影响成品质量。
     */
    private fun decodePreview(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        // 目标：降采样后长边 < PREVIEW_MAX_DIMENSION × 2（1920 → sample=2 → 960）
        while (maxDim / (sample * 2) >= PREVIEW_MAX_DIMENSION) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    fun initVideoInfo(context: Context) {
        deleteCacheDir(context)
        viewModelScope.launch(Dispatchers.IO) {
            downloadTaskRepository.getSegmentAll().collect { list ->
                _allDownloadSegment.emit(
                    list.filter { segment ->
                        segment.downloadState == DownloadState.COMPLETED &&
                                (segment.downloadMode == DownloadMode.VIDEO_ONLY ||
                                        segment.downloadMode == DownloadMode.AUDIO_VIDEO)
                    }.mapNotNull { segment ->
                        val savePath = segment.savePath
                        if (savePath.isBlank()) {
                            null
                        } else if (savePath.startsWith("content://")) {
                            val fileUri = savePath.toUri()
                            // ⚠️ I（2026-10-02 真机复现）：原先用 `DocumentFile.fromSingleUri(uri).exists()`
                            // 判存在 —— 真机上"**被移动到子目录**的那条记录"在这里判 false（而下载卡片、⑥ 的
                            // 探测都说存在），于是抽帧的「从已下载导入」列表**整个空掉**（用户以为抽帧坏了）。
                            // 改成与全应用统一的判据：⑥ 的 `probeSavePath`（宽判据：媒体库行/可打开）。
                            if (!fileOutputManager.probeSavePath(savePath).exists) {
                                null
                            } else {
                                val retriever = MediaMetadataRetriever()
                                try {
                                    retriever.setDataSource(context, fileUri)
                                    val durationStr = retriever.extractMetadata(
                                        MediaMetadataRetriever.METADATA_KEY_DURATION
                                    )
                                    val durationMs = durationStr?.toLongOrNull() ?: 0L
                                    segment.apply { tempDuration = durationMs }
                                } catch (_: Exception) {
                                    null
                                } finally {
                                    retriever.release()
                                }
                            }
                        } else {
                            val file = File(savePath)
                            if (!file.exists()) {
                                null
                            } else {
                                val retriever = MediaMetadataRetriever()
                                try {
                                    retriever.setDataSource(file.absolutePath)
                                    val durationStr = retriever.extractMetadata(
                                        MediaMetadataRetriever.METADATA_KEY_DURATION
                                    )
                                    val durationMs = durationStr?.toLongOrNull() ?: 0L
                                    segment.apply { tempDuration = durationMs }
                                } catch (_: Exception) {
                                    null
                                } finally {
                                    retriever.release()
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    fun deleteCacheDir(context: Context) {
        appContext = appContext ?: context.applicationContext
        val root = appContext?.externalCacheDir ?: context.externalCacheDir
        val cacheDir = "${root?.absolutePath}/frameTemp"
        if (File(cacheDir).exists()) {
            File(cacheDir).deleteRecursively()
        }
        currentVideoPath = null
        extractedFramesDir = null
        extractedFps = 0
    }

    override fun onCleared() {
        // 兜底：抽帧目录（可能上万个 PNG）绝不能留在缓存里等系统回收（2026-09-15 复审 U3）
        runCatching {
            appContext?.externalCacheDir?.let { File(it, "frameTemp").deleteRecursively() }
        }
        super.onCleared()
    }

    fun exportFrameToImage(context: Context, exportUri: String) {
        val currentState = _uiState.value
        if (currentState !is UIState.ImportSuccess) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val treeUri = exportUri.toUri()
                val docFile = DocumentFile.fromTreeUri(context, treeUri) ?: return@launch

                // ⚠️ 导出**逐张按需解码原始 PNG**（2026-09-15 复审 H6）：
                // 内存里那批预览位图是降过采样的（为了不 OOM），拿它们导会掉画质；
                // 这里从 framePaths 全分辨率解码 → 压缩 → 立刻 recycle，内存占用与帧数无关。
                val paths = currentState.framePaths
                if (paths.isNotEmpty()) {
                    paths.forEachIndexed { index, path ->
                        _uiState.value = UIState.Exporting(
                            progress = (index + 1) / paths.size.toFloat(),
                            exportPath = exportUri
                        )
                        val bitmap = BitmapFactory.decodeFile(path) ?: return@forEachIndexed
                        try {
                            writeFramePng(docFile, index, bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                } else {
                    // 兜底（老状态没有 framePaths）：沿用旧逻辑
                    currentState.frameList.forEachIndexed { index, bitmap ->
                        _uiState.value = UIState.Exporting(
                            progress = (index + 1) / currentState.frameList.size.toFloat(),
                            exportPath = exportUri
                        )
                        writeFramePng(docFile, index, bitmap)
                    }
                }
                _uiState.value = currentState
            } catch (e: Exception) {
                Log.e("FrameExtractor", "Export failed", e)
                _uiState.value = currentState
            }
        }
    }

    private fun writeFramePng(docFile: DocumentFile, index: Int, bitmap: Bitmap) {
        val fileName = "frame_${index + 1}.png"
        val file = docFile.createFile("image/png", fileName) ?: return
        contentResolver.openOutputStream(file.uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
