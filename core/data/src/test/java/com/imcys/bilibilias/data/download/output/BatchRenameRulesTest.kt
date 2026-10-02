package com.imcys.bilibilias.data.download.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量重命名后缀分配的测试。
 *
 * 守的是这台 ROM 上"撞名会被 MediaProvider 自动改成 `(N)`"带来的问题：
 * 多个文件改成同一个名字时，**后缀必须由我们自己按顺序分配**，不能交给 MediaStore
 * （否则序号与用户选中的项对不上，见交接文档 §14.2）。
 */
class BatchRenameRulesTest {

    @Test
    fun `只选一个时用原名，不加后缀`() {
        assertEquals(listOf("新名字"), BatchRenameRules.assignBaseNames("新名字", 1))
    }

    @Test
    fun `多选时第一项不加后缀，其余从 1 开始递增`() {
        assertEquals(
            listOf("第1话", "第1话 (1)", "第1话 (2)", "第1话 (3)"),
            BatchRenameRules.assignBaseNames("第1话", 4),
        )
    }

    @Test
    fun `分配出来的名字两两不同`() {
        val names = BatchRenameRules.assignBaseNames("同一个名字", 6) ?: error("不该为 null")
        assertEquals(names.size, names.toSet().size)
        // 名字里不能出现 `/`（MediaStore 会拒），否则"批量重命名"整批失败
        assertTrue(names.none { it.contains('/') })
    }

    @Test
    fun `斜杠和反斜杠被清成下划线`() {
        assertEquals(listOf("a_b_c"), BatchRenameRules.assignBaseNames("a/b\\c", 1))
        assertEquals("a_b", BatchRenameRules.sanitizeBaseName(" a/b "))
    }

    @Test
    fun `控制字符被去掉，首尾空白与结尾的点被清掉`() {
        assertEquals("abc", BatchRenameRules.sanitizeBaseName("\u0001a\nb\tc"))
        assertEquals("名字", BatchRenameRules.sanitizeBaseName("  名字. "))
    }

    @Test
    fun `名字不可用时不返回任何计划（调用方必须 no-op）`() {
        assertNull(BatchRenameRules.assignBaseNames("", 3))
        assertNull(BatchRenameRules.assignBaseNames("   ", 3))
        assertNull(BatchRenameRules.assignBaseNames("/", 3))
        // `..` 是路径穿越的味道，必须拒绝（移动那边也拒绝了 `..`）
        assertNull(BatchRenameRules.assignBaseNames("..", 3))
        assertNull(BatchRenameRules.assignBaseNames("a..b", 2))
        // 一个都没选时同样什么都不做
        assertNull(BatchRenameRules.assignBaseNames("新名字", 0))
        assertNull(BatchRenameRules.assignBaseNames("新名字", -1))
    }

    @Test
    fun `isUsableBaseName 与 assignBaseNames 的判据一致`() {
        assertTrue(BatchRenameRules.isUsableBaseName("新名字"))
        assertTrue(BatchRenameRules.isUsableBaseName(" a/b "))
        assertFalse(BatchRenameRules.isUsableBaseName(""))
        assertFalse(BatchRenameRules.isUsableBaseName(".."))
        assertFalse(BatchRenameRules.isUsableBaseName("/"))
    }

    @Test
    fun `超长名字的截断由交付层负责，这里只保证不丢后缀`() {
        // 这里不截断（长度限制要看扩展名，交付层用 FileNameLengthRules 统一处理），
        // 但要钉住"名字再长也不许把 ` (1)` 后缀弄丢"这件事由调用方按序分配保证。
        val names = BatchRenameRules.assignBaseNames("长".repeat(200), 2) ?: error("不该为 null")
        assertEquals(2, names.size)
        assertTrue(names[1].endsWith(" (1)"))
    }
}
