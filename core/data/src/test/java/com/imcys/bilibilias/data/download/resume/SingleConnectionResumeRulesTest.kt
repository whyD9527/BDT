package com.imcys.bilibilias.data.download.resume

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 单连接续传写模式的测试。
 *
 * 守的是第十八轮清单里最后一条中危：**要了 Range 却收到 200 时按追加写** ——
 * 会把整份文件接到半截文件后面，产出长度翻倍、内容错位的坏文件，而且报"下载成功"。
 */
class SingleConnectionResumeRulesTest {

    private fun mode(
        requestedFrom: Long,
        partial: Boolean,
        contentRangeStart: Long? = null,
    ) = SingleConnectionResumeRules.writeMode(requestedFrom, partial, contentRangeStart)

    @Test
    fun `没发 Range 就是从头写`() {
        assertEquals(
            SingleConnectionResumeRules.WriteMode.WRITE_FROM_START,
            mode(requestedFrom = 0, partial = false),
        )
        // 空文件、服务端却回了 206：也按"从头写"处理（本来就没有可以接着写的内容）
        assertEquals(
            SingleConnectionResumeRules.WriteMode.WRITE_FROM_START,
            mode(requestedFrom = 0, partial = true, contentRangeStart = 0),
        )
    }

    @Test
    fun `要了 Range 却回 200：绝不能追加（这条就是那个 bug）`() {
        assertEquals(
            "服务端忽略了 Range、把整份发来，追加就会写出长度翻倍的坏文件",
            SingleConnectionResumeRules.WriteMode.WRITE_FROM_START,
            mode(requestedFrom = 1024, partial = false),
        )
    }

    @Test
    fun `206 且起点对得上才允许接着写`() {
        assertEquals(
            SingleConnectionResumeRules.WriteMode.APPEND_TO_EXISTING,
            mode(requestedFrom = 1024, partial = true, contentRangeStart = 1024),
        )
        // 206 但没给 Content-Range：与分片路径一致，相信这个 206
        assertEquals(
            SingleConnectionResumeRules.WriteMode.APPEND_TO_EXISTING,
            mode(requestedFrom = 1024, partial = true, contentRangeStart = null),
        )
    }

    @Test
    fun `206 但起点不是我请求的那一段：从头写`() {
        assertEquals(
            SingleConnectionResumeRules.WriteMode.WRITE_FROM_START,
            mode(requestedFrom = 1024, partial = true, contentRangeStart = 0),
        )
        assertEquals(
            SingleConnectionResumeRules.WriteMode.WRITE_FROM_START,
            mode(requestedFrom = 1024, partial = true, contentRangeStart = 2048),
        )
    }
}
