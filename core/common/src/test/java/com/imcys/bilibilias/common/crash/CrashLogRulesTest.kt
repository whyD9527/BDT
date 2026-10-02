package com.imcys.bilibilias.common.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 崩溃日志纯规则的测试。
 *
 * 守两件事：
 * 1. 报告里**必须有**时间/线程/进程/完整堆栈（以前只有一行 `error.toString()`，等于没有信息）；
 * 2. 塞进 Intent 前必须能**按 UTF-8 字节**安全截断（Binder 事务上限 + 中文/emoji 不能被切坏）。
 */
class CrashLogRulesTest {

    private val sampleStack = """
        java.lang.NullPointerException: Attempt to invoke virtual method on a null object
            at com.imcys.bilibilias.ui.download.DownloadViewModel.probeMediaFile(DownloadViewModel.kt:279)
            at com.imcys.bilibilias.download.NewDownloadManager.handleSuccessor(NewDownloadManager.kt:120)
    """.trimIndent()

    @Test
    fun `报告包含时间线程进程与完整堆栈`() {
        val report = CrashLogRules.format(
            stamp = "2026-10-02 16:20:31.123",
            threadName = "DefaultDispatcher-worker-3",
            processName = "com.whyd9527.bilibilias",
            throwableText = sampleStack,
        )
        assertTrue(report.contains("2026-10-02 16:20:31.123"))
        assertTrue(report.contains("DefaultDispatcher-worker-3"))
        assertTrue(report.contains("com.whyd9527.bilibilias"))
        // 堆栈的两行都要在（以前只传 error.toString()，行号会全丢）
        assertTrue(report.contains("DownloadViewModel.kt:279"))
        assertTrue(report.contains("NewDownloadManager.kt:120"))
        assertTrue(report.contains("===== 崩溃结束 ====="))
    }

    @Test
    fun `空文件不轮转，写满才轮转`() {
        // 从没写过（length()=0）→ 直接写，不删
        assertFalse(CrashLogRules.shouldRotate(currentBytes = 0L, incomingBytes = 999_999))
        assertFalse(CrashLogRules.shouldRotate(currentBytes = 1024L, incomingBytes = 1024))
        assertTrue(CrashLogRules.shouldRotate(currentBytes = 1024L, incomingBytes = 1024, maxBytes = 2000))
        // 正好等于上限不算超
        assertFalse(CrashLogRules.shouldRotate(currentBytes = 1000L, incomingBytes = 1000, maxBytes = 2000))
    }

    @Test
    fun `短文本原样返回`() {
        assertEquals("boom", CrashLogRules.truncateUtf8("boom", 100))
        // 正好等于上限也原样返回（边界不能多截一刀）
        assertEquals("12345", CrashLogRules.truncateUtf8("12345", 5))
    }

    @Test
    fun `超长英文按字节截断并带标记`() {
        val text = "x".repeat(5000)
        val out = CrashLogRules.truncateUtf8(text, 1000)
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= 1000)
        assertTrue(out.endsWith("完整内容见 logs/crash.log）"))
        assertTrue(out.startsWith("x"))
    }

    @Test
    fun `中文不会被切出半个字`() {
        val text = "崩溃".repeat(2000) // 6000 汉字 = 18000 字节
        val out = CrashLogRules.truncateUtf8(text, 1000)
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= 1000)
        // 截断点之后没有半个字：把非标记部分再切回去，长度必须是 3 的倍数（汉字 3 字节）
        val body = out.substringBefore("\n…（报告过长")
        assertEquals(0, body.toByteArray(Charsets.UTF_8).size % 3)
    }

    @Test
    fun `emoji 代理对不会被拦腰截断`() {
        val text = "🧨".repeat(500) // 每个 emoji 4 字节（UTF-16 是两个 char）
        val out = CrashLogRules.truncateUtf8(text, 100)
        val body = out.substringBefore("\n…（报告过长")
        assertEquals(0, body.length % 2) // 每个 emoji 两个 char：截断点只能落在配对边界上
        // 没有落单的代理（高代理后面必须紧跟低代理）
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (Character.isHighSurrogate(c)) {
                assertTrue(
                    "高代理后面必须是低代理",
                    i + 1 < body.length && Character.isLowSurrogate(body[i + 1]),
                )
                i += 2
            } else {
                assertFalse("这里不该出现孤立的低代理", Character.isLowSurrogate(c))
                i += 1
            }
        }
    }

    @Test
    fun `上限比标记还小时只返回标记，绝不抛异常`() {
        val out = CrashLogRules.truncateUtf8("anything", 5)
        assertTrue(out.isNotEmpty())
        assertTrue(out.contains("已截断"))
        assertEquals("", CrashLogRules.truncateUtf8("anything", 0))
    }
}
