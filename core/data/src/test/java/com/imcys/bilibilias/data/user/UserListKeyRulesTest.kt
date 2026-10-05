package com.imcys.bilibilias.data.user

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 列表项唯一 key 的测试（守 2026-09-15 复审 H11 那次崩溃）：
 * 分页重叠 / 同视频多分 P 时，任何两条不同的记录都不允许生成同一个 key。
 */
class UserListKeyRulesTest {

    @Test
    fun `视频项优先用 aid`() {
        assertEquals("aid:100", UserListKeyRules.videoKey(aid = 100, bvid = "BV1", fallbackIndex = 0))
    }

    @Test
    fun `没有 aid 时退回 bvid（同一视频不同分 P 也各不相同）`() {
        assertEquals("BV1", UserListKeyRules.videoKey(aid = 0, bvid = "BV1", fallbackIndex = 3))
        assertEquals("BV2", UserListKeyRules.videoKey(aid = 0, bvid = "BV2", fallbackIndex = 3))
    }

    @Test
    fun `aid 与 bvid 都缺失时用下标兜底（不崩，但每项仍不同）`() {
        assertEquals("index:0", UserListKeyRules.videoKey(aid = 0, bvid = "", fallbackIndex = 0))
        assertNotEquals(
            UserListKeyRules.videoKey(0, "", 0),
            UserListKeyRules.videoKey(0, "", 1),
        )
    }

    @Test
    fun `历史项优先 cid；同 bvid 的不同分 P 不会撞 key`() {
        assertEquals("cid:5", UserListKeyRules.historyKey(cid = 5, bvid = "BV1", fallbackIndex = 0))
        assertNotEquals(
            UserListKeyRules.historyKey(cid = 5, bvid = "BV1", fallbackIndex = 0),
            UserListKeyRules.historyKey(cid = 6, bvid = "BV1", fallbackIndex = 0),
        )
        assertEquals("BV1", UserListKeyRules.historyKey(cid = 0, bvid = "BV1", fallbackIndex = 0))
    }

    @Test
    fun `追番项用 seasonId，缺失时下标兜底`() {
        assertEquals("season:77", UserListKeyRules.bangumiKey(seasonId = 77, fallbackIndex = 0))
        assertEquals("index:2", UserListKeyRules.bangumiKey(seasonId = 0, fallbackIndex = 2))
    }

    @Test
    fun `不同来源的 key 带前缀，数值相同也不会冲突`() {
        assertNotEquals(
            UserListKeyRules.videoKey(aid = 8, bvid = "BV1", fallbackIndex = 0),
            UserListKeyRules.historyKey(cid = 8, bvid = "BV1", fallbackIndex = 0),
        )
    }
}
