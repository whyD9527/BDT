package com.imcys.bilibilias.data.diagnostics

/**
 * 「问题反馈」页的纯规则（可单测）：把**状态自检**与**反馈包内容**这两件"纯字符串/布尔"的事
 * 从 UI 里抽出来。
 *
 * 背景（2026-10-02 用户反馈）：反馈/诊断入口此前散在三处（存储管理导出日志、版本页复制信息、
 * 工具列表的 BugReport），用户不知道该去哪、还得自己去文件管理器找导出的文件。现在合并成一个页面，
 * 并且要"一键导出反馈包 + 直接分享"。页面上的标签文案走 `strings.xml`（中英双套），
 * 所以这里**只产出 key 与值**，不产出任何中文文案。
 */
object FeedbackReportRules {

    /** 状态自检的条目标识（UI 按它取本地化标签/说明） */
    enum class StatusKey {
        /** 应用版本 */
        APP_VERSION,

        /** 更新检查结果 */
        UPDATE,

        /** 隐私政策是否已同意（未同意 = 不识别剪贴板、不检查更新） */
        PRIVACY,

        /** 「所有文件访问」是否已授权（缺了会导致交付入库异常/同名覆盖失效） */
        ALL_FILES_ACCESS,

        /** 通知权限是否开启（关了就完全没有通知） */
        NOTIFICATION,

        /** 当前缓存线路 */
        LINE,

        /** 命名规则摘要 */
        NAMING,
    }

    /**
     * 一条状态。[ok] 为 false 表示"需要用户处理"，UI 会给它加醒目样式；
     * [value] 是**可以直接展示的原始值**（版本号、线路名等，属数据不译）。
     */
    data class StatusItem(
        val key: StatusKey,
        val value: String,
        val ok: Boolean,
    )

    /** 构建状态自检（顺序固定，便于真机逐条核对） */
    fun buildStatus(
        appVersion: String,
        updateResult: String?,
        privacyAgreed: Boolean,
        allFilesAccess: Boolean,
        notificationEnabled: Boolean,
        lineName: String?,
        namingSummary: String?,
    ): List<StatusItem> = listOf(
        StatusItem(StatusKey.APP_VERSION, appVersion.ifBlank { "-" }, true),
        StatusItem(StatusKey.UPDATE, updateResult?.ifBlank { null } ?: "-", updateResult != null),
        StatusItem(StatusKey.PRIVACY, if (privacyAgreed) "agreed" else "declined", privacyAgreed),
        StatusItem(StatusKey.ALL_FILES_ACCESS, if (allFilesAccess) "granted" else "denied", allFilesAccess),
        StatusItem(StatusKey.NOTIFICATION, if (notificationEnabled) "enabled" else "disabled", notificationEnabled),
        StatusItem(StatusKey.LINE, lineName?.ifBlank { null } ?: "-", true),
        StatusItem(StatusKey.NAMING, namingSummary?.ifBlank { null } ?: "-", true),
    )

    /**
     * 反馈包正文：**一个文件搞定**，方便用户直接分享。
     * 内容顺序：状态自检 → 设备与版本信息 → 诊断日志（尾部 N 行）→ 崩溃日志（若有）。
     */
    fun buildReportText(
        status: List<StatusItem>,
        deviceInfo: List<Pair<String, String>>,
        logTail: String?,
        crashTail: String?,
    ): String = buildString {
        appendLine("===== 状态自检 =====")
        status.forEach { appendLine("${it.key}: ${it.value}${if (it.ok) "" else "  ← 需要处理"}") }
        appendLine()
        appendLine("===== 设备与版本信息 =====")
        deviceInfo.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine()
        appendLine("===== 诊断日志（最近部分）=====")
        appendLine(logTail?.takeIf { it.isNotBlank() } ?: "（空）")
        if (!crashTail.isNullOrBlank()) {
            appendLine()
            appendLine("===== 崩溃日志（crash.log）=====")
            appendLine(crashTail)
        }
    }

    /** 反馈包文件名（带时间戳，避免覆盖） */
    fun reportFileName(timestamp: String): String = "BDT-反馈包-$timestamp.txt"

    /** GitHub Issue 预填链接（把版本与关键状态带进 body，省得用户手打） */
    fun issueUrl(appVersion: String, status: List<StatusItem>): String {
        val body = buildString {
            appendLine("**应用版本**：$appVersion")
            appendLine()
            appendLine("**状态自检**：")
            status.forEach { appendLine("- ${it.key}: ${it.value}") }
            appendLine()
            appendLine("**问题描述**：")
            appendLine("（请在这里描述遇到的问题、复现步骤）")
            appendLine()
            appendLine("**我已在应用内导出反馈包并附上**：是 / 否")
        }
        return "https://github.com/whyD9527/BDT/issues/new?body=" +
            java.net.URLEncoder.encode(body, "UTF-8")
    }
}
