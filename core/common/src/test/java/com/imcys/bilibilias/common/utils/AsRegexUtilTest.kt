package com.imcys.bilibilias.common.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链接识别测试。
 *
 * 这里守两类问题，两类都在真机/实测里出现过：
 *
 * 1. **崩溃**：输入是不可信的剪贴板文本，`av`/`ss`/`ep` + 超长数字会 `toLong()` 溢出。
 *    真机复现过 `am_crash: NumberFormatException: For input string: "1234567890123456789012345"`，
 *    异常一路到 `AppCrashHandler` → 起崩溃页 + 杀进程。所以"不抛异常"本身就是断言。
 * 2. **误判**：`https://example.com/nav123456` 会被当成 `av123456`。误判的代价不只是认错 ——
 *    `ClipboardAutoHandler` 会因此**清空用户剪贴板**、跳到解析页，并展示/下载一个**不相关的视频**。
 */
class AsRegexUtilTest {

    // ---------------------------------------------------------------- 正常路径（改造前就该能认）

    @Test
    fun `正常B站链接都能认出来`() {
        assertEquals(
            TextType.BILI.BV("BV1xx411c7mD"),
            AsRegexUtil.parse("【标题】 https://www.bilibili.com/video/BV1xx411c7mD?share_source=copy_web"),
        )
        assertEquals(TextType.BILI.BV("BV1xx411c7mD"), AsRegexUtil.parse("BV1xx411c7mD"))
        assertEquals(TextType.BILI.BV("BV1xx411c7md"), AsRegexUtil.parse("bv1xx411c7md"))
        assertEquals(TextType.BILI.AV(170001), AsRegexUtil.parse("av170001"))
        assertEquals(TextType.BILI.AV(170001), AsRegexUtil.parse("https://www.bilibili.com/video/av170001"))
        assertEquals(
            TextType.BILI.EP(123456),
            AsRegexUtil.parse("https://www.bilibili.com/bangumi/play/ep123456"),
        )
        assertEquals(TextType.BILI.EP(123456), AsRegexUtil.parse("ep123456"))
        assertEquals(
            TextType.BILI.SS(46089),
            AsRegexUtil.parse("https://www.bilibili.com/bangumi/play/ss46089"),
        )
        assertEquals(TextType.BILI.SS(46089), AsRegexUtil.parse("ss46089"))
        assertEquals(
            TextType.BILI.ShortLink("https://b23.tv/AbC123"),
            AsRegexUtil.parse("【标题-哔哩哔哩】 https://b23.tv/AbC123"),
        )
        assertEquals(
            TextType.BILI.UserSpace("3546720772294901"),
            AsRegexUtil.parse("https://space.bilibili.com/3546720772294901"),
        )
    }

    @Test
    fun `大小写与短链协议都能容忍`() {
        assertEquals(TextType.BILI.SS(46089), AsRegexUtil.parse("SS46089"))
        assertEquals(
            TextType.BILI.ShortLink("https://b23.tv/AbC123"),
            AsRegexUtil.parse("http://b23.tv/AbC123"),
        )
        assertEquals(
            TextType.BILI.ShortLink("https://b23.tv/AbC123"),
            AsRegexUtil.parse("https://b23.tv/AbC123?share_source=copy_web"),
        )
        assertEquals(
            TextType.BILI.ShortLink("https://bili2233.cn/AbC123"),
            AsRegexUtil.parse("https://bili2233.cn/AbC123"),
        )
    }

    @Test
    fun `文案里夹BV号但没带链接也能认`() {
        // 用户可能只复制了一段带 BV 号的文案
        assertEquals(TextType.BILI.BV("BV1xx411c7mD"), AsRegexUtil.parse("看看这个 BV1xx411c7mD 很好看"))
    }

    // ---------------------------------------------------------------- 崩溃回归

    @Test
    fun `超长数字不抛异常（崩溃回归）`() {
        // 这几条在改造前会抛 NumberFormatException → 真机上直接崩进程
        assertNull("av + 25 位数字必须安全返回 null，而不是抛异常",
            AsRegexUtil.parse("av1234567890123456789012345"))
        assertNull(AsRegexUtil.parse("ss1234567890123456789012345"))
        assertNull(AsRegexUtil.parse("ep1234567890123456789012345"))
        assertNull(AsRegexUtil.parse("https://space.bilibili.com/99999999999999999999999"))

        // 边界值：Long.MAX_VALUE 能解析，再大一位就不行
        assertEquals(
            TextType.BILI.AV(Long.MAX_VALUE),
            AsRegexUtil.parse("av${Long.MAX_VALUE}"),
        )
        assertNull(AsRegexUtil.parse("av9223372036854775808")) // MAX_VALUE + 1
    }

    @Test
    fun `任何输入都不应该抛异常`() {
        val nasty = listOf(
            "", "   ", "123456", "javascript:alert(1)", "\n\n", "av", "ss", "BV", "ep",
            "av-123", "av 123", "https://", "https://bilibili.com", "🙂🙂🙂",
            "a".repeat(10_000),
            "https://www.bilibili.com/video/" + "B".repeat(50),
        )
        nasty.forEach { input ->
            val result = runCatching { AsRegexUtil.parse(input) }
            assertTrue("输入=${input.take(40)} 抛了异常：${result.exceptionOrNull()}", result.isSuccess)
        }
    }

    // ---------------------------------------------------------------- 误判回归

    @Test
    fun `非B站文本里的 av ss ep 不再被误认`() {
        assertNull("nav123456 里的 av 不算 av 号", AsRegexUtil.parse("https://example.com/nav123456"))
        assertNull("一句话里的 ss123 不算季号", AsRegexUtil.parse("会议记录 ss123 已上传"))
        assertNull("css2024 里的 ss 不算季号", AsRegexUtil.parse("https://cdn.example.com/files/css2024.jpg"))
        assertNull("第三方站的 ep12 不算集号", AsRegexUtil.parse("https://xxx-anime.com/ep12"))
        assertNull("普通文本里的 av1 不算 av 号", AsRegexUtil.parse("请注意 av1 编码格式"))
        assertNull("ABV 前缀不算 BV 号", AsRegexUtil.parse("https://example.com/x_ABV1xx411c7mD.bin"))
    }

    @Test
    fun `带B站域名时左边界依然生效`() {
        // 这三条是"域名门槛挡不住"的情况：文本里确实有 bilibili.com，
        // 所以能不能不误判完全取决于各自的左边界 ((?<![A-Za-z0-9]))
        assertNull(
            "B站站内活动页里的 nav12345 不该被当成 av 号",
            AsRegexUtil.parse("https://www.bilibili.com/blackboard/activity-nav12345.html"),
        )
        assertNull(
            "文件名 css2024 里的 ss 不该被当成季号",
            AsRegexUtil.parse("https://www.bilibili.com/blackboard/activity-css2024.jpg"),
        )
        assertNull(
            "查询参数里的 ABV… 不该被当成 BV 号",
            AsRegexUtil.parse("https://www.bilibili.com/?x=ABV1xx411c7mD"),
        )
        // 但正常写法在同一条 URL 里仍要被认出来
        assertEquals(
            TextType.BILI.BV("BV1xx411c7mD"),
            AsRegexUtil.parse("https://www.bilibili.com/video/BV1xx411c7mD"),
        )
    }

    @Test
    fun `没有域名也不成整串的一律不认`() {
        assertNull(AsRegexUtil.parse("视频 av170001 很好看"))
        assertNull(AsRegexUtil.parse("看看 ss46089 这部"))
        assertNull(AsRegexUtil.parse("123456"))
    }

    @Test
    fun `带B站域名的文本仍然能从混杂内容里抽取`() {
        assertEquals(
            TextType.BILI.EP(123456),
            AsRegexUtil.parse("分享自 B 站 https://www.bilibili.com/bangumi/play/ep123456 很好看"),
        )
        assertEquals(
            TextType.BILI.AV(170001),
            AsRegexUtil.parse("在 bilibili.com 看到 av170001 这个视频"),
        )
    }
}
