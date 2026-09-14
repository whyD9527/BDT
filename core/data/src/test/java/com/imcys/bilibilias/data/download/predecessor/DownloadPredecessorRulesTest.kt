package com.imcys.bilibilias.data.download.predecessor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前置任务规则的测试。
 *
 * 守的是第十八轮审查里那条：**封面为 null 时请求空 URL → 整集下载失败**
 * （前置的附加内容是可选活儿，不该把整集拖下水）。
 */
class DownloadPredecessorRulesTest {

    @Test
    fun `封面为空时不发请求`() {
        // 关键用例：这条判据一旦失效，空 URL 会被真的发出去并抛异常
        assertFalse(DownloadPredecessorRules.canFetchCover(null))
        assertFalse(DownloadPredecessorRules.canFetchCover(""))
        assertFalse(DownloadPredecessorRules.canFetchCover("   "))
    }

    @Test
    fun `有封面地址时才发请求`() {
        assertTrue(DownloadPredecessorRules.canFetchCover("https://i0.hdslb.com/bfs/archive/a.jpg"))
        assertTrue(DownloadPredecessorRules.canFetchCover("http://i0.hdslb.com/bfs/archive/a.jpg"))
    }

    @Test
    fun `扩展名取不到时回落 jpg，不会拼出 _pic null `() {
        assertEquals("jpg", DownloadPredecessorRules.coverExtension(null))
        assertEquals("jpg", DownloadPredecessorRules.coverExtension(""))
        // URL 里没有点（或最后一个点后面是一长串查询参数）→ 不能拿它当扩展名
        assertEquals("jpg", DownloadPredecessorRules.coverExtension("https://i0.hdslb.com/bfs/archive/abc"))
        assertEquals(
            "jpg",
            DownloadPredecessorRules.coverExtension("https://i0.hdslb.com/bfs/a.jpg@480w_270h_1c.webp?x=1"),
        )
    }

    @Test
    fun `常见图片后缀原样保留`() {
        assertEquals("jpg", DownloadPredecessorRules.coverExtension("https://i0.hdslb.com/bfs/a.jpg"))
        assertEquals("png", DownloadPredecessorRules.coverExtension("https://i0.hdslb.com/bfs/a.png"))
        assertEquals("webp", DownloadPredecessorRules.coverExtension("https://i0.hdslb.com/bfs/a.webp"))
    }
}
