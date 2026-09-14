package com.imcys.bilibilias.network.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「要不要提示更新」的判断依据 —— 这里是唯一一处，所以必须测。
 *
 * 两种错法代价都明显：
 * - 误报（把老版本当新版）→ 反复骚扰用户；
 * - 漏报（新版本不提示）→ 用户长期停在旧版（3.1.7 那次回归就没人被自动提醒）。
 */
class RemoteVersionTest {

    @Test
    fun `更高版本会提示`() {
        assertTrue(RemoteVersion.isNewer("v3.1.8", "3.1.7"))
        assertTrue(RemoteVersion.isNewer("3.1.8", "3.1.7"))
        assertTrue(RemoteVersion.isNewer("3.2", "3.1.9"))
        assertTrue(RemoteVersion.isNewer("4.0.0", "3.99.99"))
        // 字符串比较会出错、数字比较才对
        assertTrue(RemoteVersion.isNewer("3.1.10", "3.1.9"))
    }

    @Test
    fun `同版本或更低版本不提示`() {
        assertFalse(RemoteVersion.isNewer("3.1.8", "3.1.8"))
        assertFalse(RemoteVersion.isNewer("v3.1.8", "3.1.8"))
        assertFalse(RemoteVersion.isNewer("3.1.7", "3.1.8"))
        // 段数不同：1.2 == 1.2.0
        assertFalse(RemoteVersion.isNewer("3.1", "3.1.0"))
    }

    @Test
    fun `带后缀或非数字的远端版本不乱提示`() {
        // 预发布后缀按前三位比较
        assertTrue(RemoteVersion.isNewer("3.1.8-beta", "3.1.7"))
        assertFalse(RemoteVersion.isNewer("3.1.8-beta", "3.1.8"))
        // 解析不出来的 tag 一律不提示（宁可漏报也不误报）
        assertFalse(RemoteVersion.isNewer("latest", "3.1.7"))
        assertFalse(RemoteVersion.isNewer("", "3.1.7"))
        assertFalse(RemoteVersion.isNewer("v", "3.1.7"))
        assertFalse(RemoteVersion.isNewer("nightly-build", "3.1.7"))
    }
}
