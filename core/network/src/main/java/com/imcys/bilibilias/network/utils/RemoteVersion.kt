package com.imcys.bilibilias.network.utils

/**
 * 远端版本号与本地版本号的比较。
 *
 * 为什么单独抽成一个纯函数：它是"要不要提示更新"的**唯一判断依据**。
 * 判断错了后果很直接 —— 要么拿老版本骚扰用户，要么漏掉真更新
 * （3.1.7 那次回归就是靠人肉发现的，说明这类提示确实有用）。
 *
 * 容错原则：远端数据不可控，**宁可"不提示"也不要"乱提示"**，
 * 解析不出来一律返回 false。因此它必须可测 → 见 `RemoteVersionTest`。
 */
object RemoteVersion {

    /**
     * [remote] 是否比 [current] 新。
     *
     * 规则：
     * - 两端都忽略前缀 `v`/`V`，按 `.` 分段比数字，段数不同时短的补 0（`1.2` == `1.2.0`）；
     * - 只比较前三段，多余部分（如 `3.1.8-beta` 的 `-beta`）忽略；
     * - 任一段取不出数字（如 `latest`、`nightly`）→ 返回 false，不提示。
     */
    fun isNewer(remote: String, current: String): Boolean {
        val r = parse(remote) ?: return false
        val c = parse(current) ?: return false

        for (i in 0 until maxOf(r.size, c.size)) {
            val remotePart = r.getOrElse(i) { 0 }
            val currentPart = c.getOrElse(i) { 0 }
            if (remotePart != currentPart) return remotePart > currentPart
        }
        return false
    }

    private fun parse(version: String): List<Int>? {
        val cleaned = version.trim().removePrefix("v").removePrefix("V")
        if (cleaned.isEmpty()) return null

        val numbers = cleaned
            .split('.', '-', '+', '_')
            .take(3)
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: return null }

        return numbers.ifEmpty { null }
    }
}
