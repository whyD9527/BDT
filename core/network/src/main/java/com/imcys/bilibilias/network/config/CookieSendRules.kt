package com.imcys.bilibilias.network.config

/**
 * 「这个 Cookie 能不能发给这个主机」的纯规则（可单测）。
 *
 * ## 为什么必须有（2026-09-15 全量复审 A-H2）
 * `AsCookiesStorage.get(requestUrl)` 原先**完全不看 requestUrl**，把内存里的全部 Cookie
 * 原样返回；而 Ktor 的 `HttpCookies` 插件会把 `storage.get(url)` 的返回值**直接拼进 Cookie 头**，
 * 不做任何 domain 过滤。后果是完整登录凭据（`SESSDATA` / `bili_jct`，等价账号密码）被发到：
 * - 更新检查的 `api.github.com`（每次启动就发，隐私弹窗都还没点）；
 * - 用户粘贴的短链跳转目标（`shortLink` 会跟随重定向）；
 * - 用户自己配置的第三方下载线路主机。
 *
 * ## 规则（刻意保守，优先保证"不破坏 B 站登录"）
 * 1. **B 站主机 → 一律照发**：保持改造前的行为，登录态绝不能因为这次加固受影响；
 * 2. **非 B 站主机 → 只发"该主机自己设过的 Cookie"**（RFC 6265 的域匹配）；
 * 3. 没有 domain 的历史 Cookie 只当作 B 站的，**不外发**。
 *
 * ⚠️ 注意域的边界：`notbilibili.com`、`bilibili.com.evil.com` 都**不是** B 站主机 ——
 * 必须用「相等或 `.后缀`」的域匹配，不能用子串（`AsRegexUtil` 那边就踩过子串匹配的坑）。
 *
 * 还没做的（已知，留作后续）：path / secure / 过期时间的过滤。要一次做全，最稳的是给
 * "任意 URL"（GitHub、短链、自定义线路）单独用一个**不装 cookies 的 client**。
 */
object CookieSendRules {

    /** B 站主机的后缀白名单（判据：`host == 后缀` 或 `host.endsWith(".后缀")`） */
    private val BILI_HOST_SUFFIXES = listOf(
        "bilibili.com",
        "b23.tv",
        "bili2233.cn",
    )

    /**
     * 这个 Cookie 能不能发给这个主机。
     *
     * @param requestHost 本次请求的主机（`Url.host`）
     * @param cookieDomain Cookie 自己的 domain（可空：历史行里可能是空串）
     */
    fun shouldSend(requestHost: String, cookieDomain: String?): Boolean {
        val host = normalizeHost(requestHost) ?: return false
        if (isBilibiliHost(host)) return true
        // 非 B 站主机：只有"这个域自己设过的 Cookie"才发回去
        val domain = normalizeDomain(cookieDomain) ?: return false
        return hostMatchesDomain(host, domain)
    }

    /** 是不是 B 站（含子域）的主机 */
    fun isBilibiliHost(requestHost: String): Boolean {
        val host = normalizeHost(requestHost) ?: return false
        return BILI_HOST_SUFFIXES.any { hostMatchesDomain(host, it) }
    }

    /**
     * RFC 6265 的域匹配：完全相等，或"主机是它的子域"。
     *
     * 刻意不做子串匹配：`notbilibili.com.endsWith("bilibili.com")` 为真，但那种主机不是 B 站。
     */
    fun hostMatchesDomain(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    private fun normalizeHost(host: String?): String? =
        host?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() }

    /** Cookie 的 domain 可能带前导点（`.bilibili.com`），比较前统一去掉 */
    private fun normalizeDomain(domain: String?): String? =
        domain?.trim()?.trimStart('.')?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() }
}
