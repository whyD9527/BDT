package com.imcys.bilibilias.data.download.startup

import com.imcys.bilibilias.database.entity.download.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重启清理规则的测试。
 *
 * 守的是第十八轮审查里那三条：PAUSE 变幽灵、WAITING/ERROR 被静默删除、
 * 失败任务的临时文件永不清理。
 */
class DownloadStartupRulesTest {

    @Test
    fun `只有已完成会被保留`() {
        assertEquals(
            DownloadStartupRules.StartupAction.KEEP,
            DownloadStartupRules.actionFor(DownloadState.COMPLETED),
        )
    }

    @Test
    fun `还没下完的丢记录但保留文件（还能接着下）`() {
        // 关键：PAUSE 不再被"留在 DB 里却谁都看不到"，而是明确丢弃；
        // 文件留着，下次重新添加同一内容时 Range/分片边车能接着下。
        listOf(
            DownloadState.PAUSE,
            DownloadState.WAITING,
            DownloadState.PRE_TASK,
            DownloadState.DOWNLOADING,
            DownloadState.MERGING,
            DownloadState.POST_TASK,
        ).forEach { state ->
            assertEquals(
                "state=$state 应当丢记录、留文件",
                DownloadStartupRules.StartupAction.DISCARD_KEEP_FILES,
                DownloadStartupRules.actionFor(state),
            )
        }
    }

    @Test
    fun `已经终结的（失败取消）要连临时文件一起清掉`() {
        listOf(DownloadState.ERROR, DownloadState.CANCELLED).forEach { state ->
            assertEquals(
                "state=$state 应当丢记录并清文件",
                DownloadStartupRules.StartupAction.DISCARD_AND_CLEAN,
                DownloadStartupRules.actionFor(state),
            )
        }
    }

    @Test
    fun `每个状态都必须有明确处理方式，不能漏`() {
        // 将来新增状态时，这条会提醒你回来决定它重启后怎么办
        DownloadState.entries.forEach { state ->
            DownloadStartupRules.actionFor(state)
        }
    }

    // ------------------------------------------------------------ 临时文件归属

    @Test
    fun `按前缀认领中间产物，含 downloading 与 downloadpart 边车`() {
        val platformId = "173286"
        listOf(
            "173286_VIDEO.m4s",
            "173286_AUDIO.m4a",
            "173286_VIDEO.m4s.downloading",
            "173286_VIDEO.m4s.downloading.downloadpart",
        ).forEach { name ->
            assertTrue("$name 应当被认领", DownloadStartupRules.isTempFileOf(name, platformId))
        }
    }

    @Test
    fun `前缀必须带下划线，不能误伤相邻编号`() {
        val platformId = "12"
        // 这条就是这个规则里唯一容易写错的地方：只用 "12" 当前缀会连 "123_VIDEO.m4s" 一起删掉
        assertFalse(
            "12 号的任务不能删掉 123 号的文件",
            DownloadStartupRules.isTempFileOf("123_VIDEO.m4s", platformId),
        )
        assertTrue(DownloadStartupRules.isTempFileOf("12_VIDEO.m4s", platformId))
        assertFalse(DownloadStartupRules.isTempFileOf("别的文件.mp4", platformId))
    }

    @Test
    fun `platformId 为空时谁都不认领（避免空前缀匹配一切）`() {
        assertFalse(DownloadStartupRules.isTempFileOf("173286_VIDEO.m4s", ""))
        assertFalse(DownloadStartupRules.isTempFileOf("173286_VIDEO.m4s", "   "))
    }
}
