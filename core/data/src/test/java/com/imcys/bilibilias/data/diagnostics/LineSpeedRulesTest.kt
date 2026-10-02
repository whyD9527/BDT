package com.imcys.bilibilias.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineSpeedRulesTest {

    @Test
    fun `速度串解析：支持 KB MB GB 与小写、逗号小数点`() {
        assertEquals(1024.0 * 1024 * 3.2, LineSpeedRules.parseBytesPerSecond("3.2 MB/s")!!, 1.0)
        assertEquals(800.0 * 1024, LineSpeedRules.parseBytesPerSecond("800 KB/s")!!, 1.0)
        assertEquals(2.0 * 1024 * 1024 * 1024, LineSpeedRules.parseBytesPerSecond("2 GB/s")!!, 1.0)
        assertEquals(1.5 * 1024 * 1024, LineSpeedRules.parseBytesPerSecond("1,5 MB/s")!!, 1.0)
        assertEquals(1.0 * 1024 * 1024, LineSpeedRules.parseBytesPerSecond("1 Mb/s")!!, 1.0)
        assertNull(LineSpeedRules.parseBytesPerSecond(null))
        assertNull(LineSpeedRules.parseBytesPerSecond(""))
        assertNull(LineSpeedRules.parseBytesPerSecond("检测"))
    }

    @Test
    fun `挑最快：忽略未测速的，取最大；全没测则 null`() {
        val entries = listOf(
            "upos-sz-mirror01.bilivideo.com" to null,
            "upos-sz-mirrorali.bilivideo.com" to "3.2 MB/s",
            "upos-sz-mirrorcos.bilivideo.com" to "800 KB/s",
            "" to "1.0 MB/s",
        )
        assertEquals("upos-sz-mirrorali.bilivideo.com", LineSpeedRules.fastestHost(entries))
        assertNull(LineSpeedRules.fastestHost(listOf("a" to null, "b" to "检测")))
        assertNull(LineSpeedRules.fastestHost(emptyList()))
    }
}
