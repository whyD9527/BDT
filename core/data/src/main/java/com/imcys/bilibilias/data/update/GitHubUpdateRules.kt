package com.imcys.bilibilias.data.update

/**
 * 「从 GitHub Releases 检查更新」的纯规则（可单测）。
 *
 * 背景（2026-10-02）：应用原先**只有** Google Play 的应用内更新（`GooglePlayAppUpdateManage`），
 * 而 alpha 渠道里 `ENABLED_PLAY_APP_MODE = false`，等于**从不检查更新**；而且侧载安装的包
 * Play 也查不到。用户要求改成**按 GitHub Releases 检查**。
 *
 * 这里只放"不需要网络、不需要 Android"的判定：
 *  1. 解析 tag（`v3.3.4` / `3.3.4` / `3.3.4-alpha`）→ 可比较的版本；
 *  2. 新版本判断；
 *  3. 按 ABI 从 release 资产里挑 APK（挑不到就退 universal）；
 *  4. 隐私门槛（未同意隐私政策时不发请求，与剪贴板自动识别一致）；
 *  5. 跳过版本（用户点过"跳过此版本"就不要再提示）。
 */
object GitHubUpdateRules {

    /** 三段式版本。`v3.3.4` → (3,3,4) */
    data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
        override fun compareTo(other: SemVer): Int =
            compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

        /** 给"跳过此版本"持久化用的稳定数值编码 */
        fun encode(): Int = major * 10_000 + minor * 100 + patch

        override fun toString(): String = "$major.$minor.$patch"
    }

    /**
     * 解析版本字符串：容忍前缀 `v`/`V`、后缀（`-alpha`、`-beta.2`、`+build`）与空白。
     * 只能解析出"至少 major.minor"时才算成功（`3` 或 `abc` → null，宁可不提示）。
     */
    fun parseVersion(raw: String?): SemVer? {
        val s = raw?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
        val core = s.takeWhile { it.isDigit() || it == '.' }
        if (core.isEmpty()) return null
        val parts = core.split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val nums = parts.take(3).map { it.toIntOrNull() ?: return null }
        return SemVer(nums.getOrElse(0) { 0 }, nums.getOrElse(1) { 0 }, nums.getOrElse(2) { 0 })
    }

    /** 远端是否比本地新（相等不算新） */
    fun isNewer(remote: SemVer?, local: SemVer?): Boolean =
        remote != null && local != null && remote > local

    /** 是否该发起检查：已同意隐私政策（未同意不发请求） */
    fun shouldCheck(privacyAgreed: Boolean): Boolean = privacyAgreed

    /**
     * 从 release 资产名里挑本机该下的那个 APK。
     * 先按 ABI 精确匹配，挑不到再退 `universal`；都没有则 null（只提示、不给下载）。
     */
    fun selectApkAsset(assetNames: List<String>, abi: String): String? {
        val apks = assetNames.filter { it.endsWith(".apk", ignoreCase = true) }
        val wanted = when (abi.lowercase()) {
            "arm64-v8a", "arm64" -> "arm64-v8a"
            "armeabi-v7a", "armeabi", "arm" -> "armeabi-v7a"
            "x86_64", "x86-64", "x64" -> "x86_64"
            "x86" -> "x86"
            else -> null
        }
        if (wanted != null) {
            apks.firstOrNull { it.contains(wanted, ignoreCase = true) }?.let { return it }
        }
        return apks.firstOrNull { it.contains("universal", ignoreCase = true) }
    }

    /** 用户点过"跳过此版本"之后，同版本（或更旧）不再提示 */
    fun shouldSkip(remote: SemVer?, lastSkippedCode: Int): Boolean {
        val r = remote ?: return true
        return r.encode() <= lastSkippedCode
    }
}
