package com.imcys.bilibilias.data.download.merge

import com.imcys.bilibilias.data.model.download.DownloadSubTask
import com.imcys.bilibilias.data.model.download.MediaContainerConfig
import com.imcys.bilibilias.database.entity.download.DownloadSubTaskType
import com.imcys.bilibilias.database.entity.download.MediaContainer
import com.imcys.bilibilias.database.entity.download.DownloadMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ffmpeg 合并命令的参数数组测试。
 *
 * 守的核心不变量：**元数据必须作为单个 argv 元素给出（`key=值`），不能自己拼引号**。
 * 原因见 [FfmpegCommandBuilder] 的注释：字符串形式的命令会被 `parseArguments` 重新分词，
 * 标题/简介里一个英文双引号就能把参数切碎 → 合并必然失败；而子任务文件此时已完整，
 * 重试会跳过下载只再合并一次再失败 → **该视频永远下不下来**。
 * 这类问题编译期看不出来，只能靠把构建器抽成纯函数来钉。
 */
class FfmpegCommandBuilderTest {

    private fun subTask(
        path: String,
        type: DownloadSubTaskType,
    ) = DownloadSubTask(segmentId = 1L, savePath = path, subTaskType = type)

    private val videoOnly = MediaContainerConfig(
        videoContainer = MediaContainer.MP4,
        audioContainer = MediaContainer.M4A,
    )

    private fun build(
        subTasks: List<DownloadSubTask>,
        downloadMode: DownloadMode = DownloadMode.AUDIO_VIDEO,
        subtitles: List<SubtitleSpec> = emptyList(),
        coverPath: String = "",
        config: MediaContainerConfig = videoOnly,
        title: String = "标题",
        description: String = "简介",
        copyright: String = "https://www.bilibili.com/video/BV1xx411c7mD",
        outputPath: String = "/tmp/out.mp4",
    ) = FfmpegCommandBuilder.build(
        subTasks = subTasks,
        downloadMode = downloadMode,
        outputPath = outputPath,
        mediaContainerConfig = config,
        title = title,
        description = description,
        copyright = copyright,
        subtitles = subtitles,
        coverPath = coverPath,
    )

    // ---------------------------------------------------------------- 元数据：本批修的那条

    @Test
    fun `元数据是单个 argv 元素，且没有被手工加引号`() {
        val args = build(
            subTasks = listOf(subTask("/v.mp4", DownloadSubTaskType.VIDEO)),
            title = "标题",
            description = "简介",
        )

        assertTrue("title 必须是单个元素", args.contains("title=标题"))
        assertTrue("description 必须是单个元素", args.contains("description=简介"))
        // 不允许出现带手工引号的写法
        assertFalse("不能出现 title=\"…\" 这种手工加引号", args.any { it.startsWith("title=\"") })
        assertFalse(args.any { it.startsWith("description=\"") })
        assertFalse(args.any { it.startsWith("copyright=\"") })
    }

    @Test
    fun `标题和简介里的英文双引号原样保留（不会再切碎命令）`() {
        // 这就是"合并永远失败"的那个输入
        val trickyTitle = "他说\"你好\"然后走了"
        val trickyDesc = "第一行 \"引用\"\n第二行 '单引号' 与 emoji 🙂"
        val args = build(
            subTasks = listOf(subTask("/v.mp4", DownloadSubTaskType.VIDEO)),
            title = trickyTitle,
            description = trickyDesc,
        )

        assertTrue(
            "含双引号的标题必须是**完整的一个**元素",
            args.contains("title=$trickyTitle"),
        )
        assertTrue(
            "含双引号与换行的简介必须是**完整的一个**元素",
            args.contains("description=$trickyDesc"),
        )
        // 关键：不能有任何元素被拆出多余的碎片
        assertEquals("元素个数不应因引号而增加", 1, args.count { it.startsWith("title=") })
        assertEquals(1, args.count { it.startsWith("description=") })
    }

    @Test
    fun `输出路径是最后一个参数`() {
        val args = build(
            subTasks = listOf(subTask("/v.mp4", DownloadSubTaskType.VIDEO)),
            outputPath = "/tmp/有 空格 的输出.mp4",
        )
        assertEquals("/tmp/有 空格 的输出.mp4", args.last())
        assertTrue(args.contains("-y"))
    }

    // ---------------------------------------------------------------- 既有行为（防回归）

    @Test
    fun `音视频双输入时映射 0v 与 1a`() {
        val args = build(
            subTasks = listOf(
                subTask("/v.mp4", DownloadSubTaskType.VIDEO),
                subTask("/a.m4a", DownloadSubTaskType.AUDIO),
            ),
        )
        val mapIdx = args.indexOf("-map")
        assertEquals("0:v:0", args[mapIdx + 1])
        assertEquals("1:a:0", args[mapIdx + 3])
        assertTrue(args.contains("/v.mp4"))
        assertTrue(args.contains("/a.m4a"))
        assertTrue(args.contains("-c:v") && args.contains("-c:a"))
    }

    @Test
    fun `单文件资源（durl）仍要带上内嵌音轨，不能产出无声视频`() {
        // durl 只建 1 个 VIDEO 子任务，但用户选的是 AUDIO_VIDEO：
        // 音轨就在同一个输入文件里，不显式映射就会整条丢掉
        val args = build(
            subTasks = listOf(subTask("/durl.mp4", DownloadSubTaskType.VIDEO)),
            downloadMode = DownloadMode.AUDIO_VIDEO,
        )
        assertTrue("durl 必须映射内嵌音轨 0:a:0?", args.contains("0:a:0?"))
        assertTrue("视频流照常映射", args.contains("0:v:0"))
    }

    @Test
    fun `仅视频时不映射音频`() {
        val args = build(
            subTasks = listOf(subTask("/v.mp4", DownloadSubTaskType.VIDEO)),
            downloadMode = DownloadMode.VIDEO_ONLY,
        )
        assertTrue(args.contains("0:v:0"))
        assertFalse("VIDEO_ONLY 不该有音频映射", args.any { it.endsWith(":a:0") })
        assertFalse(args.contains("-c:a"))
    }

    @Test
    fun `仅音频时不映射视频`() {
        val args = build(
            subTasks = listOf(subTask("/a.m4a", DownloadSubTaskType.AUDIO)),
            downloadMode = DownloadMode.AUDIO_ONLY,
        )
        assertTrue(args.contains("0:a:0"))
        assertFalse("AUDIO_ONLY 不该有视频映射", args.contains("0:v:0"))
    }

    @Test
    fun `字幕走 mov_text（mp4）或 copy（mkv），并带语言元数据`() {
        val mp4 = build(
            subTasks = listOf(subTask("/v.mp4", DownloadSubTaskType.VIDEO)),
            subtitles = listOf(SubtitleSpec("/s.ass", "zh-CN", "简体中文")),
        )
        assertTrue(mp4.contains("-c:s"))
        assertTrue("mp4 容器应转 mov_text", mp4.contains("mov_text"))
        assertTrue(mp4.contains("language=zh-CN"))
        assertTrue(mp4.contains("title=简体中文"))

        val mkv = build(
            subTasks = listOf(subTask("/v.mkv", DownloadSubTaskType.VIDEO)),
            subtitles = listOf(SubtitleSpec("/s.ass", "zh-CN", "简体中文")),
            config = MediaContainerConfig(
                videoContainer = MediaContainer.MKV,
                audioContainer = MediaContainer.M4A,
            ),
        )
        assertTrue("mkv 容器应 copy 字幕", mkv.contains("copy"))
        assertFalse(mkv.contains("mov_text"))
    }

    @Test
    fun `封面作为附加流写入`() {
        val args = build(
            subTasks = listOf(subTask("/v.mp4", DownloadSubTaskType.VIDEO)),
            coverPath = "/c.jpg",
        )
        assertTrue(args.contains("/c.jpg"))
        assertTrue(args.contains("mjpeg"))
        assertTrue(args.contains("attached_pic"))
        assertTrue(args.contains("title=Cover"))
    }

    @Test
    fun `MP3 音频容器改用 libmp3lame`() {
        val args = build(
            subTasks = listOf(subTask("/a.mp3", DownloadSubTaskType.AUDIO)),
            downloadMode = DownloadMode.AUDIO_ONLY,
            config = MediaContainerConfig(
                videoContainer = MediaContainer.MP4,
                audioContainer = MediaContainer.MP3,
            ),
        )
        assertTrue(args.contains("libmp3lame"))
        assertTrue(args.contains("-q:a"))
    }
}
