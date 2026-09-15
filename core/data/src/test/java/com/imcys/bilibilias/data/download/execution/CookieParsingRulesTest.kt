package com.imcys.bilibilias.data.download.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cookie 文本解析的测试。
 *
 * 守的是 2026-09-14 全量审计的 H5：原实现直接 `URLDecoder.decode`，
 * 遇到裸 `%` 就抛 IllegalArgumentException，而它在输入框 `onValueChange` 里**每次按键**
 * 同步执行、链路无捕获 → 用户敲字符即崩。
 */
class CookieParsingRulesTest {

    @Test
    fun `裸百分号不再抛异常（这就是 H5）`() {
        // 不崩是第一要求
        val parsed = CookieParsingRules.parse("SESSDATA=abc%")
        assertEquals(1, parsed.size)
        assertEquals("SESSDATA", parsed[0].name)
        assertEquals("abc%", parsed[0].value)
    }

    @Test
    fun `不完整转义一律退回原串`() {
        assertEquals("%z", CookieParsingRules.safeDecode("%z"))
        assertEquals("%2", CookieParsingRules.safeDecode("%2"))
        assertEquals("%", CookieParsingRules.safeDecode("%"))
        // 单独一个 % 出现在整串里也不能崩
        val parsed = CookieParsingRules.parse("a=b%c; d=e")
        assertEquals(2, parsed.size)
        assertEquals("b%c", parsed[0].value)
    }

    @Test
    fun `正常转义照旧解码`() {
        assertEquals("a b", CookieParsingRules.safeDecode("a%20b"))
        assertEquals("中", CookieParsingRules.safeDecode("%E4%B8%AD"))
    }

    @Test
    fun `加号仍然按空格处理（保持老行为）`() {
        assertEquals("a b", CookieParsingRules.safeDecode("a+b"))
    }

    @Test
    fun `按分号切分并去掉空白`() {
        val parsed = CookieParsingRules.parse("  SESSDATA=1 ; bili_jct=2;DedeUserID=3 ")
        assertEquals(listOf("SESSDATA", "bili_jct", "DedeUserID"), parsed.map { it.name })
        assertEquals(listOf("1", "2", "3"), parsed.map { it.value })
    }

    @Test
    fun `没有等号的片段跳过、空名字跳过`() {
        // `=novalue` 的名字去空白后是空串 —— 这种条目对 Cookie 没意义，直接丢掉
        val parsed = CookieParsingRules.parse("garbage; =novalue; ok=1")
        assertEquals(1, parsed.size)
        assertEquals("ok", parsed[0].name)
        assertEquals("1", parsed[0].value)
    }

    @Test
    fun `值里带等号时按第一个等号切`() {
        val parsed = CookieParsingRules.parse("a=b=c")
        assertEquals("a", parsed[0].name)
        assertEquals("b=c", parsed[0].value)
    }

    @Test
    fun `空串与只有空白不产出任何条目`() {
        assertTrue(CookieParsingRules.parse("").isEmpty())
        assertTrue(CookieParsingRules.parse("   ").isEmpty())
    }
}
