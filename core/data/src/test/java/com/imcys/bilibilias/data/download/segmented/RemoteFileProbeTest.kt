package com.imcys.bilibilias.data.download.segmented

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * HEAD 探测测试。
 *
 * `Accept-Ranges` 一旦解析错，会**静默改变分片开关**：该分片的分不了（只是慢），
 * 或者在不支持 Range 的服务端上硬分片（整段失败，比改造前更差）。所以取值集合要钉死。
 */
class RemoteFileProbeTest {

    private val url = "https://upos-sz-mirrorzos.bilivideo.com/x.m4s"
    private val referer = "https://www.bilibili.com/video/BV1xx411c7mD"

    @Test
    fun `Accept-Ranges 只认 bytes 与 none`() {
        assertEquals(true, RemoteFileProbe.parseAcceptRanges("bytes"))
        assertEquals(true, RemoteFileProbe.parseAcceptRanges("BYTES"))
        assertEquals(true, RemoteFileProbe.parseAcceptRanges("  bytes  "))
        assertEquals(false, RemoteFileProbe.parseAcceptRanges("none"))
        assertEquals(false, RemoteFileProbe.parseAcceptRanges("NONE"))

        // 其他取值一律当作"未声明"（null）→ 交给 shouldSegment 决定，而不是当成"不支持"
        assertNull(RemoteFileProbe.parseAcceptRanges(null))
        assertNull(RemoteFileProbe.parseAcceptRanges(""))
        assertNull(RemoteFileProbe.parseAcceptRanges("bananas"))
        assertNull(RemoteFileProbe.parseAcceptRanges("bytes, none"))
    }

    @Test
    fun `HEAD 能读到长度与 Accept-Ranges`() = runBlocking {
        val server = FakeRangeServer(FakeRangeServer.payload(1234))

        val info = RemoteFileProbe(HttpClient(server.engine)).probe(url, referer)

        assertEquals(1234L, info.length)
        assertEquals(true, info.acceptRanges)
    }

    @Test
    fun `HEAD 未声明 Accept-Ranges 时为 null`() = runBlocking {
        val server = FakeRangeServer(FakeRangeServer.payload(1234), acceptRangesHeader = null)

        val info = RemoteFileProbe(HttpClient(server.engine)).probe(url, referer)

        assertEquals(1234L, info.length)
        assertNull("没这个头不能当成不支持 Range", info.acceptRanges)
    }

    @Test
    fun `HEAD 失败时长度为未知且不抛异常`() = runBlocking {
        val client = HttpClient(MockEngine { throw IOException("network down") })

        val info = RemoteFileProbe(client).probe(url, referer)

        assertEquals("探测失败必须与改造前一致：长度未知，不影响单连接下载", -1L, info.length)
        assertNull(info.acceptRanges)
    }

    @Test
    fun `HEAD 返回错误状态码时长度为未知`() = runBlocking {
        // 关键：403 的响应体也有 Content-Length。若把它当远端文件长度，
        // "本地文件已完整"的判断就可能被一个错误页的长度满足 → 半截文件被当成成品。
        val client = HttpClient(
            MockEngine {
                respond(
                    content = "Forbidden: access denied by CDN",
                    status = HttpStatusCode.Forbidden,
                )
            }
        )

        val info = RemoteFileProbe(client).probe(url, referer)

        assertEquals("错误响应绝不能被当成文件长度", -1L, info.length)
        assertNull(info.acceptRanges)
    }
}
