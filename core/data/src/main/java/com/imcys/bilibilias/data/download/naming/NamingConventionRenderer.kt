package com.imcys.bilibilias.data.download.naming

/**
 * 「按命名规则模板生成文件名」的纯渲染器（可单测）。
 *
 * ## 为什么要抽出来
 * 原实现在 `:app` 的 `NamingConventionHandler` 里，而 **`:app` 的单测本机跑不了**
 * （aapt2 是 x86_64，设备是 arm64）。这段逻辑要守的恰恰是一条只能靠单测钉住的不变量
 * ——「替换值里的下划线是内容，分隔用的下划线才是分隔」—— 所以搬进库模块。
 *
 * ## 原来的 bug（第十八轮审查的低危项）
 * 每替换完一个占位符，都会对**整个累计字符串**做 `replace(Regex("_+"), "_")`：
 * ```kotlin
 * filePath = filePath
 *     .replace(rule.placeholder, value)
 *     .replace(Regex("_+"), "_")   // ← 把标题里本来就有的下划线也吃掉了
 *     .replace(Regex("_+$"), "")
 * ```
 * 于是标题 `我的_世界` 会变成 `我的世界`、`A__B` 会变成 `A_B`，用户完全无从解释。
 * 而这个 collapse 本来是给"某个占位符取值为空、留下多余分隔符"兜底的
 * （`p_title` / `collection_*` 之类字段确实经常是空串）。
 *
 * ## 现在的做法：**先切开，再逐段拼**
 * 把模板按占位符切成「模板字面段」与「取值段」，两类段落的待遇**不同**：
 *
 * | 段落 | 处理 |
 * |---|---|
 * | 模板字面段（`_` 之类，来自用户模板本身） | 把**其中**的连续下划线塌成一个（老行为） |
 * | 取值段（`title` / `p_title` 的值） | **原样保留**，一个下划线都不动 |
 * | 取值为空 | 不产出字符，并顺手把**前面刚刚留下的**独立分隔下划线收掉 |
 *
 * 这样"值里的下划线"永远不会进入 collapse 的作用域 —— 不靠任何转义技巧。
 * （试过用哨兵符把值包起来再 collapse：**不成立**。Java/Kotlin 的
 * `replaceAll("_+", "_")` 把相邻匹配当成各自独立的一次替换，
 * `\u0001A__B\u0001` 里的两个 `_` 仍然各自被替换掉，值照样被塌。）
 *
 * 另外两条与原实现对齐的细节（都不是这次改的重点，刻意保持一致）：
 * - 替换值是**字面**替换，且模板里每个占位符**只替换一次**；
 * - **不 trim 开头的下划线**：模板以 `_` 开头时原实现就会保留它（只 trim 尾部），逐字沿用。
 */
object NamingConventionRenderer {

    /**
     * 模板里所有形如 `{xxx}` 的片段（真实占位符全是小写字母 + 下划线）。
     *
     * ⚠️ **右花括号也必须转义**（`\}`）。写成 `"\\{[a-z_]+}"` 在 **JDK 上能跑、在 Android 上直接崩**：
     * 设备用的是 ICU 正则引擎，`Pattern` 的解析比 JDK 严 —— 未转义的 `}` 会报
     * `PatternSyntaxException: Syntax error in regexp pattern near index 10`，
     * 而这类静态初始化的异常是 `ExceptionInInitializerError`，**整条下载线程直接挂掉**
     * （第三十一轮真机第一次点下载就崩了；本机单测全绿也照样漏 —— 见交接文档第八节第 20 条）。
     */
    private val placeholderPattern = Regex("\\{[a-z_]+\\}")

    /** 模板字面段里的连续下划线（只对本段生效，见类的说明） */
    private val runsOfSeparators = Regex("_+")

    /** 收尾时要丢掉的尾部独立分隔下划线（只对"取值为空留下的"那种生效） */
    private val trailingSeparators = Regex("_+$")

    /**
     * 渲染文件名。
     *
     * @param template 命名规则模板，如 `{title}_{p_title}_{cid}`
     * @param placeholders 已知占位符列表（模板里出现、但不在表里的占位符原样留着）
     * @param values 占位符 → 值；null / 空串都按"这个占位符没值"处理
     * @param fileExtension 后缀（不含点）；渲染完若不以 `.<后缀>` 结尾就补上
     */
    fun render(
        template: String,
        placeholders: List<String>,
        values: Map<String, String?>,
        fileExtension: String,
    ): String {
        val parts = splitTemplate(template)
        val known = placeholders.toSet()

        val builder = StringBuilder()
        // 「上一个有效段落是取值为空的占位符」——它会在末尾留下一个独立分隔下划线，
        // 而那个下划线只有在**后面接上真东西**（或字符串结束）时才该被收掉。
        var lastTokenWasEmptyValue = false

        parts.forEach { part ->
            if (part.isPlaceholder) {
                if (!known.contains(part.text)) {
                    // 模板里的未知成分（例如 `{unknown}`）：原样留着（老行为）
                    builder.append(part.text)
                    lastTokenWasEmptyValue = false
                    return@forEach
                }
                val value = values[part.text]
                    ?.takeIf { it.isNotEmpty() }
                    // 斜杠会破坏文件名结构，按老规矩换掉（这是唯一对取值做的字符处理）
                    ?.replace("/", "_")

                if (value == null) {
                    lastTokenWasEmptyValue = true
                } else {
                    if (lastTokenWasEmptyValue) {
                        trimOrphanSeparator(builder)
                        lastTokenWasEmptyValue = false
                    }
                    builder.append(value)
                }
            } else {
                // 模板字面段：只塌它自己内部的下划线（老行为）
                var literal = part.text.replace(runsOfSeparators, "_")
                if (lastTokenWasEmptyValue) {
                    // 上一个占位符取值为空：它留下的那个分隔下划线就是本段开头那一个。
                    // ⚠️ 只吃掉**一个**（而不是把整段的开头下划线都吃掉）：
                    // `{title}_{p_title}_{cid}` 里 p_title 为空时，第二个 `_` 是给 cid 的分隔符，
                    // 必须留着 —— 老实现的 `Regex("_+$")` 也只在"后面没东西了"时才删。
                    literal = literal.removePrefix("_")
                    lastTokenWasEmptyValue = false
                }
                builder.append(literal)
            }
        }

        if (lastTokenWasEmptyValue) {
            trimOrphanSeparator(builder)
        }

        var filePath = builder.toString()
        if (!filePath.endsWith(".$fileExtension")) {
            filePath += ".$fileExtension"
        }
        return filePath
    }

    /**
     * 去掉"上一个占位符取值为空"留下的那个尾部独立下划线。
     *
     * ⚠️ 只在**模板末尾**那个占位符取值为空时调用：这时末尾那个 `_` 一定是分隔符
     * （取值内容根本没产出字符），删掉它正是老实现 `replace(Regex("_+$"), "")` 想干的事。
     * 值中间的情况由"吃掉本段开头那一个下划线"处理（见 [render] 里那段注释）——
     * 那里不能顺手 trim 尾部，否则会把**取值内容**里的下划线也删掉。
     */
    private fun trimOrphanSeparator(builder: StringBuilder) {
        val trimmed = trailingSeparators.replace(builder.toString(), "")
        builder.setLength(0)
        builder.append(trimmed)
    }

    /** 模板按"占位符 / 字面文本"交替切分 */
    private fun splitTemplate(template: String): List<Part> {
        val parts = mutableListOf<Part>()
        var cursor = 0
        placeholderPattern.findAll(template).forEach { match ->
            if (match.range.first > cursor) {
                parts += Part(template.substring(cursor, match.range.first), isPlaceholder = false)
            }
            parts += Part(match.value, isPlaceholder = true)
            cursor = match.range.last + 1
        }
        if (cursor < template.length) {
            parts += Part(template.substring(cursor), isPlaceholder = false)
        }
        return parts
    }

    private data class Part(val text: String, val isPlaceholder: Boolean)
}
