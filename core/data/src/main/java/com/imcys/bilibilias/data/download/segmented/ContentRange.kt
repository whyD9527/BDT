package com.imcys.bilibilias.data.download.segmented

/**
 * HTTP `Content-Range` 响应头的**纯解析**。
 *
 * 为什么这个必须单独测：分片下载是**按绝对偏移写文件**的，只要响应体不是我们所请求的那一段，
 * 写下去就是**静默写坏文件**（长度看着对、内容错位）。所以发 `Range: bytes=X-Y` 之后，
 * 必须核对服务端回的 `Content-Range` 起点是不是 X —— 这一步错了会直接破坏用户文件，
 * 而且**编译、CI、diff 都发现不了**（同 wbi 那次事故的性质）。
 *
 * 格式（RFC 7233）：`Content-Range: bytes 0-499/1234`，总长也可能是 `*`。
 * 解析不出来一律返回 null（调用方据此决定"要不要保守处理"）。
 */
data class ContentRange(
    val start: Long,
    val endInclusive: Long,
    /** `/` 后面的总长度；`*` 或缺失时为 null（表示总长未知） */
    val totalLength: Long?,
) {
    /** 本段字节数；闭区间所以 +1 */
    val length: Long get() = endInclusive - start + 1

    companion object {
        private val PATTERN =
            Regex("""^\s*bytes\s+(\d+)\s*-\s*(\d+)\s*/\s*(\d+|\*)\s*$""", RegexOption.IGNORE_CASE)

        fun parse(header: String?): ContentRange? {
            if (header == null) return null
            val match = PATTERN.matchEntire(header) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val endInclusive = match.groupValues[2].toLongOrNull() ?: return null
            // 空区间（end < start）不是合法的一段，宁可当作解析失败
            if (endInclusive < start) return null
            val totalText = match.groupValues[3]
            val totalLength =
                if (totalText == "*") null else totalText.toLongOrNull() ?: return null
            return ContentRange(start, endInclusive, totalLength)
        }
    }
}
