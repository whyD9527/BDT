package com.imcys.bilibilias.network.config

import com.imcys.bilibilias.network.config.API.BILIBILI.TV_PGC_PLAYER_URL
import com.imcys.bilibilias.network.config.API.BILIBILI.TV_QRCODE_GENERATE_URL
import com.imcys.bilibilias.network.config.API.BILIBILI.TV_QRCODE_POLL_URL
import com.imcys.bilibilias.network.config.API.BILIBILI.TV_VIDEO_PLAYER_URL

/**
 * 全 App 唯一的「请求头规则」来源。
 *
 * 为什么必须集中：同一条规则原先散落在三处，而且互相矛盾 ——
 *
 * | 位置 | 它对「TV 平台要不要 UA / Referer」的答案 |
 * |---|---|
 * | [plugin.AutoBILIInfoPlugin]（Ktor 业务请求） | 对 4 个 TV 端点**删掉** UA 和 Referer |
 * | [di.KtorDI] 里的 OkHttp 拦截器（下载） | 只补 Referer，不补 UA |
 * | `LineConfigViewModel.speedTest`（裸 java.net） | TV 用 `"Mozilla/5.0"`，Referer 是个具体视频页 |
 *
 * 2026-09 那次「下载必然 403」的根因就是这类不一致：CDN 请求被同一个插件
 * 按 TV 规则把头删掉了。**以后凡涉及请求头，只改这一个文件。**
 *
 * ⚠️ 本文件里的规则是**既有行为的如实搬运**，不是"理想设计"。
 * 有矛盾的地方（例如测速的 Referer 与默认 Referer 不同）刻意保留原值并注明，
 * 改动它们需要先拿到真实证据，别顺手"统一"。
 */
object RequestRules {

    /** 默认 Referer：B 站 CDN 校验的就是它，缺了直接 403 */
    const val DEFAULT_REFERER: String = BILIBILI_URL

    /** 默认浏览器 UA：CDN 同样要求它（换成 BiliApp 的 UA 一样被 403） */
    const val DEFAULT_USER_AGENT: String = BROWSER_USER_AGENT

    // ------------------------------------------------------------------
    // 媒体 CDN
    // ------------------------------------------------------------------

    /**
     * 媒体 CDN 主机关键字：命中即**无条件**补齐浏览器 UA + Referer。
     *
     * ⚠️ 关于 `"edge"`：**已取证，结论是保持不动**（2026-09-12）。
     *
     * 取证过程：B 站 playurl 对本机 IP 一律返回 `-412 request was banned`（匿名也拿不到
     * 媒体地址），Android 也不再记录 DNS 查询事件，历史里的 `edgekey.net` 只出现在注释里。
     * 所以改成**从 App 侧取证**：命中 CDN 时打印 host 与命中的关键字
     * （见 `AutoBILIInfoPlugin` 的 `ASRequest` 日志），真机下载一次即可。
     *
     * 实测结果：
     * - 真实流量：多次媒体请求全部命中 `upos-sz-mirrorzos.bilivideo.com`，
     *   **靠 `bilivideo` 命中，没有任何主机靠 `edge` 命中**；
     * - App 自带的 20 条线路主机里，19 条 `*.bilivideo.com` + 1 条
     *   `upos-hz-mirrorakam.akamaized.net` —— **全部由 `bilivideo`/`akamaized` 覆盖**，
     *   没有一条需要 `edge`（也没有需要 `mountaintoys` 的）。
     *
     * 那为什么仍然不删？因为**删了没有任何收益，却要承担一个风险敞口**：
     * 命中 CDN 分支与走默认分支的差别只有"覆盖已有 Referer"和"UA 缺失时补浏览器 UA"，
     * 而媒体请求本来就带 bilibili 的 Referer、两个 Ktor 客户端都装了 `BrowserUserAgent()`
     * —— 两者对媒体请求几乎等价。也就是说 `edge` 既不会造成实际危害，
     * 删掉也换不来任何行为改善；而万一将来出现只靠它命中的主机，删掉就会出问题。
     *
     * **它会自己暴露自己**：只要真有 `命中关键字=edge` 的主机出现，
     * `ASRequest` 日志就会显示出来（见 `DownloadExecutor` 的 `ASDownload` 同理）。
     * ✅ 2026-09-12 真机大文件实测：**它确实出现了** —— 走用户配置的第三方线路
     * `cze5361c.edge.mountaintoys.cn:4483`（4 片并发，640MB，无 403、无回落）。
     *
     * ⚠️ 但**别把那行日志当成"edge 是必需的"的证据**：日志打的是**列表里第一个**命中的
     * 关键字，而 `edge` 排在 `mountaintoys` 前面 —— 那个主机里两个关键字都有，
     * 打出来的自然是 `edge`。想知道某个关键字到底能不能删，**必须把它单独从列表里
     * 拿掉再跑一次真机下载**，只看日志会得出错误结论。
     *
     * ⛔ 唯一需要留意的是顺序问题：CDN 分支排在 TV「去头」分支之前，
     * 若将来某个 TV 端点落在含 edge 的主机上会被覆盖。目前 4 个 TV 端点的主机
     * （`*.aisee.tv`、`*.bilibili.com`）都不含 edge，暂不成立。
     */
    private val MEDIA_CDN_HOST_KEYWORDS = listOf(
        "bilivideo",
        "akamaized",
        "edge",
        "mountaintoys",
    )

    fun isMediaCdnHost(host: String): Boolean = matchedMediaCdnKeyword(host) != null

    /**
     * 返回**命中的是哪个关键字**（没命中返回 null）。
     *
     * 专为取证准备：日志里同时打出 host 与命中的关键字，
     * 就能直接回答"有没有哪个主机**只靠 `edge`** 才被判定为 CDN"——
     * 这正是决定能否收窄 `edge` 的唯一依据（见上面的说明）。
     */
    fun matchedMediaCdnKeyword(host: String): String? =
        MEDIA_CDN_HOST_KEYWORDS.firstOrNull { host.contains(it) }

    // ------------------------------------------------------------------
    // TV 平台需要「去掉请求头」的端点
    // ------------------------------------------------------------------

    /**
     * 解析平台 = TV 时，这些端点必须**不带** UA 与 Referer（B 站 TV 端接口的要求）。
     *
     * 注意这个规则与上面的 CDN 规则方向相反，是**刻意如此**：
     * 别再"顺手统一"成同一个行为 —— 那正是当初 403 的成因。
     */
    private val TV_PLATFORM_STRIP_HEADER_URLS = listOf(
        TV_QRCODE_GENERATE_URL,
        TV_QRCODE_POLL_URL,
        TV_VIDEO_PLAYER_URL,
        TV_PGC_PLAYER_URL,
    )

    fun shouldStripHeadersOnTvPlatform(url: String): Boolean =
        TV_PLATFORM_STRIP_HEADER_URLS.any { url.contains(it) }

    // ------------------------------------------------------------------
    // 媒体主机（供下载线路替换等使用）
    // ------------------------------------------------------------------

    /**
     * 默认 CDN 主机模式：只有命中它的下载地址，才允许被用户选择的线路主机替换。
     *
     * 原先硬编码在 `DownloadExecutor.replaceCdn()` 里 —— 属于与上面同一类
     * "媒体主机知识"，一并收拢到这里，避免主机规则散落多处。
     */
    val UPOS_CDN_HOST_REGEX = Regex("""upos-sz-estg[0-9a-z]*\.bilivideo\.com""", RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------------
    // 线路测速（走 java.net 裸连接，不是 Ktor，所以头要单独给）
    // ------------------------------------------------------------------

    /**
     * 测速用的 UA：TV 平台历史上用的是 `"Mozilla/5.0"`，其余用浏览器 UA。
     *
     * ⚠️ 与 CDN 规则（TV 也用浏览器 UA）**并不一致**，这是既有行为，先原样集中。
     */
    const val TV_SPEED_TEST_USER_AGENT = "Mozilla/5.0"

    /** 测速用的 Referer：是个具体视频页，与 [DEFAULT_REFERER] 不同 —— 同样是既有值，先保留 */
    const val SPEED_TEST_REFERER = "https://www.bilibili.com/video/BV1Cf421q78E/"

    fun speedTestUserAgent(isTv: Boolean): String =
        if (isTv) TV_SPEED_TEST_USER_AGENT else DEFAULT_USER_AGENT

    /** TV 平台不加 Referer，返回 null（调用方自行判断是否设置） */
    fun speedTestReferer(isTv: Boolean): String? =
        if (isTv) null else SPEED_TEST_REFERER
}
