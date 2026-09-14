package com.imcys.bilibilias.network.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * App 端签名（`appkey` + `sign`）的回归测试。
 *
 * 覆盖两件事：
 * 1. **签名值固定在黄金向量上** —— 排序、URL 编码、`md5(query + APP_SEC)` 任何一步被改动都会失败；
 * 2. **不修改调用方传入的 map** —— 原实现会往入参里 `put("appkey")`，属于隐式副作用。
 */
class BiliAppSignerTest {

    @Test
    fun `签名值符合黄金向量`() {
        // query = "a=1&appkey=4409e2ce8ffd12b8&b=2"（TreeMap 排序）+ APP_SEC → md5
        assertEquals(
            "e68cafce80b6b3ac792dfc2438aa3319",
            BiliAppSigner.appSign(mapOf("a" to "1", "b" to "2")),
        )
    }

    @Test
    fun `不修改调用方传入的 map`() {
        val params = mutableMapOf("a" to "1")

        BiliAppSigner.appSign(params)

        assertEquals("入参不该被塞进 appkey", mapOf("a" to "1"), params)
        assertFalse(params.containsKey("appkey"))
    }

    @Test
    fun `同样的参数永远得到同样的签名`() {
        val first = BiliAppSigner.appSign(mapOf("mid" to "123", "ts" to "456"))
        val second = BiliAppSigner.appSign(mapOf("ts" to "456", "mid" to "123"))
        assertEquals("签名只与参数内容有关，与传入顺序无关", first, second)
    }
}
