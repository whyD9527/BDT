package com.imcys.bilibilias.data.download.cancel

import kotlinx.coroutines.CancellationException

/**
 * 「取消」与「失败」必须区分开的纯规则。
 *
 * ## 为什么要有它（第十八轮审查）
 * `DownloadExecutor` 的重试循环与读写循环都写着 `catch (e: Exception)` —— 而
 * **`CancellationException` 也是 `Exception`**。于是用户在下载途中点「暂停」/「取消」时：
 *
 * 1. 被吞掉，还被当成一次"下载异常"记进日志；
 * 2. 重新进入重试，**白白重试 5 次**（每次都在一个已取消的协程里立刻失败）；
 * 3. 循环走完返回 false → 上层把它当成"文件下载失败（已重试 5 次）"→
 *    `failTask` 把任务置成 **ERROR 并弹「下载失败」提示**。
 *
 * 用户明明是自己按的取消，界面却报下载失败 —— 而且这条 ERROR 会被队列当成"可以重下"的状态，
 * 语义整个乱了。规则很简单：**取消不是失败，必须原样抛出去**。
 */
object DownloadCancellationRules {

    /** cause 链最多看这么多层（同时防住自引用造成的死循环） */
    private const val MAX_CAUSE_DEPTH = 16

    /**
     * 这个异常能不能算"一次可重试的下载失败"。
     *
     * 取消 ⇒ **false**：它不是失败，是调用方要求停。
     *
     * 调用点统一长这样（catch 到之后第一件事就是用它把取消放走）：
     * ```kotlin
     * } catch (e: Exception) {
     *     if (!DownloadCancellationRules.isRetryableFailure(e)) throw e
     *     Log.e(TAG, "下载异常", e)
     * }
     * ```
     */
    fun isRetryableFailure(error: Throwable): Boolean = !isCancellation(error)

    /**
     * 异常链里只要有一环是取消，就算取消。
     *
     * 之所以顺着 `cause` 看：取消经常被包一层（`RuntimeException("下载流读写异常", CancellationException)`），
     * 只看最外层就会把它又当成"可重试失败"—— 同一个 bug 换个形状复发。
     */
    fun isCancellation(error: Throwable?): Boolean {
        var current = error
        repeat(MAX_CAUSE_DEPTH) {
            if (current == null) return false
            if (current is CancellationException) return true
            current = current.cause
        }
        return false
    }
}
