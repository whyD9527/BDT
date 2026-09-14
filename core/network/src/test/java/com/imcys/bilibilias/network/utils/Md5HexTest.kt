package com.imcys.bilibilias.network.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享 MD5 实现的回归测试。
 *
 * 两个签名器都用它，所以它错了就是**签名整体错**。
 * 特别注意字节掩码：`String.format("%02x", byte)` 在带符号字节上容易出歧义，
 * 这里保证每个字节都输出两位小写十六进制。
 */
class Md5HexTest {

    @Test
    fun `已知向量`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5Hex(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5Hex("abc"))
    }

    @Test
    fun `每个字节都是两位小写十六进制`() {
        // 摘要里必然含有 >= 0x80 的字节，正好覆盖掩码逻辑
        val hex = md5Hex("bilibilias-签名-测试-0x80+")!!
        assertEquals(32, hex.length)
        assertTrue("只允许小写十六进制字符，实际: $hex", hex.all { it in "0123456789abcdef" })
    }
}
