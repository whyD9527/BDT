package com.imcys.bilibilias.data.download.cache

/**
 * 「内嵌封面 / 内嵌字幕的临时文件什么时候可以删」的纯规则（可单测）。
 *
 * ## 问题（第十八轮审查的低危项）
 * 合并阶段要先把封面与字幕落成临时文件，再把路径交给 ffmpeg：
 * - `NewDownloadManager.handlePredecessor`：`externalCacheDir/cover/embed_cover_<segmentId>.jpg`
 * - `SubtitleDownloader.downloadSubtitlesForEmbed`：`externalCacheDir/cc/embed_cc_<segmentId>_<lan>.srt`
 *
 * 这两个目录**只在"存储管理 → 清空缓存"时**才会被整体删掉。于是正常情况下：
 * 下载完成、合成产物已经进了下载目录，这两份临时件却一直留在应用缓存里；
 * 下次再下同一集又写一份新的（`segmentId` 不同 → 又是新文件）。攒着不清理，
 * 用户也不会知道"清空缓存"能清掉它们。
 *
 * ## 规则
 * 文件名一律以 `embed_` 开头（这是两条写入路径**唯一**的公共约定），
 * 且必须落在约定的目录里 —— 两条都满足才认领。
 *
 * 为什么认领要靠"目录 + 文件名前缀"两个条件、而不是只看前缀：
 * `externalCacheDir` 下还有别的模块在用（如 `frameTemp/`），只按前缀删将来容易误伤；
 * 反过来只看目录也不行，缓存目录里还可能有其他东西。
 */
object EmbedCacheRules {

    /** 内嵌封面缓存的目录名（相对 `externalCacheDir`） */
    const val COVER_DIR = "cover"

    /** 内嵌字幕缓存的目录名（相对 `externalCacheDir`） */
    const val CC_DIR = "cc"

    /** 两条写入路径共同的文件名前缀 */
    const val EMBED_PREFIX = "embed_"

    /** 这两个目录里所有内嵌产物都是临时件，可以整目录清 */
    val dirNames: Set<String> = setOf(COVER_DIR, CC_DIR)

    /**
     * 这是不是一条内嵌封面/字幕的临时件。
     *
     * @param parentDirName 文件所在目录的名字（如 `cover` / `cc`）
     * @param fileName 文件名
     */
    fun isEmbedCacheFile(parentDirName: String?, fileName: String?): Boolean {
        if (parentDirName == null || fileName == null) return false
        if (parentDirName !in dirNames) return false
        return fileName.startsWith(EMBED_PREFIX) && fileName.length > EMBED_PREFIX.length
    }

    /**
     * 这个任务产出的内嵌临时件路径（已经记在 runtime info 里的那些）。
     *
     * 只认领**本任务自己写出来的**那些路径，不做任何模式匹配 —— 这样即使
     * 目录里同时躺着别的任务的临时件，也不会被顺手删掉。
     *
     * @param coverPath `TaskRuntimeInfo.coverPath`（可能为空串 = 没下过内嵌封面）
     * @param subtitlePaths `TaskRuntimeInfo.subtitles` 里的每条 `path`
     * @param parentDirNameOf 用来把路径映射回"所在目录名"的函数
     */
    fun taskOwnedPaths(
        coverPath: String?,
        subtitlePaths: List<String>,
        parentDirNameOf: (String) -> String?,
    ): List<String> = (listOfNotNull(coverPath?.takeIf { it.isNotBlank() }) + subtitlePaths)
        .distinct()
        .filter { path ->
            isEmbedCacheFile(parentDirNameOf(path), path.substringAfterLast('/', missingDelimiterValue = ""))
        }
}
