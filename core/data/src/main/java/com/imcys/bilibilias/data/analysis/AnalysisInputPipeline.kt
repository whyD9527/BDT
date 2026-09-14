package com.imcys.bilibilias.data.analysis

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce

/**
 * 解析输入管线：把「用户输入 / 粘贴的文本」变成「防抖后的解析请求」。
 *
 * ## 为什么单独抽出来
 * 这段管线原先写在 `:app` 的 `AnalysisViewModel` 里，是
 * `MutableStateFlow<String?>` ＋ `debounce(1s)` ＋ `distinctUntilChanged()`。
 * 而 **`MutableStateFlow` 对"等值"不发射**，再叠一层去重，于是"同一段文本再提交一次"
 * 永远不会有任何反应（`distinctUntilChanged` 在这里其实是冗余的 —— StateFlow 自己就不发射等值，
 * 两重去重让这个行为更隐蔽）。后果：
 *
 * - 用户把同一个链接**再粘一次** → 页面毫无变化，看起来像卡死；
 * - 上一次解析失败后，**错误卡片上的「重试」按钮根本无法实现** ——
 *   要重试的恰恰是同一段文本；
 * - 输入框里把文本删掉再原样输回来，同样没反应。
 *
 * 换成 `MutableSharedFlow`（**不做等值去重**）后，每次提交都是一次真实的解析请求；
 * 防抖仍然保留（用户连续输入时只解析停下来后的那一次）。
 *
 * 抽到库模块是因为它有一个**只能用单测钉住**的性质：
 * "同一段文本提交两次 ⇒ 必须解析两次"。这条性质在 `:app` 里没有任何测试能覆盖
 * （`:app` 的单测在本机跑不了），而它正是这个 bug 的全部内容。
 */
class AnalysisInputPipeline(private val debounceMillis: Long = 1000L) {

    // replay=0：订阅者只收到"提交之后"的请求，不会被重放一个旧输入；
    // DROP_OLDEST：连续快速输入时丢中间态正是防抖想要的，也让 tryEmit 永不失败。
    private val submissions = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 防抖后的解析请求流。每次 [submit] 都会在上游产生一个元素（**不做等值去重**）。 */
    val requests: Flow<String> = submissions.debounce(debounceMillis)

    /**
     * 提交一段待解析文本。
     *
     * **同一段文本重复提交也必须重新发射** —— 「再粘一次」和错误卡片的「重试」都靠这个。
     */
    fun submit(text: String) {
        submissions.tryEmit(text)
    }
}
