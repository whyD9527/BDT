package com.imcys.bilibilias.data.download.segmented

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Headers
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 内存假服务器：**像真实 CDN 一样支持 Range**（206 + `Content-Range`），
 * 同时可以**故意做出各种不合规行为** —— 用来证明客户端遇到这些情况时
 * 宁可放弃分片，也绝不写出错位的文件。
 *
 * 为什么不用真 CDN 测：分片写偏移这类错误只有在"服务端行为异常"时才暴露，
 * 而真实 CDN 大部分时候是正常的；更关键的是，B 站 playurl 对本机 IP 一律 -412，
 * 连媒体地址都拿不到（交接文档第十二轮已取证）。所以把"异常行为"做成可编排的假服务器。
 */
class FakeRangeServer(
    val payload: ByteArray,
    /** true = 忽略 Range，一律回 200 整个文件（服务端不支持分片时的真实表现） */
    var ignoreRange: Boolean = false,
    /** HEAD 里 `Accept-Ranges` 的取值；null = 不返回这个头 */
    var acceptRangesHeader: String? = "bytes",
    /** 每个 206 响应**少发**这么多字节（模拟 body 被截断） */
    var truncateEachRangeBy: Int = 0,
    /**
     * 每个 206 响应**多发**这么多字节（内容故意全为 0xFF）。
     * 真实服务端忽略 Range 的**结尾**时就是这样 —— 客户端若照单全收，
     * 就会把属于下一片的内容写进下一片的地盘。
     */
    var overshootRangeEndBy: Int = 0,
    /** 206 的 `Content-Range` 起点故意写成这个值（模拟服务端回错段） */
    var contentRangeStartOverride: Long? = null,
    /** 非空时：GET 必须等够这么多请求同时到达才响应（用于证明"真的并发"） */
    var rendezvous: java.util.concurrent.CyclicBarrier? = null,
) {
    /** 每次 GET 带来的 Range 头，按到达顺序记录 */
    val rangeHeaders = CopyOnWriteArrayList<String>()

    val engine = MockEngine { request ->
        if (request.method == HttpMethod.Head) {
            respond(
                content = payload,
                status = HttpStatusCode.OK,
                headers = buildHeadHeaders(),
            )
        } else {
            rendezvous?.await(5, java.util.concurrent.TimeUnit.SECONDS)

            val range = request.headers[HttpHeaders.Range]
            rangeHeaders += range ?: "<无>"

            if (ignoreRange || range == null) {
                respond(payload, HttpStatusCode.OK)
            } else {
                val (start, end) = parseRange(range)
                val realEnd = minOf(end, payload.size.toLong() - 1)
                var slice = payload.copyOfRange(start.toInt(), (realEnd + 1).toInt())
                if (truncateEachRangeBy > 0) {
                    slice = slice.copyOf((slice.size - truncateEachRangeBy).coerceAtLeast(0))
                }
                if (overshootRangeEndBy > 0) {
                    slice = slice + ByteArray(overshootRangeEndBy) { 0xFF.toByte() }
                }
                val reportedStart = contentRangeStartOverride ?: start
                val reportedEnd = reportedStart + slice.size - 1
                respond(
                    content = slice,
                    status = HttpStatusCode.PartialContent,
                    headers = headersOf(
                        HttpHeaders.ContentRange to
                            listOf("bytes $reportedStart-$reportedEnd/${payload.size}"),
                        HttpHeaders.ContentLength to listOf(slice.size.toString()),
                    ),
                )
            }
        }
    }

    private fun buildHeadHeaders(): Headers {
        val pairs = buildList<Pair<String, List<String>>> {
            add(HttpHeaders.ContentLength to listOf(payload.size.toString()))
            acceptRangesHeader?.let { add(HttpHeaders.AcceptRanges to listOf(it)) }
        }
        return headersOf(*pairs.toTypedArray())
    }

    /** 把记录到的 Range 头解析成 (start, end) 列表，便于断言"覆盖严丝合缝" */
    fun requestedRanges(): List<Pair<Long, Long>> =
        rangeHeaders.filter { it.startsWith("bytes=") }.map { parseRange(it) }

    companion object {
        fun parseRange(header: String): Pair<Long, Long> {
            val spec = header.removePrefix("bytes=")
            val dash = spec.indexOf('-')
            return spec.substring(0, dash).toLong() to spec.substring(dash + 1).toLong()
        }

        /** 可复现的伪随机内容：分片写错位时，重复字节会掩盖问题，所以要"几乎不可能撞车" */
        fun payload(size: Long, seed: Long = 20260912L): ByteArray {
            val bytes = ByteArray(size.toInt())
            java.util.Random(seed).nextBytes(bytes)
            return bytes
        }
    }
}
