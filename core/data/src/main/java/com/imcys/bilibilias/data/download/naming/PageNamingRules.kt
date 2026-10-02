package com.imcys.bilibilias.data.download.naming

/**
 * 「分 P 标题」要不要填进命名占位符的规则。
 *
 * ## 为什么需要它（2026-10-02 真机实测）
 * 默认命名模板是 `{title}_{p_title}`（方案二）。但**单 P 视频**的 `page.part`
 * 并不是空的、也不是"P1"这类序号，而基本等于视频标题本身（实测：
 * `title = "【官方 MV】Never Gonna Give You Up - Rick Astley"`、
 * `p_title = "Never Gonna Give You Up - Rick Astley"`）。
 * 于是落盘名变成 **`标题_标题.mp4`** —— 用户看到的是"标题写了两遍"。
 *
 * 判据用**结构**（这一批要下的分 P 数量）而不是比较字符串：
 * 字符串比较抓不住上面这种"标题带前缀、分 P 标题不带"的情况
 * （等值判断在这里是失效的）。
 *
 * - 单 P：分 P 标题不提供任何信息 → 返回 null，模板自动退化成 `{title}`；
 * - 多 P：分 P 标题是**区分不同 P 的关键**（否则多 P 之间会互相覆盖，
 *   而在这台 ROM 上覆盖会退化成 `xxx (1).mp4` 副本）→ 照常填。
 */
object PageNamingRules {

    /**
     * @param pageCount 本次要下载的分 P（page）总数
     * @param partTitle 该分 P 的标题
     * @return 要填进 `{p_title}` 的值；单 P 或标题为空时返回 null
     */
    fun partTitleForNaming(pageCount: Int, partTitle: String?): String? =
        if (pageCount > 1) partTitle?.takeIf { it.isNotBlank() } else null
}
