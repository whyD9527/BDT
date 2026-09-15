package com.imcys.bilibilias.data.download.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成品正式名判定的测试。
 *
 * 守的是 2026-09-15 真机复现的那条：重下同一集时改名静默失败 + 旧文件没删掉，
 * 于是下载目录里出现 `xxx (1).mp3`、`xxx (2).mp3` 越攒越多。
 */
class FinalNameVerifyRulesTest {

    private val name = "鸣潮 _ 先约电台EP.mp3"
    private val staging = "鸣潮 _ 先约电台EP.mp3.part"

    @Test
    fun `正式名在、暂存名不在 —— 这才是成功`() {
        assertEquals(
            FinalNameVerifyRules.Verdict.OK,
            FinalNameVerifyRules.verify(name, staging, listOf(name)),
        )
    }

    @Test
    fun `只剩暂存名判定为改名失败（真机上就是这个状态）`() {
        assertEquals(
            FinalNameVerifyRules.Verdict.KEPT_STAGING_NAME,
            FinalNameVerifyRules.verify(name, staging, listOf(staging)),
        )
        // 暂存名与正式名同时在，也算没改成功
        assertEquals(
            FinalNameVerifyRules.Verdict.KEPT_STAGING_NAME,
            FinalNameVerifyRules.verify(name, staging, listOf(name, staging)),
        )
    }

    @Test
    fun `出现同名副本时判定为重复（旧文件没删掉）`() {
        assertEquals(
            FinalNameVerifyRules.Verdict.DUPLICATE_SUFFIX,
            FinalNameVerifyRules.verify(name, staging, listOf("$name (1).mp3".let { "鸣潮 _ 先约电台EP (1).mp3" })),
        )
    }

    @Test
    fun `两个都没有就是彻底失败`() {
        assertEquals(
            FinalNameVerifyRules.Verdict.MISSING,
            FinalNameVerifyRules.verify(name, staging, listOf("别的东西.mp3")),
        )
        assertEquals(
            FinalNameVerifyRules.Verdict.MISSING,
            FinalNameVerifyRules.verify(name, staging, emptyList()),
        )
    }

    @Test
    fun `副本名识别要拒绝不像副本的名字`() {
        val expected = "abc.mp3"
        assertTrue(FinalNameVerifyRules.isDuplicateNameOf("abc (1).mp3", expected))
        assertTrue(FinalNameVerifyRules.isDuplicateNameOf("abc (23).mp3", expected))
        assertFalse("没有括号不算副本", FinalNameVerifyRules.isDuplicateNameOf("abc.mp3", expected))
        assertFalse("数字不带括号不算", FinalNameVerifyRules.isDuplicateNameOf("abc 1.mp3", expected))
        assertFalse("括号里不是数字不算", FinalNameVerifyRules.isDuplicateNameOf("abc (a).mp3", expected))
        assertFalse("后缀不同不算", FinalNameVerifyRules.isDuplicateNameOf("abc (1).mp4", expected))
        assertFalse("前缀不同不算", FinalNameVerifyRules.isDuplicateNameOf("abcd (1).mp3", expected))
        assertFalse("括号没闭合不算", FinalNameVerifyRules.isDuplicateNameOf("abc (1.mp3", expected))
    }

    @Test
    fun `没有扩展名的名字也能判副本`() {
        assertTrue(FinalNameVerifyRules.isDuplicateNameOf("abc (1)", "abc"))
        assertFalse(FinalNameVerifyRules.isDuplicateNameOf("abc (1).tmp", "abc"))
    }

    @Test
    fun `按目录枚举挑出同一份内容的所有副本`() {
        val names = listOf(
            "别的文件.mp3",
            "鸣潮 _ 先约电台EP.mp3",
            "鸣潮 _ 先约电台EP (1).mp3",
            "鸣潮 _ 先约电台EP (2).mp3",
            "鸣潮 _ 先约电台EP.mp3.part",
        )
        assertEquals(
            listOf("鸣潮 _ 先约电台EP.mp3", "鸣潮 _ 先约电台EP (1).mp3", "鸣潮 _ 先约电台EP (2).mp3"),
            FinalNameVerifyRules.siblingCopies("鸣潮 _ 先约电台EP.mp3", names),
        )
    }

    @Test
    fun `没有同名文件时挑不出任何东西`() {
        assertTrue(
            FinalNameVerifyRules.siblingCopies("x.mp3", listOf("a.mp3", "b.mp3")).isEmpty(),
        )
    }

    @Test
    fun `回读名字必须是正式名才算改名成功`() {
        assertTrue(FinalNameVerifyRules.displayNameMatches(name, name))
        assertFalse("真机上回读到的就是暂存名", FinalNameVerifyRules.displayNameMatches(name, staging))
        assertFalse(FinalNameVerifyRules.displayNameMatches(name, null))
        assertFalse(FinalNameVerifyRules.displayNameMatches(name, "$name (1).mp3".let { "鸣潮 _ 先约电台EP (1).mp3" }))
    }
}
