package com.imcys.bilibilias.data.analysis

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 解析输入管线的测试。
 *
 * 守的性质只有一条，但它是第十八轮审查里那个 bug 的**全部内容**：
 * **同一段文本提交两次，必须解析两次。**
 *
 * 原先的实现是 `MutableStateFlow` ＋ `distinctUntilChanged()` —— StateFlow 对等值不发射，
 * 于是"同一个链接再粘一次"和"失败后点重试"都毫无反应、页面看起来像卡死。
 * 这条性质只能在这里用单测钉住（`:app` 在本机跑不了单测，见交接文档第十五轮）。
 *
 * ## 为什么改用虚拟时间（第二十六轮）
 * 第一版用真实的 `delay(400)`（后来又改成"等到收到为止"）去等结果，**连着撞了两次随机红**：
 * 负载高的时候第二次提交像是被吞了，等 10 秒都等不到第二次发射。
 * 而这条管线要验的正是**防抖**这种时间行为 —— 拿真实时间去测，就注定要跟机器负载赛跑。
 * 现在换成 `runTest` + `advanceTimeBy`：时钟是虚拟的，`advanceTimeBy(150)` 一定跨过
 * 100ms 的防抖窗口，**结果确定、也不再需要等待**（整类测试从秒级降到毫秒级）。
 */
class AnalysisInputPipelineTest {

    private val debounceMillis = 100L

    /** 推进过防抖窗口：比窗口大一点就够，虚拟时间下这是确定的 */
    private fun TestScope.pastDebounce() {
        advanceTimeBy(debounceMillis + 50)
        runCurrent()
    }

    @Test
    fun `同一段文本提交两次必须解析两次`() = runTest {
        val pipeline = AnalysisInputPipeline(debounceMillis = debounceMillis)
        val received = mutableListOf<String>()
        backgroundScope.launch { pipeline.requests.collect { received += it } }
        runCurrent() // 让收集器真的订阅上（MutableSharedFlow 没有订阅者时不会转发）

        pipeline.submit("BV1xx411c7mD")
        pastDebounce()
        // 关键动作：**原样再提交一次**。改造前这里不会有任何反应。
        pipeline.submit("BV1xx411c7mD")
        pastDebounce()

        assertEquals(listOf("BV1xx411c7mD", "BV1xx411c7mD"), received)
    }

    @Test
    fun `连续提交只解析最后一次（防抖还在）`() = runTest {
        val pipeline = AnalysisInputPipeline(debounceMillis = debounceMillis)
        val received = mutableListOf<String>()
        backgroundScope.launch { pipeline.requests.collect { received += it } }
        runCurrent()

        // 三次提交都落在同一个防抖窗口里（虚拟时间没推进，它们就是"同一瞬间"）
        pipeline.submit("BV1")
        pipeline.submit("BV1xx")
        pipeline.submit("BV1xx411c7mD")
        pastDebounce()

        assertEquals(listOf("BV1xx411c7mD"), received)
    }

    @Test
    fun `两次解析之间隔开防抖窗口就都要解析`() = runTest {
        val pipeline = AnalysisInputPipeline(debounceMillis = debounceMillis)
        val received = mutableListOf<String>()
        backgroundScope.launch { pipeline.requests.collect { received += it } }
        runCurrent()

        pipeline.submit("BV1xx411c7mD")
        pastDebounce()
        pipeline.submit("av170001")
        pastDebounce()

        assertEquals(listOf("BV1xx411c7mD", "av170001"), received)
    }

    @Test
    fun `没有订阅者时提交的历史不会被重放`() = runTest {
        val pipeline = AnalysisInputPipeline(debounceMillis = debounceMillis)
        // 订阅之前先提交：这些请求是"没人听"的，之后订阅上来**不该**被重放
        // （replay=0 的语义）。否则 ViewModel 一旦重新订阅就会莫名重解析一段旧文本。
        pipeline.submit("旧输入")

        val received = mutableListOf<String>()
        backgroundScope.launch { pipeline.requests.collect { received += it } }
        pastDebounce()

        assertEquals(emptyList<String>(), received)
    }
}
