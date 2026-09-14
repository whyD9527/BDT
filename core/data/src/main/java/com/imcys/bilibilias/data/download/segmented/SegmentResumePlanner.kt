package com.imcys.bilibilias.data.download.segmented

/**
 * 某一片**本次要下的部分**（跳过已经落盘的那一段）。
 *
 * 这是断点续传的核心决策：一片可能上次下到一半就断了，本次必须
 * **从 `writeOffset` 接着写**，而不是从头写（浪费流量）或整片跳过（文件缺字节）。
 */
data class PartResume(
    val segment: Segment,
    /** 本片已落盘的字节数（从 `segment.start` 起连续） */
    val alreadyDownloaded: Long,
) {
    /** 本片剩余要下的字节数 */
    val remaining: Long get() = (segment.length - alreadyDownloaded).coerceAtLeast(0L)

    /** 本片是否已经下完（本次不用再下） */
    val isComplete: Boolean get() = alreadyDownloaded >= segment.length

    /** 本次写入的**绝对文件偏移**（不是片内偏移） */
    val writeOffset: Long get() = segment.start + alreadyDownloaded

    /** 本次要发的 Range 头（闭区间）；已下完返回 null（调用方应跳过该片） */
    val rangeHeader: String?
        get() = if (isComplete) null else "bytes=$writeOffset-${segment.endInclusive}"
}

/**
 * 把「分片计划」＋「边车元数据」翻译成「本次每一片该怎么下」。
 *
 * 信任规则只有一条：**元数据必须与本次计划完全一致**（[SegmentDownloadMeta.matches]）。
 * 只要有一点不符（文件变了、并发设置变了、元数据是坏的/过期的），就当作从来没下过，
 * 全片从 0 重下 —— 宁可多下一遍，也不要把旧进度套到新的边界上（那会写错位）。
 */
object SegmentResumePlanner {

    fun resolve(segments: List<Segment>, meta: SegmentDownloadMeta?): List<PartResume> {
        val trusted = meta?.takeIf { it.matches(segments) }
        return segments.map { segment ->
            val downloaded = trusted?.parts
                ?.firstOrNull { it.index == segment.index }
                ?.downloaded
                ?: 0L
            PartResume(
                segment = segment,
                // matches() 已经保证过 downloaded 在 0..length，这里是第二道防线：
                // 越界的值一旦漏进去，就会算出一个文件之外的写偏移。
                alreadyDownloaded = downloaded.coerceIn(0L, segment.length),
            )
        }
    }
}
