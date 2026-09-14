package com.imcys.bilibilias.data.download.segmented

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * 回落单连接前的临时文件整理测试。
 *
 * **这是"分片失败之后会不会写坏文件"的最后一道闸门。**
 * 单连接续传是按「文件长度」判断已下载量的，而分片是各片 seek 到绝对偏移乱序写的 ——
 * 不清洗就直接回落，单连接会把中间的洞当成已下载内容，只补后半段，
 * 产出**长度完全正确、内容却错位**的文件。所以这里逐字节地验长度。
 */
class SegmentedTempFileReconcilerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val total = 4000L
    private val plan = SegmentedDownloadPlan.plan(totalLength = 4000L, concurrency = 4)

    private fun newFile(): File = File(temp.newFolder(), "video.m4s.downloading")

    private fun writeMeta(file: File, planned: List<Segment>, downloaded: List<Long>) {
        val meta = SegmentDownloadMeta(
            totalLength = planned.sumOf { it.length },
            parts = planned.mapIndexed { i, segment ->
                SegmentDownloadMeta.PartProgress(
                    index = segment.index,
                    start = segment.start,
                    endInclusive = segment.endInclusive,
                    downloaded = downloaded.getOrElse(i) { 0L },
                )
            },
        )
        val metaFile = SegmentedDownloader.metaFileFor(file)
        metaFile.parentFile?.mkdirs()
        metaFile.writeText(SegmentDownloadMetaCodec.encode(meta))
    }

    @Test
    fun `截断到从片 0 起连续的字节数，并清掉边车`() {
        val file = newFile()
        // 文件是全长的（片乱序完成/或被预分配过），但片 2 只下了 300 字节
        RandomAccessFile(file, "rw").use { it.setLength(total) }
        writeMeta(file, plan, listOf(1000L, 1000L, 300L, 1000L))

        val resumeFrom = SegmentedTempFileReconciler.reconcile(
            file,
            SegmentedDownloader.metaFileFor(file),
            plan,
        )

        assertEquals("连续前缀 = 片0 + 片1 + 片2 的 300", 2300L, resumeFrom)
        assertEquals("文件必须被截断到这个长度", 2300L, file.length())
        assertFalse("边车必须清掉，否则下次会信任过期进度", SegmentedDownloader.metaFileFor(file).exists())
    }

    @Test
    fun `片 0 没下完时截断到零（从头下，不能留洞）`() {
        val file = newFile()
        RandomAccessFile(file, "rw").use { it.setLength(total) }
        writeMeta(file, plan, listOf(0L, 1000L, 1000L, 1000L))

        val resumeFrom = SegmentedTempFileReconciler.reconcile(
            file,
            SegmentedDownloader.metaFileFor(file),
            plan,
        )

        assertEquals(0L, resumeFrom)
        assertEquals(0L, file.length())
    }

    @Test
    fun `计划不符时整份不可信，截断到零`() {
        val file = newFile()
        RandomAccessFile(file, "rw").use { it.setLength(total) }
        // 边车是 2 片的计划，本次按 4 片整理
        writeMeta(file, SegmentedDownloadPlan.plan(total, 2), listOf(2000L, 2000L))

        val resumeFrom = SegmentedTempFileReconciler.reconcile(
            file,
            SegmentedDownloader.metaFileFor(file),
            plan,
        )

        assertEquals(0L, resumeFrom)
        assertEquals(0L, file.length())
        assertFalse(SegmentedDownloader.metaFileFor(file).exists())
    }

    @Test
    fun `边车声称的进度超过文件实际长度时，只信文件长度`() {
        val file = newFile()
        // 文件只有 100 字节，边车却声称全下完了
        RandomAccessFile(file, "rw").use { it.setLength(100) }
        writeMeta(file, plan, listOf(1000L, 1000L, 1000L, 1000L))

        val resumeFrom = SegmentedTempFileReconciler.reconcile(
            file,
            SegmentedDownloader.metaFileFor(file),
            plan,
        )

        assertEquals("超出文件长度的进度一律不算数", 100L, resumeFrom)
        assertEquals(100L, file.length())
    }

    @Test
    fun `边车损坏或不存在时截断到零，且不抛异常`() {
        val file = newFile()
        RandomAccessFile(file, "rw").use { it.setLength(total) }
        SegmentedDownloader.metaFileFor(file).writeText("{ 这不是 JSON")

        val resumeFrom = SegmentedTempFileReconciler.reconcile(
            file,
            SegmentedDownloader.metaFileFor(file),
            plan,
        )

        assertEquals(0L, resumeFrom)
        assertEquals(0L, file.length())
    }

    @Test
    fun `临时文件不存在时安全返回 0`() {
        val file = newFile()

        val resumeFrom = SegmentedTempFileReconciler.reconcile(
            file,
            SegmentedDownloader.metaFileFor(file),
            plan,
        )

        assertEquals(0L, resumeFrom)
        assertFalse(file.exists())
    }

    @Test
    fun `远程长度未知（计划为空）时也截断到零`() {
        val file = newFile()
        RandomAccessFile(file, "rw").use { it.setLength(total) }
        writeMeta(file, plan, listOf(1000L, 1000L, 1000L, 1000L))

        val resumeFrom = SegmentedTempFileReconciler.reconcile(
            file,
            SegmentedDownloader.metaFileFor(file),
            emptyList(),
        )

        assertEquals(0L, resumeFrom)
        assertEquals(0L, file.length())
    }
}
