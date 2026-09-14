package com.imcys.bilibilias.data.download.queue

import com.imcys.bilibilias.database.entity.download.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载队列规则测试。
 *
 * 这几条规则原先散在 `:app` 的 `NewDownloadManager` 里、**没有任何测试**，
 * 结果长出了第十八轮审查里那几类问题（失败任务静默跳过、暂停后队列不再启动、
 * 选任务与并发上限的竞态）。这里把规则钉住，改逻辑时必须让测试说话。
 */
class DownloadQueueRulesTest {

    private data class Task(val platformId: String, val state: DownloadState)

    private fun merge(
        existing: List<Task>,
        incoming: List<Task>,
    ) = DownloadQueueRules.mergeTasks(
        existing = existing,
        incoming = incoming,
        platformIdOf = { it.platformId },
        stateOf = { it.state },
    )

    // ---------------------------------------------------------------- 合并 / 去重

    @Test
    fun `新内容直接追加`() {
        val merged = merge(
            existing = listOf(Task("BV1", DownloadState.DOWNLOADING)),
            incoming = listOf(Task("BV2", DownloadState.WAITING)),
        )
        assertEquals(listOf("BV1", "BV2"), merged.map { it.platformId })
    }

    @Test
    fun `已在处理中的同一内容不重复添加`() {
        listOf(
            DownloadState.WAITING,
            DownloadState.DOWNLOADING,
            DownloadState.MERGING,
            DownloadState.PAUSE,
            DownloadState.PRE_TASK,
            DownloadState.COMPLETED,
        ).forEach { state ->
            val merged = merge(
                existing = listOf(Task("BV1", state)),
                incoming = listOf(Task("BV1", DownloadState.WAITING)),
            )
            assertEquals("state=$state 时不该重复添加", 1, merged.size)
            assertEquals("$state 的旧条目应原样保留（不能被覆盖掉进度）", state, merged[0].state)
        }
    }

    @Test
    fun `失败的内容可以重下：替换旧条目而不是静默跳过`() {
        val merged = merge(
            existing = listOf(Task("BV1", DownloadState.ERROR)),
            incoming = listOf(Task("BV1", DownloadState.WAITING)),
        )
        assertEquals("应替换而不是跳过", 1, merged.size)
        assertEquals(DownloadState.WAITING, merged[0].state)
    }

    @Test
    fun `替换失败条目后 platformId 仍然唯一（下载列表拿它当 Compose key）`() {
        val merged = merge(
            existing = listOf(
                Task("BV1", DownloadState.ERROR),
                Task("BV2", DownloadState.DOWNLOADING),
            ),
            incoming = listOf(
                Task("BV1", DownloadState.WAITING),
                Task("BV3", DownloadState.WAITING),
            ),
        )
        val ids = merged.map { it.platformId }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(listOf("BV1", "BV2", "BV3"), ids)
    }

    @Test
    fun `空的新批次不动现有列表`() {
        val existing = listOf(Task("BV1", DownloadState.DOWNLOADING))
        assertEquals(existing, merge(existing, emptyList()))
    }

    @Test
    fun `同一批里出现重复 platformId 也只留一条`() {
        val merged = merge(
            existing = emptyList(),
            incoming = listOf(
                Task("BV1", DownloadState.WAITING),
                Task("BV1", DownloadState.WAITING),
            ),
        )
        assertEquals(1, merged.size)
    }

    // ---------------------------------------------------------------- 选下一个任务

    private fun items(vararg pairs: Pair<Long, DownloadState>) = pairs.toList()

    @Test
    fun `只挑等待中的任务`() {
        val list = items(
            1L to DownloadState.DOWNLOADING,
            2L to DownloadState.WAITING,
            3L to DownloadState.WAITING,
        )
        assertEquals(1, DownloadQueueRules.nextTaskIndex(list, activeIds = emptySet(), maxConcurrent = 4))
    }

    @Test
    fun `并发上限生效`() {
        val list = items(1L to DownloadState.WAITING)
        assertNull(
            "已有 1 个在跑、上限也是 1 时不该再挑",
            DownloadQueueRules.nextTaskIndex(list, activeIds = setOf(99L), maxConcurrent = 1),
        )
    }

    @Test
    fun `已经在跑的任务不会被重复挑中`() {
        // 这条正是 check-then-act 竞态的护栏：同一个 segmentId 不能起两个 Job
        val list = items(1L to DownloadState.WAITING)
        assertNull(
            DownloadQueueRules.nextTaskIndex(list, activeIds = setOf(1L), maxConcurrent = 4),
        )
    }

    @Test
    fun `没有可开始的任务时返回 null`() {
        assertNull(DownloadQueueRules.nextTaskIndex(emptyList(), emptySet(), 4))
        assertNull(
            DownloadQueueRules.nextTaskIndex(
                items(1L to DownloadState.COMPLETED, 2L to DownloadState.PAUSE),
                emptySet(),
                4,
            ),
        )
    }

    // ---------------------------------------------------------------- 队列收工条件

    @Test
    fun `空队列算收工`() {
        assertTrue(DownloadQueueRules.isQueueDrained(emptyList(), activeCount = 0))
    }

    @Test
    fun `只剩暂停或已终结时收工`() {
        listOf(
            DownloadState.COMPLETED,
            DownloadState.ERROR,
            DownloadState.CANCELLED,
            DownloadState.PAUSE,
        ).forEach { state ->
            assertTrue(
                "state=$state 应当允许队列退出",
                DownloadQueueRules.isQueueDrained(items(1L to state), activeCount = 0),
            )
        }
    }

    @Test
    fun `还有等待中就还不能收工`() {
        assertFalse(
            DownloadQueueRules.isQueueDrained(items(1L to DownloadState.WAITING), activeCount = 0),
        )
    }

    @Test
    fun `还有任务在跑就不能收工`() {
        assertFalse(
            DownloadQueueRules.isQueueDrained(items(1L to DownloadState.COMPLETED), activeCount = 1),
        )
    }

    // ---------------------------------------------------------------- 暂停 / 恢复规则

    @Test
    fun `只有下载中能暂停（合并中不可中断）`() {
        assertTrue(DownloadQueueRules.canPause(DownloadState.DOWNLOADING))
        assertFalse("合并中中断会留下半截成品", DownloadQueueRules.canPause(DownloadState.MERGING))
        assertFalse(DownloadQueueRules.canPause(DownloadState.WAITING))
        assertFalse(DownloadQueueRules.canPause(DownloadState.PAUSE))
    }

    @Test
    fun `只有已暂停能恢复`() {
        assertTrue(DownloadQueueRules.canResume(DownloadState.PAUSE))
        assertFalse(DownloadQueueRules.canResume(DownloadState.DOWNLOADING))
        assertFalse(DownloadQueueRules.canResume(DownloadState.WAITING))
    }
}
