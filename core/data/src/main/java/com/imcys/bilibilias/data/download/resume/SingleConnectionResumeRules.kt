package com.imcys.bilibilias.data.download.resume

/**
 * 单连接（非分片）续传时，「这次响应到底能不能接着写」的纯规则。
 *
 * ## 原来错在哪（第十八轮审查里最后一条中危）
 * `DownloadExecutor.performDownload` 只要临时文件里已经有内容，就会发
 * `Range: bytes=<已下载>-`，并且**无条件以追加模式打开文件**
 * （`FileOutputStream(tempFile, append = true)`）。
 * 但服务端完全可能**忽略 Range、直接回 200 + 整份文件**：这时"追加"就把整份内容
 * 接到了半截文件的后面 —— 产出**长度翻倍、内容错位**的坏文件，而且照样报"下载成功"。
 * 分片路径早就防了这条（"只在 206 上写"），单连接这条一直没防。
 *
 * 三个判据（与分片路径同源）：
 * 1. 没发 Range ⇒ 本来就该从头写；
 * 2. **要了 Range 却回 200** ⇒ 服务端把整份发来了，绝不能追加；
 * 3. 回了 206 但 `Content-Range` 的起点不是我要的那一段 ⇒ 偏移不对，同样从头写
 *    （起点缺失时沿用分片路径的做法：相信 206）。
 */
object SingleConnectionResumeRules {

    enum class WriteMode {
        /** 接着已有内容往后写（服务端确实按 Range 回了我请求的那一段） */
        APPEND_TO_EXISTING,

        /** 从头写（截断重来）—— 包括"服务端忽略了 Range"这种情况 */
        WRITE_FROM_START,
    }

    /**
     * @param requestedRangeFrom 这次请求的 Range 起点；**0 表示没发 Range**
     * @param statusIsPartialContent 响应是不是 206
     * @param contentRangeStart 206 的 `Content-Range` 起点；null = 没给或解析不出来
     */
    fun writeMode(
        requestedRangeFrom: Long,
        statusIsPartialContent: Boolean,
        contentRangeStart: Long?,
    ): WriteMode {
        if (requestedRangeFrom <= 0L) return WriteMode.WRITE_FROM_START
        if (!statusIsPartialContent) return WriteMode.WRITE_FROM_START
        if (contentRangeStart != null && contentRangeStart != requestedRangeFrom) {
            return WriteMode.WRITE_FROM_START
        }
        return WriteMode.APPEND_TO_EXISTING
    }
}
