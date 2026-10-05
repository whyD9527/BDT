package com.imcys.bilibilias.ui.user.list

import androidx.navigation3.runtime.NavKey
import com.imcys.bilibilias.data.user.UserListKeyRules
import kotlinx.serialization.Serializable

/**
 * 用户相关的五种列表来源（#4：四个页面合成一个通用列表页）。
 *
 * 2026-10-05：原来 `LikeVideoScreen` / `WorkListScreen` / `BangumiFollowScreen` /
 * `UserPlayHistoryScreen` 四份实现各写一遍「网格 + 加载/失败重试 + 翻页」，
 * 差异其实只有**数据来源**和**卡片样式**。现在合成一个页面，靠这个枚举分流。
 */
@Serializable
enum class UserListSource {
    LIKE,
    COIN,
    WORK,
    BANGUMI_FOLLOW,
    HISTORY;

    /** 追番是整行的横向卡片（封面在左、信息在右），其余都是两列小卡 */
    val singleColumn: Boolean get() = this == BANGUMI_FOLLOW

    /** 只有「投稿」需要搜索框（原来 WorkListScreen 特有） */
    val withSearch: Boolean get() = this == WORK

    /** 除历史外都要 mid 才能请求（历史走登录态自己的游标接口） */
    val needsMid: Boolean get() = this != HISTORY
}

/** 通用列表页路由：来源 + mid（历史不需要 mid） */
@Serializable
data class UserListRoute(
    val source: UserListSource,
    val mid: Long = 0L,
) : NavKey

/**
 * 分页游标：**页码与历史游标统一成一个 key**，让五种来源共用一个 PagingSource。
 * - 投稿 / 追番：用 [page]
 * - 历史：用 [viewAt] + [max]
 * - 点赞 / 投币：接口没有分页，一次拉完（nextKey 恒为 null）
 */
data class UserListPageKey(
    val page: Int = 1,
    val viewAt: Long = 0L,
    val max: Long = 0L,
)

/** 列表项：三种卡片形态，风格由 UI 决定 */
sealed interface UserListItem {
    /** LazyGrid 的 key（唯一性规则见 [UserListKeyRules]） */
    val uniqueKey: String
}

/**
 * 视频项（投稿 / 点赞 / 投币）。
 *
 * [upName] 为空表示这是「投稿」列表，用不带 UP 名的 [com.imcys.bilibilias.widget.WorkCard]；
 * 点赞/投币有 UP 信息，用 [com.imcys.bilibilias.widget.UserWorkCard] ——
 * 与合并前两种卡片各自的用法完全一致。
 */
data class UserVideoItem(
    override val uniqueKey: String,
    val bvid: String,
    val title: String,
    val pic: String,
    val upName: String? = null,
    val upMid: Long = 0L,
    val view: Long = 0L,
    val danmu: Long = 0L,
) : UserListItem

/** 观看历史项：比视频项多"时长 + 看到哪了" */
data class UserHistoryItem(
    override val uniqueKey: String,
    val bvid: String,
    val title: String,
    val pic: String,
    val upName: String,
    val upMid: Long,
    val duration: Long,
    val progress: Long,
) : UserListItem

/** 追番项 */
data class UserBangumiItem(
    override val uniqueKey: String,
    val seasonId: Long,
    val title: String,
    val intro: String,
    val updateInfo: String,
    val seenInfo: String,
    val pic: String,
) : UserListItem
