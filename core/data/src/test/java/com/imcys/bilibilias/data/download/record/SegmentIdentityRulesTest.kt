package com.imcys.bilibilias.data.download.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `download_segment`「产物身份」语义的测试（④）。
 *
 * 这一版**没有接线**（用户决定先只出方案）：所以这里的断言就是"将来接线时应该满足的规格"。
 * 重点钉住两件事：
 * 1. **归一化**（trim / 大小写 / 空音质 → `unknown`）—— 不归一就会把同一份产物判成两种；
 * 2. **`ProductKey` 比现有 `LegacyKey` 多哪三项** —— 现有实现会把"同 node 的音频"和"视频"
 *    判成同一条记录然后互相覆盖（这正是要修的语义漏洞）。
 */
class SegmentIdentityRulesTest {

    private fun audio() = SegmentIdentityRules.productKeyOf(
        platformId = "BV1xx",
        nodeId = 11L,
        downloadMode = "audio_only",
        qualityKey = "30280",
        container = "M4A",
    )

    private fun video() = SegmentIdentityRules.productKeyOf(
        platformId = "BV1xx",
        nodeId = 11L,
        downloadMode = "video",
        qualityKey = "1080P",
        container = "mp4",
    )

    @Test
    fun `同一份产物：归一化后完全相同`() {
        val a = audio()
        val b = SegmentIdentityRules.productKeyOf(
            platformId = "  BV1xx  ",
            nodeId = 11L,
            downloadMode = "AUDIO_ONLY",
            qualityKey = " 30280 ",
            container = "m4a",
        )
        assertTrue(SegmentIdentityRules.sameProduct(a, b))
        // 直接用 Locale.ROOT 的结果断言：测试里若用默认 locale 的 uppercase()，
        // 在土耳其语等 locale 下 "i" 会变成 "İ"，与实现（Locale.ROOT）不一致而假红
        assertEquals("AUDIO_ONLY", a.downloadMode)
        assertEquals("m4a", a.container)
    }

    @Test
    fun `模式和音质不同就是两份产物（现有 LegacyKey 判不出来的那两种）`() {
        // 同一集先下音频、再下视频：过去 `(platformId, nodeId)` 相同 → 覆盖前一条，
        // 现在按 ProductKey 是**两份合法产物**
        assertFalse(SegmentIdentityRules.sameProduct(audio(), video()))

        // 只换音质也是两份（不同清晰度是不同产物，用户可能同时留着）
        val hd = video()
        val uhd = SegmentIdentityRules.productKeyOf("BV1xx", 11L, "video", "2160P", "mp4")
        assertFalse(SegmentIdentityRules.sameProduct(hd, uhd))

        // 只换封装也是两份
        val flv = SegmentIdentityRules.productKeyOf("BV1xx", 11L, "video", "1080P", "flv")
        assertFalse(SegmentIdentityRules.sameProduct(hd, flv))
    }

    @Test
    fun `节点或平台不同当然不是同一份产物`() {
        val base = video()
        assertFalse(
            SegmentIdentityRules.sameProduct(
                base,
                base.copy(nodeId = 12L),
            ),
        )
        assertFalse(
            SegmentIdentityRules.sameProduct(
                base,
                base.copy(platformId = "BV2yy"),
            ),
        )
    }

    @Test
    fun `音质为空或空白都归一成 unknown，不会因为没填而判成两种`() {
        assertEquals(SegmentIdentityRules.UNKNOWN_QUALITY, SegmentIdentityRules.normalizeQualityKey(null))
        assertEquals(SegmentIdentityRules.UNKNOWN_QUALITY, SegmentIdentityRules.normalizeQualityKey("   "))
        val a = SegmentIdentityRules.productKeyOf("BV1", 1L, "video", null, "mp4")
        val b = SegmentIdentityRules.productKeyOf("BV1", 1L, "VIDEO", "  ", "MP4")
        assertTrue(SegmentIdentityRules.sameProduct(a, b))
    }

    @Test
    fun `音质 key 保留大小写（1080P 与 1080p 是平台定义的两个值，不能暗中等同）`() {
        assertNotEquals(
            SegmentIdentityRules.productKeyOf("BV1", 1L, "video", "1080P", "mp4"),
            SegmentIdentityRules.productKeyOf("BV1", 1L, "video", "1080p", "mp4"),
        )
    }

    @Test
    fun `现有键刻意不含模式音质封装 —— 这份"漏洞"要留在测试里被看见`() {
        // 同一 node/平台下，音频与视频在 LegacyKey 眼里是**同一条**
        val audioLegacy = SegmentIdentityRules.legacyKeyOf("BV1xx", 11L)
        val videoLegacy = SegmentIdentityRules.legacyKeyOf("BV1xx", 11L)
        assertEquals(audioLegacy, videoLegacy)
        // 而 ProductKey 判得出来 —— 这就是阶段 1 要换键的原因
        assertFalse(SegmentIdentityRules.sameProduct(audio(), video()))
    }

    @Test
    fun `冲突时保留最新那条（MAX segment_id），空集合必须是 no-op`() {
        assertEquals(7L, SegmentIdentityRules.keepSegmentIdOnConflict(listOf(3L, 7L, 5L)))
        assertEquals(42L, SegmentIdentityRules.keepSegmentIdOnConflict(listOf(42L)))
        // ⚠️ 空集合返回 null = 调用方什么都不做。v3.3.2 就是"空表 + NOT IN 空集"把整表删光的
        assertNull(SegmentIdentityRules.keepSegmentIdOnConflict(emptyList()))
    }

    // ------------------------------------------- 阶段 1 的「产物形态」键

    @Test
    fun `产物形态键：平台 id 去空白、模式大写、容器小写`() {
        val form = SegmentIdentityRules.productFormOf(" BV1xx ", "audio_only", "M4A")
        assertEquals("BV1xx", form.platformId)
        assertEquals("AUDIO_ONLY", form.downloadMode)
        assertEquals("m4a", form.container)
    }

    @Test
    fun `产物形态键：同一集的不同产物不相等（音频 vs 视频）`() {
        val audio = SegmentIdentityRules.productFormOf("BV1", "AUDIO_ONLY", "m4a")
        val video = SegmentIdentityRules.productFormOf("BV1", "VIDEO_ONLY", "mp4")
        val both = SegmentIdentityRules.productFormOf("BV1", "AUDIO_VIDEO", "mp4")
        assertEquals(3, setOf(audio, video, both).size)
        // 同一产物（大小写/空白不同）要相等 —— 否则又会出现"同一份产物两条记录"
        assertEquals(audio, SegmentIdentityRules.productFormOf("BV1", "audio_only", "M4A"))
    }

    @Test
    fun `产物形态键里刻意没有音质 —— 因为文件名里也没有`() {
        // 这条断言是"规格"：等哪天命名规则把音质写进文件名了，再把音质加进键
        val form = SegmentIdentityRules.productFormOf("BV1", "VIDEO_ONLY", "mp4")
        assertFalse(form.toString().contains("quality"))
    }

    @Test
    fun `建议的唯一索引列写进了代码（评审用），且明确它包含现在还不存在的 quality_key`() {
        assertEquals(
            listOf("platform_id", "node_id", "download_mode", "quality_key"),
            SegmentIdentityRules.PROPOSED_UNIQUE_COLUMNS,
        )
        // 现在 DB 里没有这一列 → 阶段 2 必须先加列 + 回填 + 治理重复行，不能只加 @Index
        assertTrue(SegmentIdentityRules.PROPOSED_UNIQUE_COLUMNS.contains("quality_key"))
    }
}
