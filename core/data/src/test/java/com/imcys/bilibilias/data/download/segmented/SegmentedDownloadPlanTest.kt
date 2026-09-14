package com.imcys.bilibilias.data.download.segmented

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分片规划的**边界**测试。
 *
 * 这里错的后果很直接：片区间不严丝合缝 → 文件缺字节或写重叠（后者更糟，
 * 因为文件长度看着是对的，内容却是坏的）；阈值/条件判断错 → 该分片时不分片，
 * 或者在不支持 Range 的服务器上分片导致整段失败。
 */
class SegmentedDownloadPlanTest {

    private val mb = 1024L * 1024L

    // ---------------- plan：区间必须严丝合缝 ----------------

    @Test
    fun `分片严丝合缝覆盖整个文件`() {
        val total = 10L * mb + 12345L   // 故意不是并发数的整数倍
        val segments = SegmentedDownloadPlan.plan(total, concurrency = 4)

        assertEquals(4, segments.size)
        assertEquals("首片必须从 0 开始", 0L, segments.first().start)
        assertEquals("末片必须覆盖到最后一个字节", total - 1, segments.last().endInclusive)
        assertEquals("各片长度之和必须等于文件长度", total, segments.sumOf { it.length })

        segments.zipWithNext { a, b ->
            assertEquals("片之间不能有空隙/重叠", a.endInclusive + 1, b.start)
        }
    }

    @Test
    fun `各片长度尽量均衡`() {
        val segments = SegmentedDownloadPlan.plan(10L * mb + 3, concurrency = 4)
        val lengths = segments.map { it.length }
        assertTrue("最大最小之差不应超过 1，实际=$lengths",
            lengths.max() - lengths.min() <= 1)
    }

    @Test
    fun `片数不超过并发数，也不超过总字节数`() {
        assertEquals(4, SegmentedDownloadPlan.plan(1000L * mb, concurrency = 4).size)
        // 文件只有 2 字节时不该切出 4 片
        assertEquals(2, SegmentedDownloadPlan.plan(2, concurrency = 4).size)
        assertEquals(1, SegmentedDownloadPlan.plan(1, concurrency = 4).size)
        assertEquals(1, SegmentedDownloadPlan.plan(1000L * mb, concurrency = 1).size)
    }

    @Test
    fun `长度非法时返回空列表（调用方据此回落单连接）`() {
        assertTrue(SegmentedDownloadPlan.plan(0).isEmpty())
        assertTrue(SegmentedDownloadPlan.plan(-1).isEmpty())
        assertTrue(SegmentedDownloadPlan.plan(1000, concurrency = 0).isEmpty())
    }

    @Test
    fun `单片也要能完整覆盖`() {
        val total = 12345L
        val only = SegmentedDownloadPlan.plan(total, concurrency = 1).single()
        assertEquals(0L, only.start)
        assertEquals(total - 1, only.endInclusive)
        assertEquals(total, only.length)
    }

    // ---------------- shouldSegment：阈值与条件 ----------------

    @Test
    fun `达到阈值且允许时才分片`() {
        val threshold = SegmentedDownloadPlan.MIN_SIZE_FOR_SEGMENTED
        assertTrue(SegmentedDownloadPlan.shouldSegment(threshold, acceptRanges = true, enabled = true))
        assertFalse("差 1 字节不应分片",
            SegmentedDownloadPlan.shouldSegment(threshold - 1, acceptRanges = true, enabled = true))
    }

    @Test
    fun `关掉开关或并发为1时不分期`() {
        val big = 100L * mb
        assertFalse(SegmentedDownloadPlan.shouldSegment(big, acceptRanges = true, enabled = false))
        assertFalse(SegmentedDownloadPlan.shouldSegment(big, acceptRanges = true, enabled = true, concurrency = 1))
    }

    @Test
    fun `服务器明确不支持 Range 时不分片，未声明则允许尝试`() {
        val big = 100L * mb
        assertFalse(SegmentedDownloadPlan.shouldSegment(big, acceptRanges = false, enabled = true))
        // 很多 CDN 不在 HEAD 里声明 Accept-Ranges，但确实支持 —— 现有断点续传就是靠这个
        assertTrue(SegmentedDownloadPlan.shouldSegment(big, acceptRanges = null, enabled = true))
        assertTrue(SegmentedDownloadPlan.shouldSegment(big, acceptRanges = true, enabled = true))
    }

    @Test
    fun `长度未知时不分片`() {
        assertFalse(SegmentedDownloadPlan.shouldSegment(-1, acceptRanges = true, enabled = true))
        assertFalse(SegmentedDownloadPlan.shouldSegment(0, acceptRanges = true, enabled = true))
    }

    // ---------------- Range 头：必须是闭区间 ----------------

    @Test
    fun `Range 头是闭区间`() {
        assertEquals("bytes=0-99", Segment(0, 0, 99).rangeHeader)
        assertEquals("bytes=100-199", Segment(1, 100, 199).rangeHeader)
        // 单字节片
        assertEquals("bytes=1023-1023", Segment(0, 1023, 1023).rangeHeader)
    }

    @Test
    fun `Range 头与实际规划一致`() {
        val total = 10L * mb + 7
        SegmentedDownloadPlan.plan(total, 4).forEach { segment ->
            assertEquals("bytes=${segment.start}-${segment.endInclusive}", segment.rangeHeader)
        }
    }

    // ---------------- 进度聚合 ----------------

    @Test
    fun `进度聚合等于各片之和除以总长度`() {
        val total = 1000L
        assertEquals(0f, SegmentedDownloadPlan.aggregateProgress(emptyMap(), total), 0.0001f)
        assertEquals(0f, SegmentedDownloadPlan.aggregateProgress(mapOf(0 to 0L, 1 to 0L), total), 0.0001f)
        assertEquals(0.5f, SegmentedDownloadPlan.aggregateProgress(mapOf(0 to 300L, 1 to 200L), total), 0.0001f)
        assertEquals(1f, SegmentedDownloadPlan.aggregateProgress(mapOf(0 to 600L, 1 to 400L), total), 0.0001f)
    }

    @Test
    fun `进度不会越界，负值当0`() {
        val total = 1000L
        assertEquals(1f, SegmentedDownloadPlan.aggregateProgress(mapOf(0 to 5000L), total), 0.0001f)
        assertEquals(0f, SegmentedDownloadPlan.aggregateProgress(mapOf(0 to -100L), total), 0.0001f)
        // 总长度未知时不给进度
        assertEquals(0f, SegmentedDownloadPlan.aggregateProgress(mapOf(0 to 100L), -1), 0.0001f)
    }

    // ---------------- 设置项解析（开关 / 并发数）----------------
    //
    // 这一段守的是"设置项把功能弄坏"这类问题：
    // proto 用普通 bool 表示开关 → 升级后默认变成"关"，功能静默消失；
    // 并发数存进 0 或 1 → 开关明明是开的，却永远不分片。两者都极难排查。

    @Test
    fun `没设置过开关时默认开（不能因为加了设置项就把功能默认关掉）`() {
        assertTrue(
            "未设置必须映射成默认开，否则老用户升级后分片下载会静默消失",
            SegmentedDownloadPlan.resolveEnabled(null),
        )
        assertTrue(SegmentedDownloadPlan.resolveEnabled(true))
        assertFalse("显式关掉才是关", SegmentedDownloadPlan.resolveEnabled(false))
    }

    @Test
    fun `没设置过并发数时用默认值`() {
        assertEquals(
            SegmentedDownloadPlan.DEFAULT_CONCURRENCY,
            SegmentedDownloadPlan.resolveConcurrency(null),
        )
        assertEquals(4, SegmentedDownloadPlan.DEFAULT_CONCURRENCY)
    }

    @Test
    fun `并发数被夹在安全范围内`() {
        assertEquals(2, SegmentedDownloadPlan.resolveConcurrency(2))
        assertEquals(4, SegmentedDownloadPlan.resolveConcurrency(4))
        assertEquals(8, SegmentedDownloadPlan.resolveConcurrency(8))

        // 0/1 会让 shouldSegment 恒为 false（"开关开着却从不分片"），必须夹到下限
        assertEquals(2, SegmentedDownloadPlan.resolveConcurrency(1))
        assertEquals(2, SegmentedDownloadPlan.resolveConcurrency(0))
        assertEquals(2, SegmentedDownloadPlan.resolveConcurrency(-7))

        assertEquals(8, SegmentedDownloadPlan.resolveConcurrency(9))
        assertEquals(8, SegmentedDownloadPlan.resolveConcurrency(1000))
    }

    @Test
    fun `解析后的默认设置必须真的能触发分片`() {
        // 端到端地把"用户什么都没设置"这条路径走一遍
        val enabled = SegmentedDownloadPlan.resolveEnabled(null)
        val concurrency = SegmentedDownloadPlan.resolveConcurrency(null)

        assertTrue(
            "默认设置下，大文件必须走分片",
            SegmentedDownloadPlan.shouldSegment(
                totalLength = 100L * mb,
                acceptRanges = true,
                enabled = enabled,
                concurrency = concurrency,
            ),
        )
        assertEquals("默认应切出 4 片", 4, SegmentedDownloadPlan.plan(100L * mb, concurrency).size)
    }

    @Test
    fun `夹过之后的任何取值都不会让分片开关失效`() {
        // 无论设置项里被塞进什么数字，解析结果都必须是"合法且能分片"的
        for (raw in listOf(Int.MIN_VALUE, -1, 0, 1, 2, 4, 8, 9, Int.MAX_VALUE)) {
            val concurrency = SegmentedDownloadPlan.resolveConcurrency(raw)
            assertTrue("raw=$raw 解析成 $concurrency，越界了", concurrency in 2..8)
            assertTrue(
                "raw=$raw 解析成 $concurrency 后应当仍可分片",
                SegmentedDownloadPlan.shouldSegment(
                    totalLength = 100L * mb,
                    acceptRanges = true,
                    enabled = true,
                    concurrency = concurrency,
                ),
            )
            assertEquals(concurrency, SegmentedDownloadPlan.plan(100L * mb, concurrency).size)
        }
    }
}
