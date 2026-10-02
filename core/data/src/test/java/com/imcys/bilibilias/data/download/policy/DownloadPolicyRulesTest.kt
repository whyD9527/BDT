package com.imcys.bilibilias.data.download.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPolicyRulesTest {

    @Test
    fun `只有开了仅Wi-Fi且当前不是Wi-Fi才拦`() {
        assertTrue(DownloadPolicyRules.shouldBlockForWifiOnly(wifiOnly = true, isWifi = false))
        assertFalse(DownloadPolicyRules.shouldBlockForWifiOnly(wifiOnly = true, isWifi = true))
        assertFalse(DownloadPolicyRules.shouldBlockForWifiOnly(wifiOnly = false, isWifi = false))
    }

    @Test
    fun `不限速时永远不等`() {
        assertEquals(0L, DownloadPolicyRules.sleepMillisFor(100_000_000L, 1000L, 0))
        assertEquals(0L, DownloadPolicyRules.sleepMillisFor(100_000_000L, 1000L, -5))
    }

    @Test
    fun `网络本来就比限速慢时不等`() {
        assertEquals(
            0L,
            DownloadPolicyRules.sleepMillisFor(bytesWritten = 100L * 1024, elapsedMs = 1000, limitKbps = 1000),
        )
    }

    @Test
    fun `写太快时按超出量换算成等待毫秒`() {
        val wait = DownloadPolicyRules.sleepMillisFor(
            bytesWritten = 2000L * 1024, elapsedMs = 1000, limitKbps = 1000,
        )
        assertEquals(1000L, wait)
    }

    @Test
    fun `刚启动还没写东西时不等`() {
        assertEquals(0L, DownloadPolicyRules.sleepMillisFor(0L, 0L, 500))
    }
}
