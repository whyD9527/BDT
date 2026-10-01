package com.imcys.bilibilias.data.download.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已完成下载」列表显示规则的测试。
 *
 * 守的是第二十一轮真机验证发现的那个观感问题：只勾弹幕/字幕时生成的记录
 * **没有媒体文件**，却显示成「音视频 / 未知画质 / mp4」——
 * 用户会以为视频下好了，点「打开」才发现文件不存在。
 */
class DownloadRecordDisplayRulesTest {

    private fun tags(savePath: String, quality: String? = "1080P") = DownloadRecordDisplayRules.tags(
        modeTitle = "音视频",
        qualityTitle = quality,
        extension = "mp4",
        savePath = savePath,
    )

    @Test
    fun `有媒体文件时保持原来的三个标签`() {
        assertEquals(
            listOf("音视频", "1080P", "mp4"),
            tags("content://media/external/downloads/90036"),
        )
        // 本地路径（Android 10 以下）同样算有媒体文件
        assertEquals(
            listOf("音视频", "1080P", "mp4"),
            tags("/storage/emulated/0/Download/BiliDownloader/某视频.mp4"),
        )
    }

    @Test
    fun `没有媒体文件时只说"仅附加内容"，不能出现音视频画质封装`() {
        val labels = tags(savePath = "")
        assertEquals(listOf(DownloadRecordDisplayRules.EXTRAS_ONLY_TAG), labels)
        // 这三条断言就是这个 bug 本身：以前它们会显示成"像是下好了"
        assertFalse("不能显示模式", labels.contains("音视频"))
        assertFalse("不能显示画质", labels.contains("1080P"))
        assertFalse("不能显示封装格式", labels.contains("mp4"))
    }

    @Test
    fun `画质为空时只有真有媒体文件才回落到未知画质`() {
        assertEquals(listOf("音视频", "未知画质", "mp4"), tags("content://x", quality = null))
        // 没有媒体文件时连"未知画质"都不该出现（它同样在暗示有一个视频文件）
        assertEquals(listOf(DownloadRecordDisplayRules.EXTRAS_ONLY_TAG), tags("", quality = null))
    }

    @Test
    fun `savePath 只有空白也算没有媒体文件`() {
        assertFalse(DownloadRecordDisplayRules.hasMediaFile(""))
        assertFalse(DownloadRecordDisplayRules.hasMediaFile("   "))
        assertTrue(DownloadRecordDisplayRules.hasMediaFile("content://media/x"))
    }

    // ------------------------------------------- 文件被外部删掉（2026-10-01 真机反馈）

    @Test
    fun `文件丢失时先标出「文件已丢失」，再保留原本的模式画质格式`() {
        assertEquals(
            listOf("文件已丢失", "音视频", "1080P 高清", "mp4"),
            DownloadRecordDisplayRules.tags(
                modeTitle = "音视频",
                qualityTitle = "1080P 高清",
                extension = "mp4",
                savePath = "content://media/external/video/media/42",
                fileMissing = true,
            ),
        )
    }

    @Test
    fun `纯附加内容的记录不因探测结果而变成「文件已丢失」`() {
        // 只下了弹幕/封面/字幕的记录，savePath 本来就是空 —— 不该被标成"文件丢失"
        assertEquals(
            listOf(DownloadRecordDisplayRules.EXTRAS_ONLY_TAG),
            DownloadRecordDisplayRules.tags(
                modeTitle = "音视频",
                qualityTitle = "1080P 高清",
                extension = "mp4",
                savePath = "",
                fileMissing = true,
            ),
        )
    }

    @Test
    fun `打不开的提示要分情况，不能一律说成只有附加内容`() {
        assertEquals(
            "这条记录只有附加内容（弹幕/封面/字幕），没有可打开的文件",
            DownloadRecordDisplayRules.unopenableMessage(savePath = "", fileMissing = false),
        )
        assertEquals(
            "文件已被删除或移动（可以用右侧的删除按钮清掉这条记录，再重新下载）",
            DownloadRecordDisplayRules.unopenableMessage(
                savePath = "content://media/external/video/media/42",
                fileMissing = true,
            ),
        )
    }
}
