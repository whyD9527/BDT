package com.imcys.bilibilias.data.download.merge

import com.imcys.bilibilias.data.download.execution.EmbedStreamMappingRules
import com.imcys.bilibilias.data.model.download.DownloadSubTask
import com.imcys.bilibilias.data.model.download.MediaContainerConfig
import com.imcys.bilibilias.database.entity.download.DownloadMode
import com.imcys.bilibilias.database.entity.download.DownloadSubTaskType
import com.imcys.bilibilias.database.entity.download.MediaContainer

/**
 * 字幕输入的最小描述。
 *
 * 之所以不直接用 `:app` 的 `LocalSubtitle`：那个类在 app 模块里，而本文件要放在库模块
 * 才能跑本地单测（`:app` 的单测在本机跑不了，见交接文档第十五轮）。
 */
data class SubtitleSpec(
    val path: String,
    val lang: String,
    val langDoc: String,
)

/**
 * ffmpeg 合并命令的**纯函数**构建器（不碰 Android、不做 IO、不是 suspend）。
 *
 * ## 为什么必须返回 `List<String>` 而不是拼成一条命令串
 * `FFmpegKit.executeAsync(String)` 会把命令串交给 `FFmpegKitConfig.parseArguments` 重新分词，
 * 而它的解析器只按空格切分、`"` 只切换「引号内」状态、**不处理 `\"` 转义**。
 * 于是标题或简介里只要有一个英文双引号（简介很长、很常见），
 * `title="a"b c"` 就会被切成 `title=a`、`b`、`c"` 三个参数 → ffmpeg 拿到多余的位置参数 →
 * 合并必然失败。更糟的是：子任务文件此时**已经完整**，重试会被「文件已完整」的早退分支跳过，
 * 只再合并一次、再失败一次 —— 这个视频就**永远下不下来**。
 *
 * 而 `FFmpegKit.executeWithArgumentsAsync(String[])` 走的是 `FFmpegSession.create(String[])`，
 * **完全不经过 `parseArguments`**（已用字节码确认），参数原样传给 native 侧，
 * 引号、空格、换行都是字面量。所以元数据必须以**单个 argv 元素**给出（`key=值`），
 * 由 ffmpeg 自己按第一个 `=` 解析 —— **不要自己加引号**。
 *
 * ## 可测性
 * 抽出成纯函数后，「元数据是单个元素、没有被手工加引号」这类不变量可以用单测钉死，
 * 而不是只能靠真机下载一个"标题里带引号"的视频来碰运气。
 */
object FfmpegCommandBuilder {

    fun build(
        subTasks: List<DownloadSubTask>,
        downloadMode: DownloadMode,
        outputPath: String,
        mediaContainerConfig: MediaContainerConfig,
        title: String,
        description: String,
        copyright: String,
        subtitles: List<SubtitleSpec> = emptyList(),
        coverPath: String = "",
    ): List<String> {
        val mediaInputs = subTasks.map { it.savePath }
        val videoFileCount = subTasks.count { it.subTaskType == DownloadSubTaskType.VIDEO }
        val audioFileCount = subTasks.count { it.subTaskType == DownloadSubTaskType.AUDIO }

        val videoEnabled = downloadMode in listOf(DownloadMode.VIDEO_ONLY, DownloadMode.AUDIO_VIDEO)
        val audioEnabled = downloadMode in listOf(DownloadMode.AUDIO_ONLY, DownloadMode.AUDIO_VIDEO)

        val audioFileStartIdx = videoFileCount
        val subFileStartIdx = mediaInputs.size

        // ---- 内嵌音轨 / 字幕 / 封面的映射决策（纯规则，见 EmbedStreamMappingRules）----
        // 两个洞都是"用了 -map 就只输出被映射的流"的直接后果：
        // ① durl 单文件 + 仅视频：补映射原来挂在 `audioEnabled` 下，而"仅视频"时它是 false
        //    → 音轨整条丢掉（无声视频 + COMPLETED）；
        // ② 纯音频容器里硬塞字幕/封面：字幕下标指向的其实是音频流、容器又没有字幕编码器
        //    → ffmpeg 必然失败，这一集永远下不下来。
        val mapEmbeddedAudio = EmbedStreamMappingRules.shouldMapEmbeddedAudio(
            mediaInputCount = mediaInputs.size,
            hasSeparateAudioInput = audioFileCount > 0,
        )
        val mapSubtitles = EmbedStreamMappingRules.canMapSubtitles(
            containerSupportsSubtitle = mediaContainerConfig.videoContainer.canEmbedSubtitle(),
            videoEnabled = videoEnabled,
        ) && subtitles.isNotEmpty()
        val mapCover = EmbedStreamMappingRules.canMapCover(
            containerSupportsCover = if (videoEnabled) {
                mediaContainerConfig.videoContainer.canEmbedCover()
            } else {
                mediaContainerConfig.audioContainer.canEmbedCover()
            },
        ) && coverPath.isNotBlank()

        val coverIdx = if (mapCover) mediaInputs.size + subtitles.size else -1

        return buildList {
            // 基础参数
            add("-y")
            add("-strict")
            add("-2")

            // 输入文件
            mediaInputs.forEach {
                add("-i")
                add(it)
            }
            if (mapSubtitles) {
                subtitles.forEach {
                    add("-i")
                    add(it.path)
                }
            }
            if (mapCover) {
                add("-i")
                add(coverPath)
            }

            // 流映射
            if (videoEnabled) {
                add("-map")
                add("0:v:0")
            }
            if (audioEnabled && audioFileCount > 0) {
                repeat(audioFileCount) { i ->
                    add("-map")
                    add("${audioFileStartIdx + i}:a:$i")
                }
            }
            if (mapEmbeddedAudio) {
                // durl（渐进式单文件）资源：音轨就在**同一个输入文件**里，
                // 而 ffmpeg 一旦用了 `-map` 就只输出被映射的流。
                // 末尾的 `?` = "有就映射、没有也不报错"。
                // ⚠️ 这一条**不挂在 `audioEnabled` 下**（H3 的教训）：仅视频 + 单文件时
                // `audioEnabled` 是 false，挂在下面就等于没写。
                add("-map")
                add("0:a:0?")
            }
            if (mapSubtitles) {
                subtitles.forEachIndexed { sIdx, _ ->
                    add("-map")
                    add("${subFileStartIdx + sIdx}:s:0")
                }
            }
            if (mapCover) {
                add("-map")
                add("$coverIdx:v:0")
            }

            // 编解码器
            if (videoEnabled) {
                add("-c:v")
                add("copy")
            }
            if (audioEnabled) {
                add("-c:a")
                add("copy")
            }

            // 字幕元数据
            if (mapSubtitles) {
                add("-c:s")
                if (mediaContainerConfig.videoContainer != MediaContainer.MKV) {
                    add("mov_text")
                } else {
                    add("copy")
                }
                subtitles.forEachIndexed { sIdx, subtitle ->
                    add("-metadata:s:s:$sIdx")
                    add("language=${subtitle.lang}")
                    add("-metadata:s:s:$sIdx")
                    add("title=${subtitle.langDoc}")
                }
            }

            // 封面配置
            if (mapCover) {
                val coverStreamIndex = if (videoEnabled) 1 else 0
                add("-c:v:$coverStreamIndex")
                add("mjpeg")
                add("-disposition:v:$coverStreamIndex")
                add("attached_pic")
                add("-metadata:s:v:$coverStreamIndex")
                add("title=Cover")
            }

            if (audioEnabled && mediaContainerConfig.audioContainer == MediaContainer.MP3) {
                addAll(listOf("-codec:a", "libmp3lame", "-q:a", "2"))
            }

            // 元数据：**单个 argv 元素**，不加引号（见类注释）
            add("-metadata")
            add("title=$title")

            add("-metadata")
            add("description=$description")

            add("-metadata")
            add("copyright=$copyright")

            add(outputPath)
        }
    }
}
