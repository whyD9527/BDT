package com.imcys.bilibilias.data.download.segmented

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `Content-Range` 解析测试。
 *
 * 它守的是"**别把响应体写到错误的偏移上**"：解析结果一旦错，要么把正常响应当成异常
 * （退回单连接，只是慢），要么**把错位的数据当对的写进用户文件**（不可逆）。
 * 所以取值集合要钉死，宁可解析不出来（返回 null）也不要猜。
 */
class ContentRangeTest {

    @Test
    fun `解析标准写法`() {
        val range = ContentRange.parse("bytes 0-499/1234")
        assertEquals(0L, range?.start)
        assertEquals(499L, range?.endInclusive)
        assertEquals(1234L, range?.totalLength)
        assertEquals(500L, range?.length)
    }

    @Test
    fun `单字节段也是闭区间`() {
        val range = ContentRange.parse("bytes 1023-1023/1024")
        assertEquals(1023L, range?.start)
        assertEquals(1023L, range?.endInclusive)
        assertEquals(1L, range?.length)
    }

    @Test
    fun `总长未知时 totalLength 为 null`() {
        val range = ContentRange.parse("bytes 0-99/*")
        assertEquals(0L, range?.start)
        assertEquals(99L, range?.endInclusive)
        assertNull(range?.totalLength)
    }

    @Test
    fun `大小写与多余空白都能容忍`() {
        assertEquals(0L, ContentRange.parse("  BYTES 0-99/100  ")?.start)
        assertEquals(0L, ContentRange.parse("bytes  0 - 99 / 100")?.start)
        assertEquals(99L, ContentRange.parse("bytes  0 - 99 / 100")?.endInclusive)
    }

    @Test
    fun `解析不出来一律返回 null，绝不猜`() {
        assertNull(ContentRange.parse(null))
        assertNull(ContentRange.parse(""))
        assertNull(ContentRange.parse("0-99/100"))          // 少了 bytes
        assertNull(ContentRange.parse("bytes 0-99"))        // 少了 /总长
        assertNull(ContentRange.parse("items 0-99/100"))    // 单位不对
        assertNull(ContentRange.parse("bytes a-b/c"))
        assertNull(ContentRange.parse("bytes 99-0/100"))    // 空区间/倒序
        assertNull(ContentRange.parse("bytes 0-99/-1"))
    }

    @Test
    fun `超大偏移不会溢出`() {
        val range = ContentRange.parse("bytes 9223372036854775800-9223372036854775806/9223372036854775807")
        assertEquals(9223372036854775800L, range?.start)
        assertEquals(7L, range?.length)
    }
}
