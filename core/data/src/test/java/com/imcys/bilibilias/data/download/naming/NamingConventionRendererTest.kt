package com.imcys.bilibilias.data.download.naming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命名规则渲染器的测试。
 *
 * 守的是第十八轮审查里的低危项：`Regex("_+")` 会把**标题里本来就有的下划线**吃掉。
 * 那个 collapse 本来只是给"某个占位符取值为空、留下多余分隔符"兜底的。
 */
class NamingConventionRendererTest {

    private val videoPlaceholders = listOf(
        "{title}", "{p_title}", "{author}", "{p}", "{aid}", "{bvid}", "{cid}",
        "{collection_title}", "{collection_season_title}",
    )

    private fun renderVideo(
        template: String,
        values: Map<String, String?>,
        ext: String = "mp4",
    ) = NamingConventionRenderer.render(template, videoPlaceholders, values, ext)

    @Test
    fun `标题里的下划线必须原样保留（这就是那个 bug）`() {
        val name = renderVideo(
            template = "{title}",
            values = mapOf("{title}" to "我的_世界"),
        )
        assertEquals("我的_世界.mp4", name)
    }

    @Test
    fun `标题里连续的下划线也不能被塌成一个`() {
        val name = renderVideo(
            template = "{title}",
            values = mapOf("{title}" to "A__B___C"),
        )
        assertEquals("A__B___C.mp4", name)
    }

    @Test
    fun `值为空留下的多余分隔符仍然要塌成一个并去掉末尾的`() {
        // 这是那个 collapse 存在的唯一理由：p_title / collection_* 经常是空串。
        val name = renderVideo(
            template = "{title}_{p_title}_{cid}",
            values = mapOf("{title}" to "某视频", "{p_title}" to "", "{cid}" to "123"),
        )
        assertEquals("某视频_123.mp4", name)
    }

    @Test
    fun `模板里自己写多的下划线仍然要塌成一个（老行为）`() {
        // 分隔符的塌缩只对**模板字面段**生效：模板里写 `{title}__{cid}`，用户要的就是一个分隔符。
        // 这条与"值里的下划线不许动"是一对，缺了它 collapse 整个被删掉都不会变红。
        val name = renderVideo(
            template = "{title}__{cid}",
            values = mapOf("{title}" to "某视频", "{cid}" to "123"),
        )
        assertEquals("某视频_123.mp4", name)
    }

    @Test
    fun `尾部占位符为空时末尾分隔符要被去掉`() {
        val name = renderVideo(
            template = "{title}_{author}",
            values = mapOf("{title}" to "某视频", "{author}" to null),
        )
        assertEquals("某视频.mp4", name)
    }

    @Test
    fun `内容下划线与空值分隔符混在一起时只收拾分隔符`() {
        val name = renderVideo(
            template = "{title}_{p_title}_{p}",
            values = mapOf("{title}" to "我的_世界", "{p_title}" to "", "{p}" to "2"),
        )
        assertEquals("我的_世界_2.mp4", name)
    }

    @Test
    fun `占位符值不会被再当成模板处理`() {
        // {p_title} 的值本身可以含有花括号（B 站标题偶见），
        // 收拾分隔符的时机如果放在"还没替换完"的中间态就会把它弄坏。
        val name = renderVideo(
            template = "{title}_{p_title}_{cid}",
            values = mapOf("{title}" to "{p_title}", "{p_title}" to "第二话", "{cid}" to "9"),
        )
        // 同上：值里的字面量活着，模板里的 `{p_title}` 正常取到"第二话"。
        assertEquals("{p_title}_第二话_9.mp4", name)
    }

    @Test
    fun `值里的斜杠仍然按老规矩换成下划线`() {
        val name = renderVideo(
            template = "{title}_{cid}",
            values = mapOf("{title}" to "上/下", "{cid}" to "1"),
        )
        assertEquals("上_下_1.mp4", name)
    }

    @Test
    fun `后缀已经存在时不重复追加`() {
        val name = renderVideo(
            template = "{title}.mp4",
            values = mapOf("{title}" to "某视频"),
        )
        assertEquals("某视频.mp4", name)
    }

    @Test
    fun `没有值的占位符就是空串（与老行为一致）`() {
        val name = renderVideo(
            template = "{title}_{cid}",
            values = mapOf("{title}" to "某视频", "{cid}" to null),
        )
        // 老实现：`{cid}` 空 → "某视频_" → 尾部分隔符被 trim → "某视频"
        assertEquals("某视频.mp4", name)
    }

    @Test
    fun `模板里的未知占位符原样留着（老行为）`() {
        val name = renderVideo(
            template = "{title}_{unknown}",
            values = mapOf("{title}" to "某视频"),
        )
        assertEquals("某视频_{unknown}.mp4", name)
    }

    @Test
    fun `值的字面占位符不会再被替换第二遍`() {
        // 老实现里 `{p_title}` 若出现在 `{title}` 的值里，会被后续那轮替换**再替换一次** ——
        // 值本身被改写。这里把它钉住：替换只发生一次。
        val name = renderVideo(
            template = "{title}_{p_title}",
            values = mapOf("{title}" to "{p_title}", "{p_title}" to "第二话"),
        )
        // `{title}` 的值是个字面量 `{p_title}`：它**不该**被后面那轮替换再换一次
        // （老实现会把它也换成"第二话"，值就被改写了）。
        // 模板里真正的 `{p_title}` 照样换成"第二话"，两者各自独立。
        assertEquals("{p_title}_第二话.mp4", name)
    }

    @Test
    fun `collapse 靠转义是保护不了值的（所以只能按段处理）`() {
        // 这条钉的是"为什么不能用哨兵符 + collapse 的老路"：
        // `replaceAll("_+", "_")` 把相邻匹配当成各自独立的一次替换，
        // 所以哪怕值被哨兵符整段围住，值里连续的 `__` 照样各自被塌掉。
        val guarded = "\u0001" + "A__B" + "\u0001"
        assertEquals("\u0001A_B\u0001", guarded.replace(Regex("_+"), "_"))
    }

    @Test
    fun `空串与 null 都按没值处理`() {
        assertEquals(
            "某视频.mp4",
            renderVideo("{title}_{author}", mapOf("{title}" to "某视频", "{author}" to "")),
        )
        assertEquals(
            "某视频.mp4",
            renderVideo("{title}_{author}", mapOf("{title}" to "某视频", "{author}" to null)),
        )
    }
}
