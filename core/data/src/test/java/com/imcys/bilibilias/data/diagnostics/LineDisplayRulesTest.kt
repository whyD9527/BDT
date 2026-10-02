package com.imcys.bilibilias.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class LineDisplayRulesTest {

    @Test
    fun `裸 host 映射成可读品牌名`() {
        assertEquals("ali（阿里）", LineDisplayRules.displayName("upos-sz-mirrorali.bilivideo.com"))
        // 长关键字优先：alib / alio1 不能被 ali 抢走
        assertEquals("alib（阿里）", LineDisplayRules.displayName("upos-sz-mirroralib.bilivideo.com"))
        assertEquals("alio1（阿里）", LineDisplayRules.displayName("upos-sz-mirroralio1.bilivideo.com"))
        assertEquals("B站默认（upos）", LineDisplayRules.displayName("upos-sz-mirror01.bilivideo.com"))
        assertEquals("腾讯云（cos）", LineDisplayRules.displayName("upos-sz-mirrorcos.bilivideo.com"))
    }

    @Test
    fun `空值=默认线路；未知 host 原样返回（不编名字）`() {
        assertEquals(LineDisplayRules.DEFAULT_LINE_NAME, LineDisplayRules.displayName(null))
        assertEquals(LineDisplayRules.DEFAULT_LINE_NAME, LineDisplayRules.displayName("   "))
        assertEquals("example.cdn.test", LineDisplayRules.displayName("example.cdn.test"))
    }
}
