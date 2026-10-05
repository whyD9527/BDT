package com.imcys.bilibilias.data.download.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「下载目录文件」孤儿判定的测试。
 *
 * 守的是两个真机现象（重装后记录全丢、改名后旧文件没人管）：这两种情况下界面
 * 都必须把它们数成"无记录"，但**不能**把 `xxx (1).mp4` 当成 `xxx.mp4` 的记录。
 */
class DownloadDirFilesRulesTest {

    @Test
    fun `全部有记录时没有孤儿`() {
        val summary = DownloadDirFilesRules.summarize(
            dirFileDisplayNames = listOf("a.mp4", "b.mp4"),
            referencedNames = setOf("a.mp4", "b.mp4", "c 不存在于目录里.mp4"),
        )
        assertEquals(2, summary.total)
        assertEquals(0, summary.orphan)
    }

    @Test
    fun `没有任何记录时全部是孤儿（重装后的真实现象）`() {
        val summary = DownloadDirFilesRules.summarize(
            dirFileDisplayNames = listOf("a.mp4", "b.mp4", "封面.jpg"),
            referencedNames = emptySet(),
        )
        assertEquals(3, summary.total)
        assertEquals(3, summary.orphan)
    }

    @Test
    fun `重名副本各自计数（改名后旧文件成了孤儿）`() {
        val summary = DownloadDirFilesRules.summarize(
            dirFileDisplayNames = listOf("第1话.mp4", "第1话 (1).mp4", "第1话 (2).mp4"),
            referencedNames = setOf("第1话.mp4"),
        )
        assertEquals(3, summary.total)
        assertEquals(2, summary.orphan)
    }

    @Test
    fun `显示名逐字相等才算有记录（大小写与扩展名都不放宽）`() {
        assertFalse(DownloadDirFilesRules.isOrphan("第1话.mp4", setOf("第1话.mp4")))
        assertTrue(DownloadDirFilesRules.isOrphan("第1话.MP4", setOf("第1话.mp4")))
        assertTrue(DownloadDirFilesRules.isOrphan("第1话.m4a", setOf("第1话.mp4")))
        assertTrue(DownloadDirFilesRules.isOrphan(" 第1话.mp4", setOf("第1话.mp4")))
    }

    @Test
    fun `空目录统计为零`() {
        val summary = DownloadDirFilesRules.summarize(emptyList(), setOf("a.mp4"))
        assertEquals(0, summary.total)
        assertEquals(0, summary.orphan)
    }
}
