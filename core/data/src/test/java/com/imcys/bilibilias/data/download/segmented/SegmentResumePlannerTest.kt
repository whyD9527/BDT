package com.imcys.bilibilias.data.download.segmented

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 续传决策测试：**这条片本次该从哪个绝对偏移写起**。
 *
 * 决策错了的后果分两种，都很糟：
 * - 少算了 → 少下/漏下一段，文件中间有洞；
 * - 多算了 → 把一段数据写到它不属于的位置，**文件长度完全正确、内容却是错的**。
 */
class SegmentResumePlannerTest {

    private val plan = SegmentedDownloadPlan.plan(totalLength = 4000L, concurrency = 4)

    private fun metaWith(downloaded: List<Long>) = SegmentDownloadMeta(
        totalLength = plan.sumOf { it.length },
        parts = plan.mapIndexed { i, segment ->
            SegmentDownloadMeta.PartProgress(
                index = segment.index,
                start = segment.start,
                endInclusive = segment.endInclusive,
                downloaded = downloaded.getOrElse(i) { 0L },
            )
        },
    )

    @Test
    fun `没有元数据时全部从 0 开始`() {
        val parts = SegmentResumePlanner.resolve(plan, null)
        assertEquals(plan.size, parts.size)
        parts.forEachIndexed { i, part ->
            assertEquals(0L, part.alreadyDownloaded)
            assertEquals(plan[i].start, part.writeOffset)
            assertFalse(part.isComplete)
        }
    }

    @Test
    fun `元数据一致时按各片进度续传`() {
        val parts = SegmentResumePlanner.resolve(plan, metaWith(listOf(400L, 0L, 500L, 1000L)))

        assertEquals(400L, parts[0].alreadyDownloaded)
        assertEquals(plan[0].start + 400L, parts[0].writeOffset)
        assertEquals("bytes=${plan[0].start + 400}-${plan[0].endInclusive}", parts[0].rangeHeader)
        assertFalse("片 0 只下了 400/1000", parts[0].isComplete)
        assertEquals(600L, parts[0].remaining)

        assertEquals(0L, parts[1].alreadyDownloaded)
        assertEquals("bytes=${plan[1].start}-${plan[1].endInclusive}", parts[1].rangeHeader)

        assertTrue("片 3 已下满", parts[3].isComplete)
        assertFalse("片 2 只下了一半", parts[2].isComplete)
        assertTrue(parts[2].remaining in 1..(plan[2].length - 1))
    }

    @Test
    fun `已下满的片没有 Range 头（调用方应跳过）`() {
        val parts = SegmentResumePlanner.resolve(plan, metaWith(listOf(1000L, 1000L, 1000L, 1000L)))
        parts.forEach {
            assertTrue(it.isComplete)
            assertNull(it.rangeHeader)
            assertEquals(0L, it.remaining)
        }
    }

    @Test
    fun `元数据与计划不符时完全不信任，全部重下`() {
        // 片数不同的计划
        val foreign = SegmentedDownloadPlan.plan(totalLength = 4000L, concurrency = 2)
        val foreignMeta = SegmentDownloadMeta(
            totalLength = 4000L,
            parts = foreign.map {
                SegmentDownloadMeta.PartProgress(it.index, it.start, it.endInclusive, it.length)
            },
        )
        SegmentResumePlanner.resolve(plan, foreignMeta).forEach {
            assertEquals("计划变了就必须从 0 重下", 0L, it.alreadyDownloaded)
        }

        // 总长度被改过
        val tampered = metaWith(listOf(1000L, 1000L, 1000L, 1000L))
            .copy(totalLength = 8000L)
        SegmentResumePlanner.resolve(plan, tampered).forEach {
            assertEquals(0L, it.alreadyDownloaded)
        }
    }

    @Test
    fun `越界的进度会被整份拒绝（不去猜哪一片是坏的）`() {
        // matches() 要求 downloaded ∈ 0..length，所以越界的元数据**整份不可信** → 全部从 0 重下。
        // 这比"只修正坏的那一片"更安全：越界说明元数据已经不可信了，不能只挑一处修。
        // （`resolve` 内部还有一道 coerceIn 兜底，但 matches 通过时它不可达 —— 属于纵深防御。）
        val bad = metaWith(listOf(999999L, -5L, 1000L, 0L))
        val parts = SegmentResumePlanner.resolve(plan, bad)

        parts.forEach {
            assertEquals("越界元数据必须整份作废", 0L, it.alreadyDownloaded)
            assertFalse(it.isComplete)
        }
        assertTrue(parts.all { it.alreadyDownloaded in 0..it.segment.length })
    }

    @Test
    fun `剩余字节数与写入偏移互不重叠`() {
        val parts = SegmentResumePlanner.resolve(plan, metaWith(listOf(300L, 0L, 1000L, 40L)))
        parts.filter { !it.isComplete }.forEach { part ->
            assertEquals(
                "写到片尾刚好用掉剩余字节数",
                part.segment.endInclusive,
                part.writeOffset + part.remaining - 1,
            )
            assertTrue(part.writeOffset >= part.segment.start)
            assertTrue(part.writeOffset <= part.segment.endInclusive)
        }
    }

    // ---------------- 连续前缀长度（回落单连接时用） ----------------

    @Test
    fun `连续前缀只算到第一个未完成的片`() {
        // 片 0 完成、片 1 半途、片 2/3 完成（顺序不定）
        val meta = metaWith(listOf(1000L, 200L, 1000L, 1000L))
        assertEquals(
            "片 1 只下了一部分，后面的片即使下完了也不能算进连续前缀",
            plan[0].length + 200L,
            meta.contiguousPrefixLength(),
        )
    }

    @Test
    fun `片 0 没下完时连续前缀只到它已经落盘的那部分`() {
        assertEquals(0L, metaWith(listOf(0L, 1000L, 1000L, 1000L)).contiguousPrefixLength())
        // 片 0 落了 10 字节 → 连续前缀就是 10（这 10 字节是真在盘上的）
        assertEquals(10L, metaWith(listOf(10L, 1000L, 1000L, 1000L)).contiguousPrefixLength())
    }

    @Test
    fun `全部完成时连续前缀等于总长`() {
        val meta = metaWith(listOf(1000L, 1000L, 1000L, 1000L))
        assertEquals(4000L, meta.contiguousPrefixLength())
    }

    @Test
    fun `空元数据的连续前缀为零`() {
        assertEquals(0L, SegmentDownloadMeta(totalLength = 0L, parts = emptyList()).contiguousPrefixLength())
    }

    // ---------------- 进度函数 ----------------

    @Test
    fun `进度函数与旧的 Map 版本一致`() {
        val total = 1000L
        assertEquals(0f, SegmentedDownloadPlan.progressOf(0L, total), 0.0001f)
        assertEquals(0.5f, SegmentedDownloadPlan.progressOf(500L, total), 0.0001f)
        assertEquals(1f, SegmentedDownloadPlan.progressOf(1000L, total), 0.0001f)
        assertEquals("超出要夹到 1", 1f, SegmentedDownloadPlan.progressOf(99999L, total), 0.0001f)
        assertEquals("负数要夹到 0", 0f, SegmentedDownloadPlan.progressOf(-5L, total), 0.0001f)
        assertEquals("总长未知不给进度", 0f, SegmentedDownloadPlan.progressOf(100L, -1), 0.0001f)

        assertEquals(
            SegmentedDownloadPlan.aggregateProgress(mapOf(0 to 300L, 1 to 200L), total),
            SegmentedDownloadPlan.progressOf(500L, total),
            0.0001f,
        )
    }
}
