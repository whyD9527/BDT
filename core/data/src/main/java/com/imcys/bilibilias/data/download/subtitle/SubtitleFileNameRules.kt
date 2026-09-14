package com.imcys.bilibilias.data.download.subtitle

/**
 * 「字幕文件名」的纯规则（可单测）。
 *
 * ## 原来的写法（第十八轮审查的低危项）
 * `SubtitleDownloader.downloadSubtitlesToFile` 拼的是：
 * ```kotlin
 * val fileName = "${title}_${cc.lan}_${ccFileType.lowercase()}"
 * ```
 * 而 `FileOutputManager.createSubtitleOutputStream` 拿到的是 **`fileName` 本身** ——
 * 中间并没有哪一层去补扩展名（对比：弹幕那条路传的是 `buildFileName(..., "xml")`，
 * 媒体那条路传的是 `buildFileName(..., extension)`，两者都自带后缀）。
 *
 * 后果是下载目录里出现一个**没有后缀**的文件，比如
 * `某番剧_第1话_zh-CN`（而不是 `…_zh-CN.srt`）：
 * - 用户看不出它是什么格式，播放器/编辑器也不认；
 * - MediaStore 那一行虽然写了 MIME（`application/x-subrip`），但**显示名没有后缀**，
 *   别的 App 按名字判断类型时会判错；
 * - 更隐蔽的是"重新下载同一集"：`deleteExistingSameName` 按完整名删旧文件，
 *   名字里少了后缀，删的仍是同一条；但如果哪天后缀规则变了，就会出现两个同名不同格式的字幕。
 *
 * 这个规则就是把"文件名该带哪个后缀"这件事钉成纯函数：**后缀只从文件类型推**，
 * 不再依赖调用方记得拼上去。
 */
object SubtitleFileNameRules {

    /**
     * 字幕文件的后缀（不含点），从文件类型推出来。
     *
     * 取值范围就是 `CCFileType` 的两个成员的小写形式；未知类型一律回落 `srt`
     * —— 宁可给一个通用后缀，也不要产出没有后缀的文件。
     */
    fun extensionFor(ccFileTypeName: String?): String = when (ccFileTypeName?.lowercase()) {
        "ass" -> "ass"
        else -> "srt"
    }

    /**
     * 拼出下载目录里用的字幕文件名。
     *
     * @param title 视频/剧集标题（调用方传的是 `segment.title`，不是命名规则算出来的最终名）
     * @param language 字幕语言码（B 站的 `lan`，如 `zh-CN` / `ai-zh`）
     * @param ccFileTypeName `CCFileType` 的名字（`ASS` / `SRT`）
     */
    fun build(title: String, language: String, ccFileTypeName: String?): String {
        val ext = extensionFor(ccFileTypeName)
        return "${title}_${language}.$ext"
    }
}
