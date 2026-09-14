package com.imcys.bilibilias.data.download.segmented

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 分片下载的**边车（sidecar）元数据**：记录每片已完成多少字节，供断点续传使用。
 *
 * 为什么用边车文件而不是 DB 列：给 Room 实体加字段要写迁移，**迁移写错会直接影响
 * 用户已有数据**；而这份元数据是纯缓存性质的东西，丢了最多重下一片，放文件最安全。
 * 文件名约定：`<目标文件>.downloadpart`（与现有 `.downloading` 临时文件同目录）。
 */
@Serializable
data class SegmentDownloadMeta(
    val version: Int = CURRENT_VERSION,
    val totalLength: Long,
    val parts: List<PartProgress>,
) {
    @Serializable
    data class PartProgress(
        val index: Int,
        val start: Long,
        val endInclusive: Long,
        /** 本片已落盘字节数（从 start 起连续写入的长度） */
        val downloaded: Long,
    ) {
        val length: Long get() = endInclusive - start + 1
        val isCompleted: Boolean get() = downloaded >= length
    }

    /** 已完成的字节总数（用于恢复进度显示） */
    val downloadedTotal: Long get() = parts.sumOf { it.downloaded.coerceAtLeast(0L) }

    /**
     * 从片 0 起**连续完成**的字节数。
     *
     * 为什么需要这个：回落单连接续传时，单连接是按**临时文件的长度**来判断"已经下好多少"
     * 的（见 `DownloadExecutor.performDownload`）。而分片下载各片完成顺序不定，文件长度
     * 可能已经等于全长、文件中间却还有洞 —— 直接回落就会把洞当成已下载内容，
     * **写出来一个长度对、内容坏的文件**。所以回落前必须把文件截到这个"真正连续的字节数"。
     *
     * ⚠️ 只对**通过 [matches] 校验**的元数据有意义（那时片号与边界都已被确认），
     * 而且只按片号顺序累加、**不校验内容** —— 与现有单连接实现的强度相同，不更差。
     */
    fun contiguousPrefixLength(): Long {
        var prefix = 0L
        for (part in parts.sortedBy { it.index }) {
            prefix += part.downloaded.coerceIn(0L, part.length)
            if (!part.isCompleted) break
        }
        return prefix
    }

    /**
     * 元数据是否与本次的**分片计划**一致。
     *
     * 只要片数/片边界/自身声明的总长任一不符，就说明计划变了（比如文件变了、并发设置变了），
     * 此时不能信任旧的片进度 —— 返回 false，由调用方整片重下。
     *
     * ⚠️ 这里要求**完全一致**，包括"元数据自己声明的总长"要与各片长度之和对得上。
     * 一份自相矛盾的元数据（边界看着对、总长却写着别的数）说明整体已不可信，
     * 不能只挑其中"看着还行"的字段用 —— 写入偏移正是靠这些字段算出来的。
     */
    fun matches(planned: List<Segment>): Boolean {
        if (version != CURRENT_VERSION) return false
        if (parts.size != planned.size) return false
        if (totalLength != planned.sumOf { it.length }) return false
        return planned.all { segment ->
            val part = parts.firstOrNull { it.index == segment.index } ?: return false
            part.start == segment.start && part.endInclusive == segment.endInclusive &&
                part.downloaded in 0..part.length
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * 边车元数据的读写（纯字符串转换，可单测）。
 *
 * 解析失败一律返回 null —— 调用方把它当作"没有元数据"，从头下即可，
 * **绝不能让一份坏掉的缓存文件把整个下载搞失败**。
 */
object SegmentDownloadMetaCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(meta: SegmentDownloadMeta): String = json.encodeToString(meta)

    fun decode(text: String): SegmentDownloadMeta? = runCatching {
        json.decodeFromString<SegmentDownloadMeta>(text)
    }.getOrNull()
}
