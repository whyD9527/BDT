package com.imcys.bilibilias.ui.user.list

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.imcys.bilibilias.common.utils.toHttps
import com.imcys.bilibilias.data.model.BILISpaceArchiveModel
import com.imcys.bilibilias.data.model.user.BILIUserHistoryPlayModel
import com.imcys.bilibilias.data.repository.UserInfoRepository
import com.imcys.bilibilias.data.user.UserListKeyRules
import com.imcys.bilibilias.network.ApiStatus
import com.imcys.bilibilias.network.model.user.BILIUserBangumiFollowInfo
import com.imcys.bilibilias.network.model.user.BILIUserVideoLikeInfo.LikeAndCoinItemData
import kotlinx.coroutines.flow.last

/**
 * 五种用户列表共用的 PagingSource（#4）。
 *
 * 合并前的四份实现差异只在"怎么请求下一页"：
 * - 点赞 / 投币：**接口没有分页**，一次拉完；
 * - 投稿：页码 + `page.hasNext`；
 * - 追番：页码（返回空列表即到底）；
 * - 历史：游标（`view_at` + `max`）。
 *
 * 这里统一成 [UserListPageKey]，并把各自的响应映射成 [UserListItem]。
 * ⚠️ 每条的 key 由 [UserListKeyRules] 生成：分页重叠 / 同视频多分 P 时**绝不能撞 key**
 *（2026-09-15 复审 H11：撞 key 会让 Compose 直接抛异常崩掉）。
 */
class UserListPagingSource(
    private val userInfoRepository: UserInfoRepository,
    private val source: UserListSource,
    private val mid: Long,
    private val keyword: String? = null,
) : PagingSource<UserListPageKey, UserListItem>() {

    override fun getRefreshKey(state: PagingState<UserListPageKey, UserListItem>): UserListPageKey? {
        // 点赞/投币是一次性列表，历史是游标列表（不支持往前翻）：都不需要"回到锚点重拉"
        if (source == UserListSource.LIKE || source == UserListSource.COIN || source == UserListSource.HISTORY) {
            return null
        }
        return state.anchorPosition?.let { position ->
            val page = state.closestPageToPosition(position) ?: return@let null
            val prev = page.prevKey
            val next = page.nextKey
            prev?.copy(page = prev.page + 1)
                ?: next?.copy(page = (next.page - 1).coerceAtLeast(1))
        }
    }

    override suspend fun load(params: LoadParams<UserListPageKey>): LoadResult<UserListPageKey, UserListItem> =
        runCatching { loadPage(params.key ?: UserListPageKey()) }
            .getOrElse { LoadResult.Error(it) }

    private suspend fun loadPage(key: UserListPageKey): LoadResult<UserListPageKey, UserListItem> =
        when (source) {
            UserListSource.LIKE, UserListSource.COIN -> loadVideoOnce()
            UserListSource.WORK -> loadWorkPage(key)
            UserListSource.BANGUMI_FOLLOW -> loadBangumiPage(key)
            UserListSource.HISTORY -> loadHistoryPage(key)
        }

    /** 点赞 / 投币：接口一次给全，没有下一页 */
    private suspend fun loadVideoOnce(): LoadResult<UserListPageKey, UserListItem> {
        if (mid <= 0L) return emptyPage()
        val result = when (source) {
            UserListSource.COIN -> userInfoRepository.getCoinVideoList(mid)
            else -> userInfoRepository.getLikeVideoList(mid)
        }.last()
        return when (result.status) {
            ApiStatus.SUCCESS -> {
                val list = result.data?.list.orEmpty().distinctBy { it.aid }
                LoadResult.Page(
                    data = list.mapIndexed { index, item -> item.toListItem(index) },
                    prevKey = null,
                    nextKey = null,
                )
            }

            ApiStatus.ERROR -> LoadResult.Error(Throwable(result.errorMsg ?: ""))
            else -> LoadResult.Error(Throwable(LOADING_ERROR))
        }
    }

    /** 投稿：页码分页，到底看 `page.hasNext` */
    private suspend fun loadWorkPage(key: UserListPageKey): LoadResult<UserListPageKey, UserListItem> {
        if (mid <= 0L) return emptyPage()
        val result = userInfoRepository
            .getSpaceArchiveInfo(mid = mid, pn = key.page, ps = PAGE_SIZE, keyword = keyword)
            .last()
        return when (result.status) {
            ApiStatus.SUCCESS -> {
                val data = result.data
                val list = data?.list.orEmpty().distinctBy { it.aid }
                LoadResult.Page(
                    data = list.mapIndexed { index, item -> item.toListItem(index) },
                    prevKey = if (key.page == 1) null else key.copy(page = key.page - 1),
                    nextKey = if (list.isNotEmpty() && data?.page?.hasNext == true) {
                        key.copy(page = key.page + 1)
                    } else {
                        null
                    },
                )
            }

            ApiStatus.ERROR -> LoadResult.Error(Throwable(result.errorMsg ?: ""))
            else -> LoadResult.Error(Throwable(LOADING_ERROR))
        }
    }

    /** 追番：页码分页，返回空列表即到底 */
    private suspend fun loadBangumiPage(key: UserListPageKey): LoadResult<UserListPageKey, UserListItem> {
        if (mid <= 0L) return emptyPage()
        val result = userInfoRepository
            .getBangumiFollowInfo(vmid = mid, pn = key.page, ps = PAGE_SIZE)
            .last()
        return when (result.status) {
            ApiStatus.SUCCESS -> {
                val list = result.data?.list.orEmpty()
                LoadResult.Page(
                    data = list.mapIndexed { index, item -> item.toListItem(index) },
                    prevKey = if (key.page == 1) null else key.copy(page = key.page - 1),
                    nextKey = if (list.isEmpty()) null else key.copy(page = key.page + 1),
                )
            }

            ApiStatus.ERROR -> LoadResult.Error(Throwable(result.errorMsg ?: ""))
            else -> LoadResult.Error(Throwable(LOADING_ERROR))
        }
    }

    /** 历史：游标分页（取本页最后一条的游标） */
    private suspend fun loadHistoryPage(key: UserListPageKey): LoadResult<UserListPageKey, UserListItem> {
        val result = userInfoRepository
            .getHistoryCursor(max = key.max, viewAt = key.viewAt, ps = PAGE_SIZE)
            .last()
        return when (result.status) {
            ApiStatus.SUCCESS -> {
                val data = result.data.orEmpty()
                val nextCursor = data.lastOrNull()?.let { UserListPageKey(viewAt = it.viewAt, max = it.max) }
                LoadResult.Page(
                    data = data.mapIndexed { index, item -> item.toListItem(index) },
                    prevKey = null,
                    // 游标没动 = 到底（与合并前的判断一致，避免无限翻页）
                    nextKey = if (data.isEmpty() || nextCursor == null || nextCursor == key) null else nextCursor,
                )
            }

            ApiStatus.ERROR -> LoadResult.Error(Throwable(result.errorMsg ?: ""))
            else -> LoadResult.Error(Throwable(LOADING_ERROR))
        }
    }

    private fun emptyPage(): LoadResult.Page<UserListPageKey, UserListItem> =
        LoadResult.Page<UserListPageKey, UserListItem>(data = emptyList(), prevKey = null, nextKey = null)

    companion object {
        private const val PAGE_SIZE = 20
        private const val LOADING_ERROR = "加载中"
    }
}

// region 各来源响应 → 通用列表项

private fun LikeAndCoinItemData.toListItem(index: Int) = UserVideoItem(
    uniqueKey = UserListKeyRules.videoKey(aid = aid, bvid = bvid, fallbackIndex = index),
    bvid = bvid,
    title = title,
    pic = "${pic.toHttps()}@672w_378h_1c",
    upName = owner.name,
    upMid = owner.mid,
    view = stat.view,
    danmu = stat.danmaku,
)

private fun BILISpaceArchiveModel.Item.toListItem(index: Int) = UserVideoItem(
    uniqueKey = UserListKeyRules.videoKey(aid = aid, bvid = bvid, fallbackIndex = index),
    bvid = bvid,
    title = title,
    pic = "${pic.toHttps()}@672w_378h_1c",
    // 投稿列表的卡片不带 UP 名（与合并前的 WorkCard 一致）
    upName = null,
    view = play,
    danmu = danmu,
)

private fun BILIUserBangumiFollowInfo.ItemData.toListItem(index: Int) = UserBangumiItem(
    uniqueKey = UserListKeyRules.bangumiKey(seasonId = seasonId, fallbackIndex = index),
    seasonId = seasonId,
    title = title,
    intro = evaluate,
    updateInfo = newEp.indexShow ?: "",
    seenInfo = progress,
    pic = "${cover.toHttps()}@308w_410h_1c",
)

private fun BILIUserHistoryPlayModel.toListItem(index: Int) = UserHistoryItem(
    uniqueKey = UserListKeyRules.historyKey(
        cid = history.cid,
        bvid = history.bvid,
        fallbackIndex = index,
    ),
    bvid = history.bvid,
    title = title,
    pic = "${cover.toHttps()}@672w_378h_1c",
    upName = authorName,
    upMid = authorMid,
    duration = duration,
    progress = progress,
)
// endregion
