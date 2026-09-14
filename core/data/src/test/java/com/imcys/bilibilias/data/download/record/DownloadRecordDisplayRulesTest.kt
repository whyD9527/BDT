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
}
