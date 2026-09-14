package com.imcys.bilibilias.data.download.queue

import com.imcys.bilibilias.database.entity.download.DownloadState

/**
 * 下载队列的**纯规则**（不碰协程、不碰 DB、不碰 Android）。
 *
 * ## 为什么单独抽出来
 * 内存任务列表 `_downloadTasks` 原先的增删改查、去重、选任务规则全都写在
 * `:app` 的 `NewDownloadManager` 里 —— 而 `:app` 的单测在本机**跑不了**（aapt2 是 x86_64，
 * 见交接文档第十五轮）。没有测试的地方就长出了这几类 bug（见第十八轮审查）：
 *
 * - **失败任务静默跳过**：去重只比 `platformId`，于是某个视频失败（ERROR）之后再点下载，
 *   新任务被当成"已经在队列里"直接跳过，界面却仍然弹「已添加到下载队列」；
 * - **重启后暂停任务变幽灵**、**选任务与并发上限的 check-then-act**……
 *   这些都不是"代码写错了一行"，而是**规则没有定义清楚、也没人钉住**。
 *
 * 所以这里只做一件事：把规则写成可以用单测穷举的纯函数。
 * 状态容器仍留在 `:app`（把 `AppDownloadTask` 整体搬进库模块风险太大），
 * 但它必须通过原子更新来套用这些规则。
 */
object DownloadQueueRules {

    /** 队列收工时，任务允许处于的状态：已终结的三种 ＋ 用户主动暂停的 */
    private val SETTLED_STATES = listOf(
        DownloadState.COMPLETED,
        DownloadState.ERROR,
        DownloadState.CANCELLED,
        DownloadState.PAUSE,
    )

    /**
     * 把一批新任务合并进现有列表。
     *
     * - 同一个 `platformId` 只保留一条（下载列表拿它当 Compose key，重复会直接崩）；
     * - **ERROR 视为"可以重下"**：已有的失败条目会被新任务**替换**，而不是让新任务被静默跳过。
     *   替换而不是"直接追加"，是为了不产生重复 key；
     * - 其它状态（排队中/下载中/合并中/暂停）说明这个内容**已经在处理**，跳过是正确行为。
     *
     * 之所以做成泛型 `T`：这样测试不需要依赖 `:app` 的 `AppDownloadTask`。
     */
    fun <T> mergeTasks(
        existing: List<T>,
        incoming: List<T>,
        platformIdOf: (T) -> String,
        stateOf: (T) -> DownloadState,
    ): List<T> {
        if (incoming.isEmpty()) return existing
        val result = existing.toMutableList()
        incoming.forEach { task ->
            val platformId = platformIdOf(task)
            val index = result.indexOfFirst { platformIdOf(it) == platformId }
            when {
                index < 0 -> result.add(task)
                stateOf(result[index]) == DownloadState.ERROR -> result[index] = task
                // else：已经在处理中，跳过
            }
        }
        return result
    }

    /**
     * 选出下一个该开始的任务下标；没有可开始的就返回 null。
     *
     * @param items 按任务列表顺序的 (segmentId, 状态)
     * @param activeIds 已经在跑的 segmentId
     * @param maxConcurrent 并发上限
     */
    fun nextTaskIndex(
        items: List<Pair<Long, DownloadState>>,
        activeIds: Set<Long>,
        maxConcurrent: Int,
    ): Int? {
        if (activeIds.size >= maxConcurrent) return null
        return items
            .indexOfFirst { (id, state) -> state == DownloadState.WAITING && id !in activeIds }
            .takeIf { it >= 0 }
    }

    /**
     * 队列是否可以收工了：没有在跑的、没有等待的，且剩下的都已终结或暂停。
     *
     * 注意 **PAUSE 也算"可以收工"** —— 用户暂停之后队列循环会退出并解绑服务，
     * 这正是"暂停→继续"那条路径出问题的前提（继续时必须重新把队列拉起来）。
     * 反过来说：若 PAUSE 不算收工，只要有任务被暂停，队列循环就永不退出 →
     * 前台服务与「缓存通知」常驻，白白耗电。
     *
     * 实现上只需要判"剩下的都在 [SETTLED_STATES] 里"：WAITING / DOWNLOADING / MERGING
     * 都不在其中，所以"还有等待中的任务"这一条已经被涵盖，不必再单独判一次。
     */
    fun isQueueDrained(
        items: List<Pair<Long, DownloadState>>,
        activeCount: Int,
    ): Boolean =
        activeCount == 0 && items.all { (_, state) -> state in SETTLED_STATES }

    /**
     * 只有"下载中"能被暂停。
     *
     * 合并中（MERGING）刻意不可暂停：中断 ffmpeg 会留下半截成品。
     * 这条以前只是隐含在 `pauseTask` 的早退里，而 `pauseAllTasks` 的过滤条件却把 MERGING 也放进去了 ——
     * 规则写在这里，两边就不会再各说各话。
     */
    fun canPause(state: DownloadState): Boolean = state == DownloadState.DOWNLOADING

    /** 只有"已暂停"能恢复 */
    fun canResume(state: DownloadState): Boolean = state == DownloadState.PAUSE
}
