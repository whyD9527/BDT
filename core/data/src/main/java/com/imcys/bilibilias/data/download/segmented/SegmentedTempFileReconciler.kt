package com.imcys.bilibilias.data.download.segmented

import java.io.File
import java.io.RandomAccessFile

/**
 * 把分片下载留下的临时文件，整理成**单连接续传可以安全接手**的状态。
 *
 * ## 为什么必须做这一步（不做就会写坏文件）
 * 单连接续传判断"已经下好多少"的方式是 `tempFile.length()`（见 `DownloadExecutor.performDownload`）。
 * 而分片下载：
 * - 各片**完成顺序不定**，文件长度可能已经是全长，中间却还有洞；
 * - 每片是 `seek` 到绝对偏移写的，文件长度**不等于**有效内容的长度。
 *
 * 所以一旦分片失败要回落单连接，**必须先按边车元数据把文件截断到"从 0 起真正连续的字节数"**，
 * 否则单连接会把那些洞当成已下载内容，只补后半段 —— 产出长度正确、内容错位的坏文件。
 * 这类问题在界面上完全看不出来（进度照走、大小照对），只有播到一半才发现。
 *
 * ## 保守到什么程度
 * 只对**与本次计划完全一致**的元数据（[SegmentDownloadMeta.matches]）作数，且再按
 * 文件实际长度夹一次 —— 元数据说下了 100MB 但文件只有 3MB 时，只信 3MB。
 * 元数据缺失/损坏/过期 → 一律当作"什么都没下"（截断到 0，从头下）。
 *
 * ⚠️ 与现有单连接实现同等强度：**只比长度，不校验内容**（内容校验要全量读一遍，代价太大）。
 */
object SegmentedTempFileReconciler {

    /**
     * @return 整理后单连接应当认为"已下载"的字节数（即临时文件的新长度）
     */
    fun reconcile(
        tempFile: File,
        metaFile: File,
        plan: List<Segment>,
    ): Long {
        val meta = readMeta(metaFile)
        val trusted = meta?.takeIf { it.matches(plan) }
        val fileLength = if (tempFile.exists()) tempFile.length() else 0L
        val prefix = (trusted?.contiguousPrefixLength() ?: 0L).coerceAtMost(fileLength)

        if (tempFile.exists() && fileLength != prefix) {
            runCatching {
                RandomAccessFile(tempFile, "rw").use { it.setLength(prefix) }
            }
        }
        // 边车已经交给单连接了，留着只会让下次的分片尝试信任一份过期的进度
        metaFile.delete()
        File("${metaFile.path}.tmp").delete()
        return prefix
    }

    private fun readMeta(metaFile: File): SegmentDownloadMeta? {
        if (!metaFile.exists()) return null
        return runCatching { SegmentDownloadMetaCodec.decode(metaFile.readText()) }.getOrNull()
    }
}
