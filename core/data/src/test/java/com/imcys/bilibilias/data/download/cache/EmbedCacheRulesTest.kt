package com.imcys.bilibilias.data.download.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内嵌封面 / 内嵌字幕临时文件清理规则的测试。
 *
 * 守的是第十八轮审查里的低危项：这两类临时件**只有"清空缓存"才会被删**，
 * 下载完成后既不清、也没有别的入口，于是下得越多、cache 里攒得越多。
 */
class EmbedCacheRulesTest {

    @Test
    fun `封面与字幕的临时件都要认领`() {
        assertTrue(
            EmbedCacheRules.isEmbedCacheFile("cover", "embed_cover_173286.jpg"),
        )
        assertTrue(
            EmbedCacheRules.isEmbedCacheFile("cc", "embed_cc_173286_zh-CN.srt"),
        )
    }

    @Test
    fun `别的目录同名文件不认领（避免误伤其他模块的缓存）`() {
        assertFalse(
            EmbedCacheRules.isEmbedCacheFile("frameTemp", "embed_cover_1.jpg"),
        )
        assertFalse(
            EmbedCacheRules.isEmbedCacheFile(null, "embed_cover_1.jpg"),
        )
    }

    @Test
    fun `这两个目录里非 embed_ 前缀的东西不认领`() {
        assertFalse(EmbedCacheRules.isEmbedCacheFile("cover", "something_else.jpg"))
        assertFalse(EmbedCacheRules.isEmbedCacheFile("cc", "notes.txt"))
    }

    @Test
    fun `只有前缀没有内容的名字不认领`() {
        assertFalse(EmbedCacheRules.isEmbedCacheFile("cc", "embed_"))
        assertFalse(EmbedCacheRules.isEmbedCacheFile("cover", null))
        assertFalse(EmbedCacheRules.isEmbedCacheFile(null, null))
    }

    @Test
    fun `本任务产出的临时件按路径逐一认领`() {
        val parentOf: (String) -> String? = { path ->
            path.substringBeforeLast('/', "").substringAfterLast('/', "")
        }
        val owned = EmbedCacheRules.taskOwnedPaths(
            coverPath = "/data/user/0/pkg/cache/cover/embed_cover_173286.jpg",
            subtitlePaths = listOf(
                "/data/user/0/pkg/cache/cc/embed_cc_173286_zh-CN.srt",
                "/data/user/0/pkg/cache/cc/embed_cc_173286_ai-zh.srt",
            ),
            parentDirNameOf = parentOf,
        )
        assertEquals(3, owned.size)
    }

    @Test
    fun `空封面与不在缓存目录里的路径都不认领`() {
        val parentOf: (String) -> String? = { path ->
            path.substringBeforeLast('/', "").substringAfterLast('/', "")
        }
        val owned = EmbedCacheRules.taskOwnedPaths(
            coverPath = "",
            subtitlePaths = listOf(
                "/data/user/0/pkg/files/video/embed_cc_173286_zh-CN.srt", // 不在缓存目录
                "/data/user/0/pkg/cache/cc/embed_cc_173286_zh-CN.srt",
            ),
            parentDirNameOf = parentOf,
        )
        assertEquals(listOf("/data/user/0/pkg/cache/cc/embed_cc_173286_zh-CN.srt"), owned)
    }

    @Test
    fun `同一个路径出现两次只留一条`() {
        val parentOf: (String) -> String? = { "cc" }
        val owned = EmbedCacheRules.taskOwnedPaths(
            coverPath = null,
            subtitlePaths = listOf("a/cc/embed_x.srt", "a/cc/embed_x.srt"),
            parentDirNameOf = parentOf,
        )
        assertEquals(1, owned.size)
    }
}
