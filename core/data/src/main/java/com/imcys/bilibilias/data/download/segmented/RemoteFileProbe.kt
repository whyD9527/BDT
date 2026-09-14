package com.imcys.bilibilias.data.download.segmented

import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HEAD 探测到的远端文件信息。
 *
 * @param length 远端文件长度；未知为 -1（此时不能分片）
 * @param acceptRanges `Accept-Ranges` 的解析结果：
 *        - `true`  → 明确声明支持（`bytes`）
 *        - `false` → 明确声明不支持（`none`）
 *        - `null`  → 没有这个头，或取值看不懂 → **当作"未声明"**，
 *          由 [SegmentedDownloadPlan.shouldSegment] 决定允许尝试
 *          （很多 CDN 确实不在 HEAD 里声明它，而现有断点续传一直在用 Range）
 */
data class RemoteFileInfo(
    val length: Long,
    val acceptRanges: Boolean?,
)

/**
 * 用 HEAD 探测远端长度与 Range 支持情况。
 *
 * 抽出来单独放库模块的原因：`Accept-Ranges` 一旦解析错，就会**静默改变分片开关**
 * （该分片时分不了、或在不支持 Range 的服务器上硬分片导致整段失败），
 * 而这类错误编译期看不出来 —— 用 MockEngine 才能真跑一遍。
 */
class RemoteFileProbe(
    private val httpClient: HttpClient,
) {

    suspend fun probe(url: String, referer: String): RemoteFileInfo = withContext(Dispatchers.IO) {
        try {
            val response = httpClient.head(url) {
                header("Referer", referer)
            }
            // 只有成功响应才相信 Content-Length。
            // 改造前的实现不看状态码：HEAD 拿到 403/404 时，响应体会带一个「错误页的长度」，
            // 那个值会被当成远端文件大小 —— 一旦它小于本地残留的临时文件，
            // "文件已完整"的判断就会成立，于是把半截文件当成品重命名过去（静默损坏）。
            if (!response.status.isSuccess()) {
                return@withContext RemoteFileInfo(length = -1L, acceptRanges = null)
            }
            RemoteFileInfo(
                length = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L,
                acceptRanges = parseAcceptRanges(response.headers[HttpHeaders.AcceptRanges]),
            )
        } catch (e: CancellationException) {
            // 取消要原样抛出，不能被"探测失败"吞掉（否则暂停/取消会继续往下走）
            throw e
        } catch (e: Exception) {
            // 探测失败一律当作"长度未知"，与改造前的行为一致：不影响单连接下载
            RemoteFileInfo(length = -1L, acceptRanges = null)
        }
    }

    companion object {
        /**
         * 只认 `bytes` / `none`（大小写不敏感、允许前后空白）。
         * 其他取值（含 `bytes `、`Bytes`之外的怪值）当作"未声明" → null。
         */
        fun parseAcceptRanges(header: String?): Boolean? =
            when (header?.trim()?.lowercase()) {
                "bytes" -> true
                "none" -> false
                else -> null
            }
    }
}
