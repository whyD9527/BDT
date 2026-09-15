package com.imcys.bilibilias.data.download.execution

/**
 * 「这次下载到底算不算成功」的纯规则（可单测）。
 *
 * ## 为什么要有它（2026-09-14 全量审计 H1/H2）
 * `DownloadExecutor` 有两条写文件的路：多线程分片、回落单连接。分片那条**核对过**实收字节：
 * ```kotlin
 * val actualLength = tempFile.length()
 * if (actualLength >= totalLength) { …Success }      // SegmentedDownloader
 * ```
 * 而单连接那条**从来没有核对过**：
 * ```kotlin
 * while (!channel.exhausted()) { … write … }   // 只在 EOF 退出，不看收了多少
 * onProgress(1f)
 * return true                                  // ← 无条件成功
 * ```
 * `downloadedBytes` 唯一的用途是算进度。于是 CDN 在发完 `Content-Length` 之前断流时，
 * **半截文件会被当成成品交付**（状态 COMPLETED），而合并侧只查"返回码 + 输出非空"，
 * 根本兜不住。这正是本项目最怕的"静默产出坏文件"。
 *
 * 同一条路径上还有第二个洞：成功之后要 `file.delete()` + `tempFile.renameTo(file)`，
 * 而**单连接分支丢弃了 `renameTo` 的返回值**（分片分支偏偏是有校验的）——
 * 改名失败时目标文件不存在、旧成品又被删了，函数却返回 true。
 *
 * 这两件事都只依赖「期望长度 / 实收长度 / 改名结果」，所以抽成纯规则钉住。
 */
object DownloadCompletionRules {

    /** 一次下载收尾时的判定结果 */
    enum class Outcome {
        /** 字节数够（或远端没给长度）且文件已就位 */
        SUCCESS,

        /** 实收字节数少于远端声明的长度 —— 服务端提前断流，**不能**交付 */
        INCOMPLETE,

        /** 文件已写完但改名/落地失败 —— 目标路径上没有成品 */
        RENAME_FAILED,
    }

    /**
     * 单连接下载写完之后的判定。
     *
     * @param expectedLength 远端声明的总长（HEAD 的 `Content-Length`，或 `Content-Range` 算出来的）；
     *   `<= 0` 表示**长度未知**（分块传输、探测失败），这种情况下**不**做字节数判定 ——
     *   没有期望值就无法判断多少算够，硬判会把正常下载误杀。
     * @param receivedLength 本次真正写进临时文件的字节数（含续传时本来就有的那部分）
     * @param renamed 临时文件是否成功改名到目标路径
     */
    fun judge(
        expectedLength: Long,
        receivedLength: Long,
        renamed: Boolean,
    ): Outcome = when {
        !renamed -> Outcome.RENAME_FAILED
        expectedLength > 0 && receivedLength < expectedLength -> Outcome.INCOMPLETE
        else -> Outcome.SUCCESS
    }

    /**
     * 长度未知时是否允许"乐观交付"。
     *
     * 分块传输（`Transfer-Encoding: chunked`）没有 `Content-Length`，此时唯一能得到的信号是
     * "流正常结束"。我们**接受**它（老行为、绝大多数情况没问题），但把这条判断显式写出来，
     * 免得以后有人看到 `expectedLength <= 0` 就顺手把它也当失败。
     */
    fun lengthIsUnknown(expectedLength: Long): Boolean = expectedLength <= 0
}
