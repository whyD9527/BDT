package com.imcys.bilibilias.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackReportRulesTest {

    private fun status(
        privacy: Boolean = true,
        allFiles: Boolean = true,
        notify: Boolean = true,
        update: String? = "已是最新：本地=3.3.5",
    ) = FeedbackReportRules.buildStatus(
        appVersion = "3.3.5",
        updateResult = update,
        privacyAgreed = privacy,
        allFilesAccess = allFiles,
        notificationEnabled = notify,
        lineName = "ali（阿里）",
        namingSummary = "{title}_{p}",
    )

    @Test
    fun `状态自检：顺序固定，缺权限的项标为需要处理`() {
        val s = status(privacy = false, allFiles = false, notify = false)
        assertEquals(
            listOf(
                FeedbackReportRules.StatusKey.APP_VERSION,
                FeedbackReportRules.StatusKey.UPDATE,
                FeedbackReportRules.StatusKey.PRIVACY,
                FeedbackReportRules.StatusKey.ALL_FILES_ACCESS,
                FeedbackReportRules.StatusKey.NOTIFICATION,
                FeedbackReportRules.StatusKey.LINE,
                FeedbackReportRules.StatusKey.NAMING,
            ),
            s.map { it.key },
        )
        assertFalse("未同意隐私政策要标为需要处理", s.first { it.key == FeedbackReportRules.StatusKey.PRIVACY }.ok)
        assertFalse(s.first { it.key == FeedbackReportRules.StatusKey.ALL_FILES_ACCESS }.ok)
        assertFalse(s.first { it.key == FeedbackReportRules.StatusKey.NOTIFICATION }.ok)
        assertTrue(s.first { it.key == FeedbackReportRules.StatusKey.LINE }.ok)
        // 更新结果为空时不算"需要处理"（只是没查过）
        assertTrue(status(update = null).first { it.key == FeedbackReportRules.StatusKey.UPDATE }.ok)
    }

    @Test
    fun `反馈包：四段齐全，缺崩溃日志时不出现崩溃段`() {
        val text = FeedbackReportRules.buildReportText(
            status = status(privacy = false),
            deviceInfo = listOf("应用版本" to "3.3.5", "设备型号" to "REDMI K90"),
            logTail = "10-02 23:53:40 [更新检查] 已是最新：本地=3.3.5 远端=v3.3.5",
            crashTail = null,
        )
        assertTrue(text.contains("===== 状态自检 ====="))
        assertTrue(text.contains("===== 设备与版本信息 ====="))
        assertTrue(text.contains("===== 诊断日志（最近部分）====="))
        assertTrue(text.contains("REDMI K90"))
        assertTrue(text.contains("已是最新"))
        assertTrue("未同意隐私政策应标出需要处理", text.contains("PRIVACY: declined  ← 需要处理"))
        assertFalse("没有崩溃日志就不该出现崩溃段", text.contains("崩溃日志（crash.log）"))

        val withCrash = FeedbackReportRules.buildReportText(
            status = status(),
            deviceInfo = emptyList(),
            logTail = null,
            crashTail = "java.lang.NullPointerException",
        )
        assertTrue(withCrash.contains("===== 崩溃日志（crash.log）====="))
        assertTrue(withCrash.contains("NullPointerException"))
        assertTrue("日志为空时给占位", withCrash.contains("（空）"))
    }

    @Test
    fun `文件名与 Issue 链接：带时间戳 / 预填版本与状态`() {
        assertEquals("BDT-反馈包-20261002-235340.txt", FeedbackReportRules.reportFileName("20261002-235340"))
        val url = FeedbackReportRules.issueUrl("3.3.5", status(privacy = false))
        assertTrue(url.startsWith("https://github.com/whyD9527/BDT/issues/new?body="))
        assertTrue(url.contains("3.3.5"))
        assertTrue(url.contains("PRIVACY"))
        assertFalse("URL 里不应出现未编码的空格", url.substringAfter("body=").contains(" "))
    }
}
