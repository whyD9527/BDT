package com.imcys.bilibilias.data.download.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「重复下载文件分组」的测试。
 *
 * 守的是 2026-10-01 真机复现的那条：这台 ROM 的 MediaStore 会用 `xxx (N).mp4` 命名副本，
 * 而自动删除在这个 ROM 上不可靠 → 退化到"列给用户确认"。分组判据必须准：
 * **宁可漏提示，也不能把两部不同的视频分到一组**（那会让用户一键删掉正片）。
 */
class DuplicateDownloadRulesTest {

    @Test
    fun `同名多份要分成一组并保留正式名`() {
        val groups = DuplicateDownloadRules.groupDuplicates(
            listOf("爱.mp4", "爱 (1).mp4", "爱 (2).mp4"),
        )
        assertEquals(1, groups.size)
        assertEquals("爱.mp4", groups[0].keepName)
        assertEquals(listOf("爱 (1).mp4", "爱 (2).mp4"), groups[0].removableNames)
    }

    @Test
    fun `只有副本名时保留序号最小的那份`() {
        val groups = DuplicateDownloadRules.groupDuplicates(listOf("爱 (3).mp4", "爱 (2).mp4"))
        assertEquals("爱 (2).mp4", groups[0].keepName)
        assertEquals(listOf("爱 (3).mp4"), groups[0].removableNames)
    }

    @Test
    fun `不同视频绝不能分到一组`() {
        assertTrue(
            DuplicateDownloadRules.groupDuplicates(
                listOf("爱.mp4", "别的视频.mp4", "第三个 (1).mp4"),
            ).isEmpty(),
        )
        // 同 stem、不同扩展名也各算各的（.mp4 与 .ass 不是同一份内容）
        assertTrue(
            DuplicateDownloadRules.groupDuplicates(listOf("爱.mp4", "爱.ass")).isEmpty(),
        )
    }

    @Test
    fun `括号里不是纯数字的不算副本`() {
        assertTrue(
            DuplicateDownloadRules.groupDuplicates(
                listOf("爱 (前篇).mp4", "爱 (后篇).mp4"),
            ).isEmpty(),
        )
        // 但真实的 `(1)` 仍要认：同族里保留序号最小的 (1)，可删 (2)
        assertEquals(
            listOf("爱 (2).mp4"),
            DuplicateDownloadRules.groupDuplicates(listOf("爱 (1).mp4", "爱 (2).mp4"))
                .single().removableNames,
        )
    }

    @Test
    fun `族标识与副本序号`() {
        assertEquals("爱.mp4", DuplicateDownloadRules.familyKey("爱 (7).mp4"))
        assertEquals("爱.mp4", DuplicateDownloadRules.familyKey("爱.mp4"))
        // 没有扩展名
        assertEquals("爱", DuplicateDownloadRules.familyKey("爱 (2)"))
        assertEquals(2, DuplicateDownloadRules.copyIndex("爱 (2).mp4"))
        assertEquals(12, DuplicateDownloadRules.copyIndex("爱 (12).mp4"))
        assertNull(DuplicateDownloadRules.copyIndex("爱.mp4"))
        assertNull(DuplicateDownloadRules.copyIndex("爱 (x).mp4"))
    }

    @Test
    fun `空列表与空白名字不会炸`() {
        assertTrue(DuplicateDownloadRules.groupDuplicates(emptyList()).isEmpty())
        assertTrue(DuplicateDownloadRules.groupDuplicates(listOf("", "   ")).isEmpty())
    }
}
