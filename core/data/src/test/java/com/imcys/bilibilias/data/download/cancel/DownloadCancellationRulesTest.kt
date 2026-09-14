package com.imcys.bilibilias.data.download.cancel

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 「取消 ≠ 失败」规则的测试。
 *
 * 守的是第十八轮审查里那条：暂停/取消时 `CancellationException` 被
 * `catch (e: Exception)` 吞掉 → 白重试 5 次 → 任务被记成 ERROR 并弹「下载失败」。
 */
class DownloadCancellationRulesTest {

    /** 协程取消用的异常在真实场景里是 CancellationException 的子类 */
    private class FakeJobCancellation : CancellationException("job was cancelled")

    @Test
    fun `取消不算可重试的失败`() {
        // 关键用例：这条一旦失效，用户点暂停就会变成"下载失败"＋弹提示
        assertFalse(DownloadCancellationRules.isRetryableFailure(CancellationException("用户暂停")))
        assertFalse(DownloadCancellationRules.isRetryableFailure(FakeJobCancellation()))
    }

    @Test
    fun `真正的 IO 失败仍然可重试`() {
        assertTrue(DownloadCancellationRules.isRetryableFailure(IOException("连接被重置")))
        assertTrue(DownloadCancellationRules.isRetryableFailure(IllegalStateException("响应异常")))
    }

    @Test
    fun `取消的包装异常（cause 是取消）也算取消`() {
        // 协程里常见的形态：业务异常把取消包在里面
        val wrapped = RuntimeException("下载流读写异常", CancellationException("暂停"))
        assertFalse(DownloadCancellationRules.isRetryableFailure(wrapped))
    }
}
