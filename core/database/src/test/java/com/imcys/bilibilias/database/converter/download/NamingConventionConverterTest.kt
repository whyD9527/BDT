package com.imcys.bilibilias.database.converter.download

import com.imcys.bilibilias.database.entity.download.NamingConventionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `naming_convention_info` 的 JSON 往返测试（2026-09-15 全量复审 H12）。
 *
 * 守的不变量：**写进去什么、读回来就得是什么**（类型 + 字段一个不少）。
 * 原实现把 `ruleType` 读错位一格（Video=1 被读成 Donghua、Donghua=2 变 null），
 * 而下载完成时会把读回来的值原样写回 DB → 每下载一集就永久改写/清空这一列。
 */
class NamingConventionConverterTest {

    private val converter = NamingConventionConverter()

    private fun roundTrip(info: NamingConventionInfo?): NamingConventionInfo? =
        converter.fromString(converter.stringToDownloadStage(info))

    @Test
    fun `Video 往返后仍是 Video，字段一个都不少`() {
        val video = NamingConventionInfo.Video(
            title = "标题",
            pTitle = "P1",
            author = "UP主",
            bvId = "BV1xx411c7mD",
            aid = "123",
            cid = "456",
            p = "2",
            collectionTitle = "合集",
            collectionSeasonTitle = "章节",
        )
        val back = roundTrip(video)
        assertTrue("Video 必须读回 Video（原实现会读成 Donghua）", back is NamingConventionInfo.Video)
        assertEquals(video, back)
    }

    @Test
    fun `Donghua 往返后仍是 Donghua，seasonTitle 不丢`() {
        val donghua = NamingConventionInfo.Donghua(
            title = "番剧",
            episodeTitle = "第 1 话",
            episodeNumber = "1",
            cid = "789",
            seasonTitle = "季度",
        )
        val back = roundTrip(donghua)
        assertTrue("Donghua 必须读回 Donghua（原实现会变成 null）", back is NamingConventionInfo.Donghua)
        assertEquals(donghua, back)
    }

    @Test
    fun `实体里 Video=1 Donghua=2，读取必须与写入同源`() {
        // 直接钉住"错位一格"这个本体：1 是 Video，不是 Donghua
        assertEquals(1, NamingConventionInfo.Video().ruleType)
        assertEquals(2, NamingConventionInfo.Donghua().ruleType)
        assertTrue(converter.fromString("""{"ruleType":1}""") is NamingConventionInfo.Video)
        assertTrue(converter.fromString("""{"ruleType":2}""") is NamingConventionInfo.Donghua)
        // 更早版本可能写过 0，按 Video 兼容
        assertTrue(converter.fromString("""{"ruleType":0}""") is NamingConventionInfo.Video)
    }

    @Test
    fun `缺失字段读回来是 null 而不是空串`() {
        val back = converter.fromString("""{"ruleType":1,"title":"只有标题"}""")
        assertTrue(back is NamingConventionInfo.Video)
        back as NamingConventionInfo.Video
        assertEquals("只有标题", back.title)
        assertNull(back.pTitle)
        assertNull(back.collectionTitle)
        assertNull(back.collectionSeasonTitle)

        val donghua = converter.fromString("""{"ruleType":2,"title":"番剧"}""")
        assertTrue(donghua is NamingConventionInfo.Donghua)
        donghua as NamingConventionInfo.Donghua
        assertNull(donghua.episodeTitle)
        assertNull(donghua.seasonTitle)
    }

    @Test
    fun `坏 JSON 与认不出的 ruleType 一律返回 null，绝不抛给 Room`() {
        // 原实现对非法 JSON 会抛 JSONException —— 那会让整个下载列表查询崩掉
        assertNull(converter.fromString("不是 JSON"))
        assertNull(converter.fromString(""))
        assertNull(converter.fromString("   "))
        assertNull(converter.fromString("[]"))
        assertNull(converter.fromString("""{"ruleType":99}"""))
        assertNull(converter.fromString("""{"noRuleType":1}"""))
    }

    @Test
    fun `null 写成 SQL NULL，不是空 JSON`() {
        assertNull("不要落一个 {} 脏值", converter.stringToDownloadStage(null))
        assertNull(converter.fromString(null))
    }
}
