package com.imcys.bilibilias.data.download.record

import java.util.Locale

/**
 * `download_segment` 的**产物身份语义**（④）：什么是"同一条下载"、什么情况**允许并存两条**。
 *
 * ## 这一版是"规格"，不是"接线"（2026-10-02 用户决定：先只出方案、先不动 DB）
 * 所以本对象**目前没有任何生产调用方** —— 它是给后面两个阶段定的判据，
 * 配了单测把语义钉死（`SegmentIdentityRulesTest`），免得将来拍脑袋改。
 *
 * ## 现状（读代码 ＋ DB 结构得到的，2026-10-02）
 * `DownloadSegment` 表**没有任何唯一索引**（只有 `node_id` / `task_id` 两个普通索引），
 * 而应用层"复用"用的是 [`legacyKeyOf`] = `(platformId, nodeId)`：
 * `DownloadTaskRepository.createSegment` 一查到同键行就 **UPDATE 覆盖**
 * （`downloadMode` / `mediaContainer` / `qualityDescription` 全被新值盖掉）。
 *
 * 于是存在三处**不一致**：
 * 1. **`(platformId, nodeId)` 不含"产物形态"**：同一集先下音频、再下视频 → 只剩一条记录，
 *    它描述的是**最后一次**的形态；先下那份音频文件留在磁盘上却**没有记录**（在「下载目录文件」
 *    里会显示成"孤儿"）；
 * 2. **B4「跳过已下载」用的是 `platformId + downloadMode`**（见 `NewDownloadManager`），
 *    与应用层的 `(platformId, nodeId)` 不是同一个键；
 * 3. **DB 里没有"音质"这一列**：只有 `quality_description`（给人看的字符串，如 `1080P 高清`），
 *    真正的画质/音质选择在下载时由参数带入 —— 这直接决定了"唯一键能不能覆盖音质"这件事
 *    **必须先改 schema**，不是加个 `@Index` 就完事（见 [PROPOSED_UNIQUE_COLUMNS] 的注释）。
 *
 * ## 建议的判据（本项目推荐，分两阶段，均需另行批准）
 * - **阶段 1（不改 schema，只正应用层）**：把"复用/跳过"的统一键换成 [ProductKey] ——
 *   `(platformId, nodeId, downloadMode, qualityKey, container)`。
 *   同一 node 的**不同模式/不同音质**从此被当作两份合法产物（DB 本来就允许，表里没有唯一索引），
 *   先下音频再下视频会得到两条记录，而不是把前一条盖掉；
 * - **阶段 2（要迁移，高风险）**：给 segment 加"音质身份"列（如 `quality_key`）→ 治理历史重复行
 *   （保留 `MAX(segment_id)`，与 `Migration45Sql` 对 `download_task` 的做法一致，**空表时必须是 no-op**）
 *   → 建唯一索引 [PROPOSED_UNIQUE_COLUMNS]。迁移必须用 `Migration45SqlTest` 那种
 *   **JVM 上真跑 SQLite** 的测试钉语义。
 */
object SegmentIdentityRules {

    /**
     * 「产物身份」：五项任一不同，就是**两份合法产物**（各占一条 `download_segment`）。
     *
     * @param downloadMode 下载模式（`DownloadMode` 的枚举名；这里用 String 保持 `core:data` 不依赖数据库模块）
     * @param qualityKey 音质/画质的**稳定**标识（不是给人看的描述文案；空白会被归一成 [UNKNOWN_QUALITY]）
     * @param container 封装格式（`mp4` / `flv` / `mp3`…）
     */
    data class ProductKey(
        val platformId: String,
        val nodeId: Long,
        val downloadMode: String,
        val qualityKey: String,
        val container: String,
    )

    /**
     * **现在代码里实际用的**键：只有"哪个节点 + 哪个平台"。
     *
     * 刻意保留它：① 让人一眼看出它比 [ProductKey] 少了哪三项；② 阶段 1 迁移时要靠它找历史行。
     */
    data class LegacyKey(
        val platformId: String,
        val nodeId: Long,
    )

    /** 音质未知时的占位（**不能**用空串：空串与"没填"分不开，会让"同一产物"判定漏掉） */
    const val UNKNOWN_QUALITY: String = "unknown"

    /**
     * 建议的唯一索引列（**本项没有真的建索引**，这里只是把方案写进代码，便于评审与将来迁移复用）。
     *
     * ⚠️ 其中 `quality_key` **目前 DB 里并不存在** —— 这就是"不能只加一个 `@Index`"的原因：
     * 阶段 2 必须先加列 + 回填 + 治理重复行，才谈得上建这个索引。
     */
    val PROPOSED_UNIQUE_COLUMNS: List<String> =
        listOf("platform_id", "node_id", "download_mode", "quality_key")

    /** 现在实际使用的键 */
    fun legacyKeyOf(platformId: String, nodeId: Long): LegacyKey =
        LegacyKey(platformId = platformId.trim(), nodeId = nodeId)

    /**
     * 构造 [ProductKey]（顺手归一化：trim、模式转大写、封装转小写、空音质 → [UNKNOWN_QUALITY]）。
     *
     * 归一化必须发生在**构造**时：否则 `"1080P"` 与 `"1080p "` 会被当成两种音质，
     * 于是又冒出一条重复记录 —— 这类"字符串不归一 → 判定失效"的坑本项目踩过多次
     * （如 `RELATIVE_PATH` 的结尾斜杠、`FILENAME` 的 `(N)` 后缀）。
     */
    fun productKeyOf(
        platformId: String,
        nodeId: Long,
        downloadMode: String,
        qualityKey: String?,
        container: String,
    ): ProductKey = ProductKey(
        platformId = platformId.trim(),
        nodeId = nodeId,
        downloadMode = downloadMode.trim().uppercase(Locale.ROOT),
        qualityKey = normalizeQualityKey(qualityKey),
        container = container.trim().lowercase(Locale.ROOT),
    )

    /** 音质标识归一：空白 → [UNKNOWN_QUALITY]，其余 trim（保留大小写，因为音质 key 可能是 "1080P" 这种） */
    fun normalizeQualityKey(qualityKey: String?): String =
        qualityKey?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_QUALITY

    /**
     * 两个身份是不是**同一份产物**：是 → 该复用同一条记录；不是 → 允许并存两条。
     *
     * 两边都会先按 [productKeyOf] 的规则归一，所以调用方不必自己 trim。
     */
    fun sameProduct(a: ProductKey, b: ProductKey): Boolean = normalize(a) == normalize(b)

    /**
     * 如果真加了唯一索引，`(键冲突的一组 segment_id)` 里该保留哪一条。
     *
     * 与 `Migration45Sql` 对 `download_task` 的做法一致：**保留 `MAX(segment_id)`**（最新那条），
     * 其余行连带子表数据一起清掉。空集合返回 null（调用方必须当 no-op —— 这正是 v3.3.2
     * 那个"空表把整表删光"事故的同源防护）。
     */
    fun keepSegmentIdOnConflict(existingSegmentIds: List<Long>): Long? =
        existingSegmentIds.maxOrNull()

    private fun normalize(key: ProductKey): ProductKey = ProductKey(
        platformId = key.platformId.trim(),
        nodeId = key.nodeId,
        downloadMode = key.downloadMode.trim().uppercase(Locale.ROOT),
        qualityKey = normalizeQualityKey(key.qualityKey),
        container = key.container.trim().lowercase(Locale.ROOT),
    )
}
