package com.imcys.bilibilias.data.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `readOnce()` 的测试 —— 它钉的其实是"**不要**在不会结束的流上用 `last()`"这件事。
 *
 * 背景（第二十四轮真机发现）：`getSegmentAll()` 是 Room 的热流，永不 complete，
 * 而 `initDownloadList` 用了 `.last()` → 那段代码**一次都没跑过**，
 * 真机上表现为"重启后没有任何清理日志"。
 */
class FlowReadOnceTest {

    @Test
    fun `readOnce 在不会结束的流上也能立刻拿到当前值`() = runBlocking {
        // MutableStateFlow 就是"永不结束"的流 —— 与 Room 的 Flow 同类
        val neverCompleting = MutableStateFlow(listOf("当前值"))
        val value = withTimeoutOrNull(1_000) { neverCompleting.readOnce() }
        assertEquals(listOf("当前值"), value)
    }

    @Test
    fun `反证：last 在同样的流上永远不返回（这就是那个 bug）`() = runBlocking {
        val neverCompleting = MutableStateFlow(listOf("当前值"))
        val value = withTimeoutOrNull(200) { neverCompleting.last() }
        // 超时 ⇒ last() 挂住了。若哪天 kotlinx 改了语义导致这里不是 null，
        // 说明这条注释与 FlowReadOnce 的存在理由需要重新 Review。
        assertNull("last() 不该在不会结束的流上返回", value)
    }

    @Test
    fun `会结束的流两种读法结果相同`() = runBlocking {
        val single = flow { emit(1); emit(2) }
        assertEquals(1, single.readOnce())
        // 对冷流（一次发完就结束）来说 last 也没问题 —— 所以这个坑只在热流上
        assertEquals(2, flow { emit(1); emit(2) }.last())
    }
}
