package com.imcys.bilibilias.network.utils

import com.imcys.bilibilias.network.model.BILILoginUserInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * wbi mixin key 的派生过程 —— 回归测试。
 *
 * **为什么必须有这个文件**：2026-09（v3.1.7）我重写 [WebiTokenUtils.setKey] 时漏了
 * `.take(32)`。wbi 的置换表有 64 项，但官方算法**只取前 32 项**；漏掉之后 mixin key
 * 变成 64 字符，**所有 wbi 签名失效**，B 站按风控处理（-352 风控校验失败），
 * 用户页直接报错。
 *
 * 当时三道关卡全都没拦住：编译通过、CI 通过、diff 也逐行核对过 —— 因为它类型、语法、
 * 构建全对，只错在"派生过程"这一步。这个文件就是补那个缺口：
 * **纯 JVM 测试，本地 `sh gradlew :core:network:testDebugUnitTest` 几秒就能跑，不需要设备。**
 */
class WebiTokenUtilsTest {

    private fun wbi(img: String, sub: String) = BILILoginUserInfo.WbiImg(
        imgUrl = "https://i0.hdslb.com/bfs/wbi/$img.png",
        subUrl = "https://i0.hdslb.com/bfs/wbi/$sub.png",
    )

    /**
     * 置换表有 64 项，但只取前 32 项。
     *
     * 这条断言就是那次事故的护栏：漏掉 `.take(32)` 时长度会变成 64，
     * 且值也会不同，两个断言都会失败。
     */
    @Test
    fun `mixin key 只取置换表的前 32 项`() {
        assertTrue("长度为 64 的拼接应当能被接受", WebiTokenUtils.setKey(wbi(IMG_KEY, SUB_KEY)))

        val key = WebiTokenUtils.key
        assertEquals("mixin key 长度必须是 32（漏掉 take(32) 会让它变成 64）", 32, key!!.length)
        assertEquals("mixin key 的值变了 = wbi 签名会失效", EXPECTED_MIXIN_KEY, key)
    }

    /**
     * 拼接长度恰好 63（比置换表最大下标 63 大 0）时不能越界崩溃。
     *
     * 原实现用 `length >= maxIndex(63)` 判断，长度 63 时 `mixKey[63]` 会抛
     * `IndexOutOfBoundsException`；正确要求是 **长度 > 63**。
     */
    @Test
    fun `混入长度不足时返回 false 且不崩溃、不清掉已有 key`() {
        WebiTokenUtils.setKey(wbi(IMG_KEY, SUB_KEY))
        val before = WebiTokenUtils.key

        // 32 + 31 = 63，恰好差一位
        val tooShort = SUB_KEY.substring(0, 31)
        assertFalse("63 位拼接必须被拒绝", WebiTokenUtils.setKey(wbi(IMG_KEY, tooShort)))

        // 失败不能把已经可用的 key 清成 null —— 否则后续签名会直接抛异常
        assertEquals("派生失败时应保留原 key", before, WebiTokenUtils.key)
    }

    private companion object {
        // 固定输入 → 固定输出。改派生逻辑就必须同步改这里的期望值，
        // 而这正是"改动是故意的"这一确认动作。
        const val IMG_KEY = "0123456789abcdefghijklmnopqrstuv"
        const val SUB_KEY = "abcdefghijklmnopqrstuvwxyz012345"
        const val EXPECTED_MIXIN_KEY = "opi2v8nafsav03ndrl5rb9kjtsehcgjd"
    }
}
