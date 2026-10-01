package com.imcys.bilibilias.data.download.naming

/**
 * 文件名的**字节长度**处理（纯函数、可单测）。
 *
 * ## 为什么需要（2026-09-15 复审 L8）
 * 文件名直接来自视频标题 / 命名规则模板，而 MediaStore 与绝大多数文件系统限制
 * **单个名字不超过 255 字节** —— UTF-8 下一个汉字 3 字节，标题稍长（85 个汉字以上）就会超。
 * 超了以后：MediaStore 的 `insert` 直接抛异常、legacy 分支 `FileOutputStream` 也会失败，
 * 表现就是"这一集明明能下、却因为标题太长整集失败"。
 *
 * 这里按 UTF-8 字节数截断**主体**、保留扩展名，并且按 **code point** 切
 * （绝不切出半个多字节字符，emoji 这种代理对也不会被拦腰截断）。
 */
object FileNameLengthRules {

    /** 给 `.part` 暂存名和 MediaStore 的 ` (1)` 后缀留余量；255 才是硬上限 */
    const val MAX_NAME_BYTES: Int = 200

    fun truncateToUtf8Bytes(name: String, maxBytes: Int = MAX_NAME_BYTES): String {
        if (name.toByteArray(Charsets.UTF_8).size <= maxBytes) return name

        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0) name.substring(dot) else ""
        val stem = if (dot > 0) name.substring(0, dot) else name
        // 扩展名优先保留（否则用户看到的名字没有类型）；极端情况下至少留 16 字节给主体
        val budget = (maxBytes - extension.toByteArray(Charsets.UTF_8).size).coerceAtLeast(16)

        val builder = StringBuilder()
        var used = 0
        var index = 0
        while (index < stem.length) {
            val codePoint = stem.codePointAt(index)
            val piece = String(Character.toChars(codePoint))
            val size = piece.toByteArray(Charsets.UTF_8).size
            if (used + size > budget) break
            builder.append(piece)
            used += size
            index += Character.charCount(codePoint)
        }
        return builder.toString().trimEnd() + extension
    }
}
