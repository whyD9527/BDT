package com.imcys.bilibilias.data.diagnostics

/**
 * 线路的**展示名**规则（纯函数、可单测）。
 *
 * 为什么需要（2026-10-02 真机复验）：反馈页与诊断日志里，线路显示的是设置里存的**裸 host**
 * （例如 `upos-sz-mirrorali.bilivideo.com`）—— 用户根本认不出那是"阿里线路" ✗。
 * 但线路配置页里的名字是品牌名（`ali（阿里）`、`alib（阿里）`…），**不该翻译**，
 * 所以这里只做"host → 展示名"的**映射**，不产出任何中文文案以外的国际化内容。
 */
object LineDisplayRules {

    /** 已知线路关键字 → 展示名（顺序有意义：先长后短，避免 `ali` 抢了 `alib`/`alio1`） */
    private val KNOWN: List<Pair<String, String>> = listOf(
        "alio1" to "alio1（阿里）",
        "alib" to "alib（阿里）",
        "mirrorali" to "ali（阿里）",
        "ali" to "ali（阿里）",
        "akamai" to "akamai",
        "mirrorcos" to "腾讯云（cos）",
        "mirrorbos" to "百度云（bos）",
        "mirrorhw" to "华为云（hw）",
        "mirrorks3" to "金山云（ks3）",
        "mirror12589" to "阿里云（12589）",
        "upos" to "B站默认（upos）",
    )

    /** 空 host = 用户选了「默认线路」 */
    const val DEFAULT_LINE_NAME = "默认线路"

    /**
     * host → 可读展示名：
     * - 空白 → 「默认线路」
     * - 命中已知关键字 → 对应品牌名
     * - 都不命中 → **原样返回 host**（宁可不美化，也不编一个错名字）
     */
    fun displayName(host: String?): String {
        val h = host?.trim().orEmpty()
        if (h.isEmpty()) return DEFAULT_LINE_NAME
        KNOWN.firstOrNull { h.contains(it.first, ignoreCase = true) }?.let { return it.second }
        return h
    }
}
