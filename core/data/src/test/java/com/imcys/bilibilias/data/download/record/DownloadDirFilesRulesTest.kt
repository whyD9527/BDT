package com.imcys.bilibilias.data.download.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    // ---- 2026-10-05 真机复验发现的问题：目录清单里只剩"文件夹行"，真实文件一个都没有 ----

    @Test
    fun `RELATIVE_PATH 模式必须带完整 Download 前缀`() {
        assertEquals("Download/BDT/%", DownloadDirFilesRules.relativePathPattern("Download/BDT"))
        assertEquals("Download/BDT/%", DownloadDirFilesRules.relativePathPattern("/Download/BDT/"))
        assertEquals(
            "Download/BiliDownloader/%",
            DownloadDirFilesRules.relativePathPattern("Download/BiliDownloader"),
        )
        // 回归点：以前是去掉 Download 前缀的 `BDT%` —— RELATIVE_PATH 是 `Download/BDT/xxx.mp4`，
        // 那样一条真实文件都匹配不到（只剩满足 `_data LIKE '%/BDT%'` 的"文件夹行"）。
        assertNotEquals("BDT%", DownloadDirFilesRules.relativePathPattern("Download/BDT"))
    }

    @Test
    fun `_data 兜底模式带两侧通配`() {
        assertEquals("%/Download/BDT/%", DownloadDirFilesRules.dataPathPattern("Download/BDT"))
        assertEquals("%/Download/BDT/%", DownloadDirFilesRules.dataPathPattern("/Download/BDT/"))
    }

    @Test
    fun `目录自己那一行要剔掉（0B 且名字等于被查目录名）`() {
        assertTrue(
            DownloadDirFilesRules.isDirectoryRow(
                displayName = "BDT",
                queriedRelativePath = "Download/BDT",
                sizeBytes = 0L,
            )
        )
        // 真机上这一行是走 `_data` 兜底进来的（RELATIVE_PATH 是父目录 `Download/`），
        // 所以判定**不能**要求"行相对路径 == 查询目录" —— 这里用"不带行路径"的参数形态钉住它。
        assertTrue(
            DownloadDirFilesRules.isDirectoryRow("BDT", "/Download/BDT/", 0L)
        )
        // 文件（有体积）不能因为名字恰好是 BDT 就被剔掉
        assertFalse(
            DownloadDirFilesRules.isDirectoryRow("BDT", "Download/BDT", 1024L)
        )
        // 目录下真实文件的那一行
        assertFalse(
            DownloadDirFilesRules.isDirectoryRow("第1话.mp4", "Download/BDT", 123L)
        )
        // 别的目录：名字不等于被查目录名，不剔
        assertFalse(
            DownloadDirFilesRules.isDirectoryRow("BiliDownloader", "Download/BDT", 0L)
        )
    }
}
