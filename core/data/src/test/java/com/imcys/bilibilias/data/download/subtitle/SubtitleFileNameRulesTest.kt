package com.imcys.bilibilias.data.download.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 字幕文件名的测试。
 *
 * 守的是第十八轮审查里的低危项：字幕文件名**缺扩展名**。
 * 弹幕与媒体的文件名都自带后缀（`buildFileName(..., "xml")` / `(..., extension)`），
 * 只有字幕这条把 `title_lan_type` 直接当文件名用了。
 */
class SubtitleFileNameRulesTest {

    @Test
    fun `字幕文件名必须带扩展名`() {
        assertEquals(
            "某番剧_第1话_zh-CN.srt",
            SubtitleFileNameRules.build("某番剧_第1话", "zh-CN", "SRT"),
        )
    }

    @Test
    fun `ASS 类型用 ass 后缀`() {
        assertEquals(
            "某番剧_ai-zh.ass",
            SubtitleFileNameRules.build("某番剧", "ai-zh", "ASS"),
        )
    }

    @Test
    fun `大小写不敏感（CCFileType 的 name 是大写）`() {
        assertEquals("t_zh.srt", SubtitleFileNameRules.build("t", "zh", "srt"))
        assertEquals("t_zh.ass", SubtitleFileNameRules.build("t", "zh", "ass"))
    }

    @Test
    fun `类型缺失或未知时回落 srt（绝不产出没有后缀的文件）`() {
        assertEquals("t_zh.srt", SubtitleFileNameRules.build("t", "zh", null))
        assertEquals("t_zh.srt", SubtitleFileNameRules.build("t", "zh", ""))
        assertEquals("t_zh.srt", SubtitleFileNameRules.build("t", "zh", "vtt"))
    }

    @Test
    fun `后缀推断只认 ass 与 srt`() {
        assertEquals("ass", SubtitleFileNameRules.extensionFor("ASS"))
        assertEquals("srt", SubtitleFileNameRules.extensionFor("SRT"))
        assertEquals("srt", SubtitleFileNameRules.extensionFor(null))
    }
}
