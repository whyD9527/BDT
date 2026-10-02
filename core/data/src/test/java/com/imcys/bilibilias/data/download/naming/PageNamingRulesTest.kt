package com.imcys.bilibilias.data.download.naming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 单 P 不填分 P 标题（否则默认模板 `{title}_{p_title}` 会把标题写两遍）。
 * 真机来源见 [PageNamingRules] 的 KDoc。
 */
class PageNamingRulesTest {

    @Test
    fun `单 P 视频不提供分 P 标题`() {
        assertNull(PageNamingRules.partTitleForNaming(1, "Never Gonna Give You Up - Rick Astley"))
        // 就算分 P 标题与视频标题不完全相等（真机那种带前缀的情况），单 P 也不该填
        assertNull(PageNamingRules.partTitleForNaming(1, "任何一个名字"))
    }

    @Test
    fun `多 P 视频照常提供分 P 标题`() {
        assertEquals("P2 标题", PageNamingRules.partTitleForNaming(3, "P2 标题"))
    }

    @Test
    fun `空标题一律不填（交给渲染器收掉多余分隔符）`() {
        assertNull(PageNamingRules.partTitleForNaming(3, ""))
        assertNull(PageNamingRules.partTitleForNaming(3, null))
        assertNull(PageNamingRules.partTitleForNaming(0, "x"))
    }

    @Test
    fun `与渲染器连起来：单 P 得到的名字就是标题本身`() {
        val placeholders = listOf("{title}", "{p_title}")
        val name = NamingConventionRenderer.render(
            template = "{title}_{p_title}",
            placeholders = placeholders,
            values = mapOf(
                "{title}" to "【官方 MV】Never Gonna Give You Up - Rick Astley",
                // 单 P：调用方按规则传 null
                "{p_title}" to PageNamingRules.partTitleForNaming(1, "Never Gonna Give You Up - Rick Astley"),
            ),
            fileExtension = "mp4",
        )
        assertEquals("【官方 MV】Never Gonna Give You Up - Rick Astley.mp4", name)
    }
}
