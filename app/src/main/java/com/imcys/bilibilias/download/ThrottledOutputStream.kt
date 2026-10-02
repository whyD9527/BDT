package com.imcys.bilibilias.download

import com.imcys.bilibilias.data.download.policy.DownloadPolicyRules
import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * 限速输出流（B5）：按"累计写入量 vs 按限速允许的写入量"算该等多久。
 *
 * 用的是 [DownloadPolicyRules.sleepMillisFor]（纯函数、有单测）：
 * - 网络本来就比限速慢 → 永远返回 0，**一点都不会额外拖慢**；
 * - 写得比限速快 → 才 sleep 补回来。
 *
 * 为什么不做成"每个 write 固定 sleep"：那样在慢网/小文件上会莫名其妙地更慢，
 * 而且总时长不可控。这里对齐的是**平均速率**。
 */
class ThrottledOutputStream(
    out: OutputStream,
    private val limitKbps: Int,
) : FilterOutputStream(out) {

    private val startedAt = System.nanoTime()
    private var written = 0L

    override fun write(b: Int) {
        throttle(1)
        out.write(b)
    }

    override fun write(b: ByteArray) {
        throttle(b.size)
        out.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        throttle(len)
        out.write(b, off, len)
    }

    private fun throttle(bytes: Int) {
        written += bytes
        if (limitKbps <= 0) return
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        val waitMs = DownloadPolicyRules.sleepMillisFor(written, elapsedMs, limitKbps)
        if (waitMs > 0) {
            runCatching { Thread.sleep(waitMs.coerceAtMost(2000L)) }
        }
    }
}
