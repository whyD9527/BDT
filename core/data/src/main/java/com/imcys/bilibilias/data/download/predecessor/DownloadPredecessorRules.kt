package com.imcys.bilibilias.data.download.predecessor

/**
 * 「下载前置任务」的纯规则。
 *
 * 前置任务指的是正式下载媒体之前那些**可选的**附加活儿：内嵌封面、封面存相册、
 * 弹幕、字幕文件。它们每一个都是用户勾选的可选项，**任何一个出问题都不该让整集下载失败**。
 *
 * 抽出来的直接原因（第十八轮审查）：封面为 null 时会去请求**空 URL**
 * （`task.cover?.toHttps() ?: ""`），Ktor/OkHttp 解析空 URL 直接抛异常，
 * 异常顺着 `handlePredecessor` 冒到任务层 → `failTask` → 用户明明能下这一集，
 * 却因为"这集没有封面"而整集失败。
 */
object DownloadPredecessorRules {

    /**
     * 能不能去下这张封面。
     *
     * 地址为 null/空白 ⇒ **不发请求**（发出去就是抛异常）。
     */
    fun canFetchCover(coverUrl: String?): Boolean = !coverUrl.isNullOrBlank()

    /**
     * 封面文件名用的扩展名。
     *
     * 取不到就回落 `jpg` —— 原先直接 `cover?.substringAfterLast(".")`，
     * 没有封面时会拼出 `123_pic.null` 这种文件名，取到的"扩展名"也可能带着
     * 一长串查询参数（`jpg@480w_270h.webp` 这类）。
     */
    fun coverExtension(coverUrl: String?): String {
        val candidate = coverUrl?.substringAfterLast('.', "")?.trim().orEmpty()
        return if (candidate.isNotEmpty() && candidate.length <= 5 && candidate.all { it.isLetterOrDigit() }) {
            candidate
        } else {
            "jpg"
        }
    }
}
