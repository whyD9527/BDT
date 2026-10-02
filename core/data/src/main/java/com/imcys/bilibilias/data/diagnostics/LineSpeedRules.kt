package com.imcys.bilibilias.data.diagnostics

/**
 * 线路测速结果的**比较规则**（纯函数、可单测）。
 *
 * 线路配置页里每行的「检测」会把结果显示成字符串（如 `3.2 MB/s`、`800 KB/s`），
 * 但**没有结论**：用户不知道该选哪条（2026-10-02 大加强 L2）。
 * 这里把速度串归一化成"每秒字节数"再挑最大，供 UI 标「最快」与「选最快的」用。
 */
object LineSpeedRules {

    /** 把 `3.2 MB/s` / `800 KB/s` / `1,5 MB/s` / `2 MBps` 之类解析成字节/秒；认不出返回 null */
    fun parseBytesPerSecond(raw: String?): Double? {
        val s = raw?.trim()?.replace(",", ".") ?: return null
        if (s.isEmpty()) return null
        val m = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*([kKmMgG])?\\s*[bB]").find(s) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].lowercase()
        val factor = when (unit) {
            "k" -> 1024.0
            "m" -> 1024.0 * 1024
            "g" -> 1024.0 * 1024 * 1024
            else -> 1.0
        }
        return value * factor
    }

    /**
     * 从 (host, 速度串) 列表里挑**最快**的 host；都没测/都认不出则返回 null。
     * 只比较"能解析出速度"的那些（未检测的 null 不参与，避免误标）。
     */
    fun fastestHost(entries: List<Pair<String, String?>>): String? =
        entries.mapNotNull { (host, speed) ->
            val bps = parseBytesPerSecond(speed) ?: return@mapNotNull null
            host to bps
        }.maxByOrNull { it.second }?.first
}
