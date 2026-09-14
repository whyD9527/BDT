package com.imcys.bilibilias.data.download.segmented

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 边车元数据的编解码与"能否信任"的判断测试。
 *
 * 关注两点：
 * 1. **坏数据不能把下载搞失败**：解析失败必须返回 null（当作没有元数据，从头下）；
 * 2. **计划变了就不能信任旧进度**：片数/边界不符时必须判定为不匹配，否则会写出错位的文件。
 */
class SegmentDownloadMetaTest {

    private fun planned(count: Int = 4, total: Long = 4000L): List<Segment> =
        SegmentedDownloadPlan.plan(total, count)

    private fun metaOf(segments: List<Segment>, downloadedEach: Long) = SegmentDownloadMeta(
        totalLength = segments.sumOf { it.length },
        parts = segments.map {
            SegmentDownloadMeta.PartProgress(it.index, it.start, it.endInclusive, downloadedEach)
        },
    )

    // ---------------- 编解码 ----------------

    @Test
    fun `编解码往返不丢信息`() {
        val segments = planned()
        val meta = metaOf(segments, downloadedEach = 100)

        val decoded = SegmentDownloadMetaCodec.decode(SegmentDownloadMetaCodec.encode(meta))

        assertEquals(meta, decoded)
        assertEquals(meta.version, decoded?.version)
        assertEquals(meta.totalLength, decoded?.totalLength)
    }

    @Test
    fun `坏数据解析失败返回 null（不能把下载搞失败）`() {
        assertNull(SegmentDownloadMetaCodec.decode(""))
        assertNull(SegmentDownloadMetaCodec.decode("not json at all"))
        assertNull(SegmentDownloadMetaCodec.decode("{\"totalLength\":\"oops\"}"))
        assertNull(SegmentDownloadMetaCodec.decode("[]"))
    }

    @Test
    fun `多出未知字段也能解析（向后兼容）`() {
        val json = """{"version":1,"totalLength":100,"parts":[],"futureField":123}"""
        val decoded = SegmentDownloadMetaCodec.decode(json)
        assertEquals(100L, decoded?.totalLength)
        assertTrue(decoded?.parts?.isEmpty() == true)
    }

    // ---------------- 是否可信 ----------------

    @Test
    fun `计划一致时可以信任`() {
        val segments = planned()
        val meta = metaOf(segments, downloadedEach = 50)
        assertTrue(meta.matches(segments))
    }

    @Test
    fun `片数或边界不符时不可信`() {
        val segments = planned(count = 4)
        assertFalse("片数不同", metaOf(planned(count = 2, total = 4000L), 50).matches(segments))
        assertFalse("总长度不同", metaOf(planned(count = 4, total = 8000L), 50).matches(segments))

        // 边界被改掉（模拟 TotalLength 变化后重算出的计划）
        val tampered = listOf(segments.first().copy(endInclusive = segments.first().endInclusive - 1))
        assertFalse(metaOf(tampered, 50).matches(segments))
    }

    @Test
    fun `已下载字节数越界时不可信`() {
        val segments = planned()
        val bad = SegmentDownloadMeta(
            totalLength = segments.sumOf { it.length },
            parts = segments.map {
                SegmentDownloadMeta.PartProgress(it.index, it.start, it.endInclusive, downloaded = it.length + 1)
            },
        )
        assertFalse("某片自称下载量超过片长，说明元数据坏了", bad.matches(segments))
    }

    @Test
    fun `自身声明的总长与各片之和不符时不可信`() {
        val segments = planned()
        val meta = metaOf(segments, downloadedEach = 50)
        assertTrue("自身一致就该可信", meta.matches(segments))

        // 边界都对、总长却写着别的数 → 自相矛盾，整份不可信。
        // （写入偏移是靠这些字段算出来的，不能只挑"看着还行"的字段用。）
        assertFalse(
            "总长自相矛盾",
            meta.copy(totalLength = meta.totalLength + 1).matches(segments),
        )
        assertFalse(
            "总长为 0 也不对",
            meta.copy(totalLength = 0L).matches(segments),
        )
    }

    // ---------------- 汇总 ----------------

    @Test
    fun `已下载总量与完成判定`() {
        val segments = planned()
        val partDone = segments.mapIndexed { i, s ->
            SegmentDownloadMeta.PartProgress(
                s.index, s.start, s.endInclusive,
                downloaded = if (i == 0) s.length else 0L,   // 只有第一片下完
            )
        }
        val meta = SegmentDownloadMeta(totalLength = 4000L, parts = partDone)

        assertEquals(segments.first().length, meta.downloadedTotal)
        assertTrue(meta.parts.first().isCompleted)
        assertFalse(meta.parts[1].isCompleted)
    }
}
