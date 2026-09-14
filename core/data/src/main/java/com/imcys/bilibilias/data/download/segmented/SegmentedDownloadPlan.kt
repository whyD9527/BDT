package com.imcys.bilibilias.data.download.segmented

/**
 * 多线程分片下载的**纯逻辑**（不碰网络、不碰文件、不碰 DB）。
 *
 * 为什么单独抽出来：分片最容易错的地方是**边界**（尾片、长度未知、并发数大于字节数、
 * 片区间是否严丝合缝覆盖整个文件），而这些都能用纯函数表达并**单测覆盖**。
 * 2026-09 的 wbi 回归说明"看着对"不等于对 —— 先把能测的部分测掉。
 *
 * 约定：本方所有区间都是**闭区间** `[start, endInclusive]`，与 HTTP `Range` 语义一致。
 * （B 站的 CDN 只认闭区间写法，写错就是 416/403。）
 */
data class Segment(
    val index: Int,
    val start: Long,
    val endInclusive: Long,
) {
    /** 本片字节数；闭区间所以 +1 */
    val length: Long get() = endInclusive - start + 1

    /** 本片对应的 Range 请求头 */
    val rangeHeader: String get() = "bytes=$start-$endInclusive"
}

object SegmentedDownloadPlan {

    /** 小于这个大小就不分片：并发开销不划算 */
    const val MIN_SIZE_FOR_SEGMENTED: Long = 8L * 1024 * 1024

    /** 默认并发数（4 片） */
    const val DEFAULT_CONCURRENCY: Int = 4

    /** 并发数下限：1 片等于不分片，所以下限是 2 */
    const val MIN_CONCURRENCY: Int = 2

    /** 并发数上限：再高收益有限，却更容易触发 CDN 风控 */
    const val MAX_CONCURRENCY: Int = 8

    /** 用户没设置过时的默认值：分片下载**默认开**（与引入设置项之前的行为一致） */
    const val DEFAULT_ENABLED: Boolean = true

    /**
     * 把设置项里的开关解析成实际行为。
     *
     * `null`（用户从未设置过）→ **默认开**。这一点很重要：proto 里如果用一个普通 bool
     * （默认 false）来表示开关，一升级就会把已经实测可用的功能默默关掉 —— 那是静默的行为回归，
     * 用户不会知道为什么下载突然变慢。所以 proto 用的是 `optional`，这里把"未设置"映射成默认开。
     */
    fun resolveEnabled(configured: Boolean?): Boolean = configured ?: DEFAULT_ENABLED

    /**
     * 把设置项里的并发数夹到安全范围。
     *
     * `null` → 默认 [DEFAULT_CONCURRENCY]；越界值一律夹回来。
     * 设置项是个用户（以及未来的自己）可以随便塞数字的地方，而
     * **0 / 1 会让 [shouldSegment] 恒为 false** —— 表现是"分片下载开关明明是开的，却一直不分片"，
     * 极难排查；过大则可能触发 CDN 风控。所以宁可夹，也不把非法值原样带进下载逻辑。
     */
    fun resolveConcurrency(configured: Int?): Int =
        configured?.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY) ?: DEFAULT_CONCURRENCY

    /**
     * 是否应该走分片。
     *
     * @param totalLength 远端文件长度；未知（-1）时不能分片
     * @param acceptRanges 服务器是否声明支持 Range：
     *        - `true` / `null`（没这个头）→ 允许尝试。**刻意不要求必须有这个头**：
     *          现有断点续传本来就在用 `Range` 且能工作，很多 CDN 并不在 HEAD 里声明它；
     *        - `false` → 明确不支持，不分片。
     */
    fun shouldSegment(
        totalLength: Long,
        acceptRanges: Boolean?,
        enabled: Boolean,
        concurrency: Int = DEFAULT_CONCURRENCY,
    ): Boolean = enabled &&
        concurrency > 1 &&
        totalLength >= MIN_SIZE_FOR_SEGMENTED &&
        acceptRanges != false

    /**
     * 把 `[0, totalLength)` 均分成至多 [concurrency] 片。
     *
     * 保证：
     * - 片区间**严丝合缝**覆盖整个文件，不重不漏；
     * - 每片至少 1 字节（片数不会超过总字节数）；
     * - 各片长度尽量均衡（最大最小差 ≤ 1），余数分给前面的片。
     *
     * @return 长度为 0 或负数时返回空列表（调用方据此回落单连接）
     */
    fun plan(totalLength: Long, concurrency: Int = DEFAULT_CONCURRENCY): List<Segment> {
        if (totalLength <= 0 || concurrency <= 0) return emptyList()

        val count = minOf(concurrency.toLong(), totalLength).toInt()
        val base = totalLength / count
        val remainder = totalLength % count

        val segments = ArrayList<Segment>(count)
        var start = 0L
        for (i in 0 until count) {
            val length = base + if (i < remainder) 1 else 0
            segments += Segment(index = i, start = start, endInclusive = start + length - 1)
            start += length
        }
        return segments
    }

    /**
     * 由各片已下载字节数聚合出总进度（0f..1f）。
     *
     * 只做"求和再除以总长度"，并夹在 0..1 之间；**单调性由调用方保证**
     * （现有实现本来就有 1% 节流 + lastProgress，接线时沿用同一套）。
     */
    fun aggregateProgress(downloadedByIndex: Map<Int, Long>, totalLength: Long): Float =
        progressOf(
            downloadedTotal = downloadedByIndex.values.sumOf { it.coerceAtLeast(0L) },
            totalLength = totalLength,
        )

    /**
     * 已知"已下载总量"时的进度计算（并发分片里各片用原子计数累加，
     * 不必为每次算进度都建一个 Map）。
     */
    fun progressOf(downloadedTotal: Long, totalLength: Long): Float {
        if (totalLength <= 0) return 0f
        return (downloadedTotal.toDouble() / totalLength.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }
}
