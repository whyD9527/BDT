package com.imcys.bilibilias.data.clipboard

import com.imcys.bilibilias.datastore.AppSettings

/**
 * 「要不要读剪贴板、读到的这段文本要不要处理」的纯规则。
 *
 * ## 原来错在哪（第十八轮审查的低危项）
 * 1. **隐私门槛两处不一致**：首页写的是 `agreePrivacyPolicy != Default`（于是
 *    **"已拒绝"和"还没选"都会被读**），解析页干脆用的是默认的 `{ true }` —— 一点门槛都没有。
 *    把门槛收进这一处，两边的差异就不可能再出现。
 * 2. **靠"清空用户剪贴板"来去重**：识别成功就把剪贴板清空，等于**把用户复制的东西弄没了**
 *    （他还想粘到别处）。去重应该只记"我处理过这段文本"，而不是动用户的剪贴板。
 */
object ClipboardHandlingRules {

    /**
     * 现在允许读剪贴板吗？
     *
     * 只有**用户明确同意**（`Agreed`）才允许：
     * 尚未选择（`Default`）与明确拒绝（`NotAgreed` / `Refuse`）都**不读**。
     */
    fun canHandleClipboard(
        privacyState: AppSettings.AgreePrivacyPolicyState,
        autoHandlingEnabled: Boolean,
    ): Boolean =
        autoHandlingEnabled &&
            privacyState == AppSettings.AgreePrivacyPolicyState.Agreed

    /**
     * 这段剪贴板文本要不要交给上层处理。
     *
     * @param text 刚读到的文本（可能是 null / 空白 / 已经处理过的那一段）
     * @param lastHandledText 本进程内**上次已经处理过**的文本；
     *   同一段文本不重复处理 —— 这就是"清空剪贴板"原本想达到的效果，
     *   但不动用户的剪贴板。
     */
    fun shouldHandleText(text: String?, lastHandledText: String?): Boolean =
        !text.isNullOrBlank() && text != lastHandledText
}
