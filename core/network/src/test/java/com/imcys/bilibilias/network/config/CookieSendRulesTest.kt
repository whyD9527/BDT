package com.imcys.bilibilias.network.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cookie 外发范围的测试（2026-09-15 全量复审 A-H2）。
 *
 * 守的是一条安全性质：**B 站登录凭据（SESSDATA / bili_jct）只能发给 B 站主机**。
 * 改造前 `AsCookiesStorage.get()` 不看 requestUrl，全部 Cookie 会被发到
 * `api.github.com`（更新检查）、短链跳转目标、用户自建的第三方下载线路主机。
 */
class CookieSendRulesTest {

    @Test
    fun `B 站主机照发（含子域），保证登录态不受影响`() {
        val biliCookie = "bilibili.com"
        assertTrue(CookieSendRules.shouldSend("bilibili.com", biliCookie))
        assertTrue(CookieSendRules.shouldSend("api.bilibili.com", biliCookie))
        assertTrue(CookieSendRules.shouldSend("passport.bilibili.com", biliCookie))
        assertTrue(CookieSendRules.shouldSend("api.live.bilibili.com", biliCookie))
        assertTrue(CookieSendRules.shouldSend("b23.tv", biliCookie))
        assertTrue(CookieSendRules.shouldSend("www.bili2233.cn", biliCookie))
        // 大小写 / 结尾点 / 前导点都要归一化
        assertTrue(CookieSendRules.shouldSend("API.BiliBili.com.", ".BiliBili.com"))
    }

    @Test
    fun `更新检查的 GitHub 绝不带 B 站 Cookie`() {
        assertFalse(CookieSendRules.shouldSend("api.github.com", "bilibili.com"))
        assertFalse(CookieSendRules.shouldSend("github.com", "bilibili.com"))
        assertFalse(CookieSendRules.shouldSend("uploads.github.com", "passport.bilibili.com"))
    }

    @Test
    fun `用户自建的第三方线路主机不带 B 站 Cookie`() {
        assertFalse(
            CookieSendRules.shouldSend(
                "cze5361c.edge.mountaintoys.cn",
                "bilibili.com",
            ),
        )
        assertFalse(CookieSendRules.shouldSend("cdn.example.com", "api.bilibili.com"))
    }

    @Test
    fun `域名必须按边界匹配，子串相似的一律不是 B 站`() {
        // `AsRegexUtil` 那边就是栽在子串匹配上：notbilibili.com 里含 bilibili.com
        assertFalse(CookieSendRules.isBilibiliHost("notbilibili.com"))
        assertFalse(CookieSendRules.isBilibiliHost("bilibili.com.evil.com"))
        assertFalse(CookieSendRules.isBilibiliHost("fakeb23.tv"))
        assertFalse(CookieSendRules.shouldSend("notbilibili.com", "bilibili.com"))
        assertFalse(CookieSendRules.shouldSend("bilibili.com.evil.com", "bilibili.com"))
        assertTrue(CookieSendRules.isBilibiliHost("b.bilibili.com"))
    }

    @Test
    fun `没有 domain 的历史 Cookie 只当作 B 站的，不外发`() {
        assertTrue(CookieSendRules.shouldSend("api.bilibili.com", null))
        assertTrue(CookieSendRules.shouldSend("api.bilibili.com", ""))
        assertTrue(CookieSendRules.shouldSend("api.bilibili.com", "  "))
        assertFalse(CookieSendRules.shouldSend("api.github.com", null))
        assertFalse(CookieSendRules.shouldSend("api.github.com", ""))
    }

    @Test
    fun `第三方主机自己设过的 Cookie 要能发回去（只是不回传 B 站的）`() {
        // RFC 6265：Set-Cookie 没带 Domain 时只属于设它的那个主机
        assertTrue(CookieSendRules.shouldSend("cdn.example.com", "cdn.example.com"))
        assertTrue(CookieSendRules.shouldSend("a.cdn.example.com", "cdn.example.com"))
        assertFalse(CookieSendRules.shouldSend("evil.example.net", "cdn.example.com"))
    }

    @Test
    fun `空主机不发送（防御性）`() {
        assertFalse(CookieSendRules.shouldSend("", "bilibili.com"))
        assertFalse(CookieSendRules.shouldSend("  ", "bilibili.com"))
    }
}
