package com.imcys.bilibilias.common.utils

/**
 * AS解析工具类：从「用户复制的文本」里认出 B 站的内容标识（BV / av / ep / ss / 短链 / 用户空间）。
 *
 * ## 两条硬规则（都是踩过坑之后加的，改动前请先看单测）
 *
 * ### 1. 数字一律用 `toLongOrNull()`，绝不用 `toLong()`
 * 剪贴板是**任何 App 都能写入**的不可信输入。`av`/`ep`/`ss` 后面跟一长串数字
 * （长订单号、时间戳、哈希……）时 `toLong()` 会抛 `NumberFormatException`；
 * 而调用它的两处（`ClipboardAutoHandler` 的 LaunchedEffect、`AnalysisViewModel` 的
 * `debounceJob.collect`）**都没有 try/catch**，异常会一路走到 `AppCrashHandler` →
 * 起崩溃页 + `killProcess`。**这是外部可触发的崩溃**，已在真机上复现
 * （`am_crash: java.lang.NumberFormatException: For input string: "1234567890123456789012345"`）。
 *
 * ### 2. 只有"像 B 站链接"或"整串就是一个 ID"才认，避免误判
 * 原来 `ss([0-9]+)`、`(?<=(av|…))([0-9]+)` 没有任何边界、也不看域名，于是：
 * `https://example.com/nav123456`（"nav" 里的 av）→ 被当成 `av123456`；
 * `会议记录 ss123 已上传` → 被当成 `ss123`；`css2024.jpg` → 被当成 `ss2024`。
 * 后果不只是"认错"：`ClipboardAutoHandler` 认定它是链接后会**清空用户剪贴板**并跳到解析页，
 * 再按那个 id 去请求 B 站接口 —— 用户会看到一个**完全不相关的视频**，还可能把它下载下来。
 *
 * 所以现在的判定是：
 * - 文本里出现 B 站域名（`bilibili.com` / `b23.tv` / `bili2233.cn`）→ 允许从混杂文本里抽 ID；
 * - 否则要求**整串（去掉首尾空白）本身就是那个 ID**（用户直接粘贴 `BV…` / `av123` / `ep123`）。
 * - BV 号另有一条自己的左边界（`(?<![A-Za-z0-9])`）：它的形状（`BV` + 恰好 10 位）本身够特殊，
 *   不必强制带域名 —— 这样"文案里夹一个 BV 号、但没带链接"仍然能识别。
 *
 * > 取舍记录：`视频 av170001 很好看` 这种"句子里夹 av 号、又没有链接"的输入现在会**不被识别**。
 * > 这是有意的：相比"漏认"（用户再贴一次链接即可），"误认成另一个视频并下载下来"更糟。
 */
object AsRegexUtil {

    /** B 站域名：出现这些才算"这是一条 B 站链接"，才允许从混杂文本里抽 ID */
    private val BILI_HOSTS = listOf("bilibili.com", "b23.tv", "bili2233.cn")

    // BV：左边界防 "ABV1234567890" 这类误命中；后 10 位与 B 站实际字符集一致（放宽到字母数字）
    private val regexBV = Regex("""(?<![A-Za-z0-9])[Bb][Vv]([A-Za-z0-9]{10})""")

    // av：左边界防 "nav123"；大小写都认
    private val regexAV = Regex("""(?<![A-Za-z0-9])[Aa][Vv]([0-9]+)""")

    // ep / ss：保持原来"行首或斜杠之后"的要求（B 站番剧 URL 就是 /play/ep… 、/play/ss…）
    private val regexEP = Regex("""(?:^|/)[Ee][Pp]([0-9]+)""")
    private val regexSS = Regex("""(?<![A-Za-z0-9])[Ss][Ss]([0-9]+)""")

    // 短链：`[A-z]` 是经典笔误（ASCII 65..122 会连带匹配 [ \ ] ^ _ `），已改成 [A-Za-z]；并补上 http://
    private val regexShortLink = Regex("""https?://b23\.tv/([A-Za-z0-9]{6,})""")
    private val regex2233ShortLink = Regex("""https?://bili2233\.cn/([A-Za-z0-9]{6,})""")
    private val regexUserSpace = Regex("""space\.bilibili\.com/?([0-9]+)""")

    private fun containsBiliHost(text: String): Boolean =
        BILI_HOSTS.any { text.contains(it, ignoreCase = true) }

    /**
     * 从混杂文本里抽取：不带 B 站域名时，只接受"整串就是这个 ID"。
     *
     * @param groupIndex 取哪一组作为 ID
     */
    private fun extract(
        text: String,
        hasBiliHost: Boolean,
        matchRegex: Regex,
        groupIndex: Int = 1,
    ): String? {
        val matched = matchRegex.find(text) ?: return null
        val coversWholeText = matched.range.first == 0 && matched.range.last == text.lastIndex
        if (!hasBiliHost && !coversWholeText) return null
        return matched.groupValues.getOrNull(groupIndex)?.takeIf { it.isNotEmpty() }
    }

    /** 数字 ID 一律安全解析：溢出/异常一律返回 null（**绝不能抛异常**，见类注释第 1 条） */
    private fun String.toIdOrNull(): Long? = toLongOrNull()

    fun parse(text: String): TextType? {
        val t = text.trim()
        if (t.isEmpty()) return null

        val hasBiliHost = containsBiliHost(t)

        // BV：形状本身够特殊，不强制要求域名（这样"文案里夹 BV 号"仍能认出来）
        regexBV.find(t)?.let { match ->
            return TextType.BILI.BV("BV" + match.groupValues[1])
        }

        extract(t, hasBiliHost, regexAV)?.let { digits ->
            return digits.toIdOrNull()?.let { TextType.BILI.AV(it) }
        }

        extract(t, hasBiliHost, regexEP)?.let { digits ->
            return digits.toIdOrNull()?.let { TextType.BILI.EP(it) }
        }

        extract(t, hasBiliHost, regexSS)?.let { digits ->
            return digits.toIdOrNull()?.let { TextType.BILI.SS(it) }
        }

        extract(t, hasBiliHost, regexShortLink)?.let {
            return TextType.BILI.ShortLink("https://b23.tv/$it")
        }

        extract(t, hasBiliHost, regex2233ShortLink)?.let {
            return TextType.BILI.ShortLink("https://bili2233.cn/$it")
        }

        // 用户空间链接本身就带域名；uid 也要求能放进 Long
        // （与其它 ID 类型保持一致：垃圾输入要在**这里**就被拒掉，
        //  否则会先清空用户剪贴板、再发一次注定失败的请求）
        if (hasBiliHost) {
            extract(t, hasBiliHost, regexUserSpace)?.let { uid ->
                return uid.toIdOrNull()?.let { TextType.BILI.UserSpace(uid) }
            }
        }

        return null
    }
}

sealed interface TextType {
    sealed interface BILI : TextType {
        data class BV(val text: String) : BILI
        data class AV(val text: Long) : BILI
        data class EP(val text: Long) : BILI
        data class SS(val text: Long) : BILI

        data class ShortLink(val text: String) : BILI
        data class UserSpace(val text: String) : BILI
    }
}
