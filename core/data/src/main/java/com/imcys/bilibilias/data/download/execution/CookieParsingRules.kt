package com.imcys.bilibilias.data.download.execution

import java.net.URLDecoder

/**
 * 「把用户粘贴的 Cookie 字符串解成键值对」的纯规则（可单测）。
 *
 * ## 背景（2026-09-14 全量审计 H5）
 * `CookieLoginViewModel.checkCookies` 由输入框的 `onValueChange` **每次按键**同步调用，
 * 里面直接用
 * ```kotlin
 * URLDecoder.decode(value, "UTF-8")
 * ```
 * 而 `URLDecoder.decode` 遇到不完整/非法的百分号转义会抛 `IllegalArgumentException`
 * （`%`、`%z`、`%2` 都算）。整条链路（Compose 事件回调 → ViewModel）没有任何 try/catch，
 * 于是**用户每敲一个字符都可能让应用崩掉** —— 而 Cookie 文本里出现单个 `%` 并不罕见。
 *
 * 这里把解码做成"永不抛"：
 * 1. 能正常解 → 用解出来的值（并保留老行为：`+` 视作空格 —— `URLDecoder` 已经这么做了）；
 * 2. 解不出来 → **退回原串**（宁可这个值带 `%xx` 字面量，也不能崩；用户自己看得见）。
 */
object CookieParsingRules {

    /**
     * 解析形如 `a=1; b=2` 的 Cookie 串。
     *
     * - 没有 `=` 的片段直接跳过（老行为）；
     * - 名字去空白；值为空也算一条（B 站确实有 `key=`）；
     * - **解码失败退回原值**，绝不抛异常。
     */
    fun parse(raw: String): List<CookiePair> =
        raw.split(";")
            .mapNotNull { segment ->
                val trimmed = segment.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val parts = trimmed.split("=", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                CookiePair(
                    name = parts[0].trim(),
                    value = safeDecode(parts[1]),
                )
            }
            .filter { it.name.isNotEmpty() }

    /**
     * 永不抛的 URL 解码。
     *
     * ⚠️ 失败时返回**原串**而不是空串：空串会让"这个 Cookie 是空的"与"这段文本我们解不了"
     * 变成同一个样子，用户更难判断自己粘错了什么。
     */
    fun safeDecode(value: String): String = runCatching {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    }.getOrDefault(value)

    /** 一条 Cookie 的键值 */
    data class CookiePair(val name: String, val value: String)
}
