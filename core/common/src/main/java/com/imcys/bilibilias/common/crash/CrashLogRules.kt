package com.imcys.bilibilias.common.crash

/**
 * 崩溃日志的**纯规则**：怎么排版、要不要轮转、怎么按字节截断。
 *
 * ## 为什么要有它
 * 2026-10-02 之前的 `AppCrashHandler` 只做两件事：把 `error.toString()`（**一行摘要**）塞进
 * 崩溃页、然后杀进程 —— 既没落文件、也没有堆栈。而这台 ROM 上（小米/Android 16）
 * **MIUI 会过滤 app 自己的 logcat**（见交接文档 §14.2 第 4 条），于是"崩溃到底崩在哪"
 * 完全拿不到证据。重开它的时候顺手把"写什么、写多大、写在哪"抽成纯规则，配单测 —
 * 这类"格式化 + 截断"的字符串边界逻辑正是最容易写错、又最容易靠单测钉住的地方。
 *
 * 文件落在 `Android/data/<pkg>/files/logs/crash.log`（与 `download-trace.log` 同目录），
 * 由 `FileOutputManager` 的「诊断日志」一起读/导出。
 */
object CrashLogRules {

    /** 与 `download-trace.log` 同一个目录（`files/logs/`） */
    const val LOG_DIR = "logs"

    const val FILE_NAME = "crash.log"

    /** 崩溃日志上限：超了就整体重写（崩溃信息"最新一份"最有价值，不做无限追加）。
     *  用 `Long` 是因为要和 `File.length()`（Long）比较 —— CI 第一次就是这里报
     *  `Initializer type mismatch: expected 'Long', actual 'Int'`（`shouldRotate` 的默认值）。 */
    const val MAX_BYTES = 128L * 1024

    /**
     * 放进 Intent 送给崩溃页的**上限**。
     *
     * ⚠️ 不能把整份报告（可能上百 KB）塞进 Intent：Binder 事务有 ~1MB 的硬上限，
     * 超了会 `TransactionTooLargeException` —— 那会是"为了显示崩溃而崩溃"。所以：
     * **完整报告落文件，崩溃页只拿截断后的一份**。
     */
    const val INTENT_DETAIL_MAX_BYTES = 8 * 1024

    /** 报告头（时间/线程/进程）+ 堆栈 + 结束标记 */
    fun format(
        stamp: String,
        threadName: String,
        processName: String,
        throwableText: String,
    ): String = buildString {
        appendLine("===== BDT 崩溃 $stamp =====")
        appendLine("线程: $threadName")
        appendLine("进程: $processName")
        appendLine(throwableText.trimEnd())
        appendLine("===== 崩溃结束 =====")
    }

    /**
     * 要不要在写入前把旧文件清掉。
     *
     * 空文件（`currentBytes == 0`）永远返回 false —— 从没写过就没必要"轮转"，
     * 直接写即可（也避免把"文件不存在、length() 报 0"误判成需要删）。
     */
    fun shouldRotate(
        currentBytes: Long,
        incomingBytes: Int,
        maxBytes: Long = MAX_BYTES,
    ): Boolean = currentBytes > 0L && currentBytes + incomingBytes > maxBytes

    /**
     * 按 **UTF-8 字节数** 截断（而不是字符数）：上层有字节上限时，按字符截断在中文/emoji 上会超标。
     *
     * 按 **code point** 切，绝不切出半个多字节字符或半个代理对（emoji 不会被拦腰截断）；
     * 截断后追加一个"已截断"标记，避免读者以为堆栈就这么短。
     *
     * @param maxBytes 小于标记本身长度时，只返回标记（不抛异常 —— 崩溃处理里不能抛）。
     */
    fun truncateUtf8(text: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        val marker = "\n…（报告过长，已截断；完整内容见 logs/crash.log）"
        val budget = maxBytes - marker.toByteArray(Charsets.UTF_8).size
        if (budget <= 0) return marker

        val builder = StringBuilder()
        var used = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val piece = String(Character.toChars(codePoint))
            val size = piece.toByteArray(Charsets.UTF_8).size
            if (used + size > budget) break
            builder.append(piece)
            used += size
            index += Character.charCount(codePoint)
        }
        return builder.toString() + marker
    }
}
