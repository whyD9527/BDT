package com.imcys.bilibilias.data.clipboard

import com.imcys.bilibilias.datastore.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板门槛与去重的测试。
 *
 * 守的是第十八轮审查里的两条低危：
 * ① 隐私门槛两处不一致（首页把"已拒绝"也放行、解析页干脆没门槛）；
 * ② 靠**清空用户剪贴板**去重（把用户复制的东西弄没了）。
 */
class ClipboardHandlingRulesTest {

    private val agreed = AppSettings.AgreePrivacyPolicyState.Agreed
    private val refused = AppSettings.AgreePrivacyPolicyState.Refuse
    private val notAgreed = AppSettings.AgreePrivacyPolicyState.NotAgreed
    private val default = AppSettings.AgreePrivacyPolicyState.Default

    @Test
    fun `只有明确同意才允许读剪贴板`() {
        assertTrue(ClipboardHandlingRules.canHandleClipboard(agreed, autoHandlingEnabled = true))
    }

    @Test
    fun `拒绝或尚未选择都不许读`() {
        // 关键用例：以前首页的判据是"不等于 Default"，于是"已拒绝"照样会被读
        listOf(refused, notAgreed, default).forEach { state ->
            assertFalse(
                "state=$state 不该读剪贴板",
                ClipboardHandlingRules.canHandleClipboard(state, autoHandlingEnabled = true),
            )
        }
    }

    @Test
    fun `开关关掉时不读，即便已经同意`() {
        assertFalse(ClipboardHandlingRules.canHandleClipboard(agreed, autoHandlingEnabled = false))
    }

    @Test
    fun `同一段文本不重复处理（替代"清空剪贴板"）`() {
        assertTrue(
            "第一次见到就该处理",
            ClipboardHandlingRules.shouldHandleText("https://b23.tv/ep1", lastHandledText = null),
        )
        assertFalse(
            "同一段再读到就不该重复处理",
            ClipboardHandlingRules.shouldHandleText("https://b23.tv/ep1", lastHandledText = "https://b23.tv/ep1"),
        )
        assertTrue(
            "换了内容就该处理",
            ClipboardHandlingRules.shouldHandleText("https://b23.tv/ep2", lastHandledText = "https://b23.tv/ep1"),
        )
    }

    @Test
    fun `空文本与空白文本不处理`() {
        assertFalse(ClipboardHandlingRules.shouldHandleText(null, null))
        assertFalse(ClipboardHandlingRules.shouldHandleText("", null))
        assertFalse(ClipboardHandlingRules.shouldHandleText("   ", null))
    }
}
