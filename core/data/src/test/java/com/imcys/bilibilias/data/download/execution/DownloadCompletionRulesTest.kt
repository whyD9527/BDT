package com.imcys.bilibilias.data.download.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「下载算不算成功」的测试。
 *
 * 守的是 2026-09-14 全量审计的 H1/H2：
 * - H1：单连接写完**从不核对实收字节数**，CDN 提前断流 → 半截文件当成品交付；
 * - H2：单连接成功路径**丢弃 `renameTo` 返回值** → 报成功但目标文件不存在。
 */
class DownloadCompletionRulesTest {

    @Test
    fun `收满字节且改名成功才算成功`() {
        assertEquals(
            DownloadCompletionRules.Outcome.SUCCESS,
            DownloadCompletionRules.judge(expectedLength = 1000, receivedLength = 1000, renamed = true),
        )
    }

    @Test
    fun `服务端提前断流时必须判失败（这就是 H1）`() {
        assertEquals(
            DownloadCompletionRules.Outcome.INCOMPLETE,
            DownloadCompletionRules.judge(expectedLength = 1000, receivedLength = 999, renamed = true),
        )
    }

    @Test
    fun `差一个字节也算失败（允许等于、不允许小于）`() {
        assertEquals(
            DownloadCompletionRules.Outcome.SUCCESS,
            DownloadCompletionRules.judge(expectedLength = 1, receivedLength = 1, renamed = true),
        )
        assertEquals(
            DownloadCompletionRules.Outcome.INCOMPLETE,
            DownloadCompletionRules.judge(expectedLength = 2, receivedLength = 1, renamed = true),
        )
    }

    @Test
    fun `改名失败优先于字节数判定（H2）`() {
        // 改名失败时目标路径上根本没有文件，字节再够也不能算成功
        assertEquals(
            DownloadCompletionRules.Outcome.RENAME_FAILED,
            DownloadCompletionRules.judge(expectedLength = 1000, receivedLength = 1000, renamed = false),
        )
        assertEquals(
            DownloadCompletionRules.Outcome.RENAME_FAILED,
            DownloadCompletionRules.judge(expectedLength = 1000, receivedLength = 10, renamed = false),
        )
    }

    @Test
    fun `长度未知时不判字节数（分块传输不该被误杀）`() {
        assertEquals(
            DownloadCompletionRules.Outcome.SUCCESS,
            DownloadCompletionRules.judge(expectedLength = -1, receivedLength = 123, renamed = true),
        )
        assertEquals(
            DownloadCompletionRules.Outcome.SUCCESS,
            DownloadCompletionRules.judge(expectedLength = 0, receivedLength = 0, renamed = true),
        )
        assertTrue(DownloadCompletionRules.lengthIsUnknown(-1))
        assertTrue(DownloadCompletionRules.lengthIsUnknown(0))
        assertFalse(DownloadCompletionRules.lengthIsUnknown(1))
    }

    @Test
    fun `实收比声明还多不算失败（服务端多发时由分片层裁剪）`() {
        assertEquals(
            DownloadCompletionRules.Outcome.SUCCESS,
            DownloadCompletionRules.judge(expectedLength = 100, receivedLength = 120, renamed = true),
        )
    }
}
