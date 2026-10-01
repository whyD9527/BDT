package com.imcys.bilibilias.common.utils.download

import com.imcys.bilibilias.network.model.danmuku.DanmakuElem
import com.imcys.bilibilias.network.model.danmuku.DmSegMobileReply
import java.util.Locale


object DanmakuXmlUtil {

    /**
     * XML 1.0 **不允许**出现的控制字符（保留 `\t`/`\n`/`\r`）。
     *
     * 2026-09-15 复审 A-L6：`escapeXml` 只转义了 `& < > " '`，一旦某条弹幕里混进
     * U+0000–U+0008 这类字符，**整份 XML 都会变成 not well-formed**，
     * 播放器/第三方工具一条都读不出来（不是丢那一条）。
     */
    private val illegalXmlChars =
        Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")

    /**
     * 构建弹幕XML头部（不含 <i> 结尾）
     *
     * @param maxLimit `maxlimit` 字段。2026-09-15 复审 A-L8：原来硬编码 1000，
     *   而调用方是**分页抓完所有弹幕再一次性写出**（长视频轻松过千）——
     *   按该字段截断的播放器只会显示前 1000 条。这里由调用方传真实条数。
     */
    fun buildXmlHeader(chatId: Long = 0L, maxLimit: Int = 1000): String {
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<i>\n")
            append("<chatserver>chat.bilibili.com</chatserver>\n")
            append("<chatid>").append(chatId).append("</chatid>\n")
            append("<mission>0</mission>\n")
            append("<maxlimit>").append(maxLimit).append("</maxlimit>\n")
            append("<state>0</state>\n")
            append("<real_name>0</real_name>\n")
            append("<source>k-v</source>\n")
        }
    }

    /**
     * 构建单条弹幕的 <d> 节点
     */
    fun buildDanmakuNode(elem: DanmakuElem): String {
        val p = buildString {
            // ⚠️ 必须用 Locale.ROOT（2026-09-15 复审 A-M8）：`String.format("%.5f")` 用默认 Locale，
            // 在 de/fr 等"逗号当小数点"的区域会产出 `1,23400` —— 弹幕的 `p` 属性多出一个字段，
            // 时间/模式全错，整份文件语义损坏。
            append(String.format(Locale.ROOT, "%.5f", elem.progress / 1000.0))
            append(",")
            append(elem.mode)
            append(",")
            append(elem.fontSize)
            append(",")
            append(elem.color.toLong() and 0xFFFFFFFF)
            append(",")
            append(elem.createTime)
            append(",0,")
            append(elem.midHash)
            append(",")
            append(elem.idStr)
            append(",10")
        }
        return "<d p=\"$p\">${escapeXml(elem.content)}</d>\n"
    }

    /**
     * 构建所有弹幕内容节点
     */
    fun buildDanmakuNodes(elems: List<DanmakuElem>): String =
        elems.joinToString(separator = "") { buildDanmakuNode(it) }

    /**
     * 构建结尾 </i>
     */
    fun buildXmlFooter(): String = "</i>"

    /**
     * 完整构建弹幕XML（适合一次性全部弹幕）
     */
    fun toBilibiliDanmakuXml(reply: DmSegMobileReply, chatId: Long = 0L): String {
        return toBilibiliDanmakuXml(reply.elems, chatId)
    }

    /**
     * 完整构建弹幕XML（适合一次性全部弹幕）
     *
     * `maxlimit` 取**实际写出的条数**（见 [buildXmlHeader] 的说明）。
     */
    fun toBilibiliDanmakuXml(elems: List<DanmakuElem>, chatId: Long = 0L): String {
        return buildXmlHeader(chatId, maxLimit = elems.size) +
            buildDanmakuNodes(elems) +
            buildXmlFooter()
    }

    /**
     * 简单的XML内容转义。
     *
     * 顺序很重要：`&` 必须先替换，否则后面替换出来的实体（`&lt;`）会被二次转义。
     * 另外先剔除 XML 1.0 非法控制字符（否则整份文档不可解析，见 [illegalXmlChars]）。
     */
    private fun escapeXml(text: String): String =
        text.replace(illegalXmlChars, "")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
