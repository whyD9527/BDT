package com.imcys.bilibilias.data.user

/**
 * 用户列表页（点赞 / 投币 / 投稿 / 追番 / 历史）**列表项唯一 key** 的纯规则。
 *
 * ## 为什么单独抽出来（这段有真机前科）
 * 2026-09-15 复审 H11：点赞列表用 `cid ?: bvid`、投稿列表用 `bvid` 当 LazyGrid 的 key ——
 * 同一条视频的不同分 P / B 站分页整体后移导致的重复项**会撞 key**，
 * Compose 直接抛 "Key was already used" 崩进程。规则是：
 * 1. 能用**全局唯一**的 ID（aid / cid / seasonId）就用它；
 * 2. 否则退回 bvid；
 * 3. 两者都拿不到才用下标兜底（只为不崩，不代表这条数据正常）。
 *
 * 前缀（`aid:` / `cid:` / `season:`）是为了让不同来源的 key 在同一个网格里也不可能相等。
 * 纯函数，无 Android 依赖 —— 用 JVM 单测钉住语义。
 */
object UserListKeyRules {

    /** 视频项（投稿 / 点赞 / 投币）：aid 唯一，其次 bvid */
    fun videoKey(aid: Long, bvid: String, fallbackIndex: Int): String =
        if (aid > 0L) "aid:$aid" else videoFallbackKey(bvid, fallbackIndex)

    /** 历史项：cid 唯一（同一视频的不同分 P 也不同），其次 bvid */
    fun historyKey(cid: Long, bvid: String, fallbackIndex: Int): String =
        if (cid > 0L) "cid:$cid" else videoFallbackKey(bvid, fallbackIndex)

    /** 追番项：seasonId 唯一 */
    fun bangumiKey(seasonId: Long, fallbackIndex: Int): String =
        if (seasonId > 0L) "season:$seasonId" else "index:$fallbackIndex"

    private fun videoFallbackKey(bvid: String, fallbackIndex: Int): String =
        bvid.ifBlank { "index:$fallbackIndex" }
}
