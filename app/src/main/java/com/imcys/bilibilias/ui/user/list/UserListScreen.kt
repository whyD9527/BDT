package com.imcys.bilibilias.ui.user.list

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.imcys.bilibilias.R
import com.imcys.bilibilias.common.event.AnalysisEvent
import com.imcys.bilibilias.common.event.sendAnalysisEvent
import com.imcys.bilibilias.common.utils.toHttps
import com.imcys.bilibilias.ui.widget.ASAsyncImage
import com.imcys.bilibilias.ui.widget.ASCardTextField
import com.imcys.bilibilias.ui.widget.ASTopAppBar
import com.imcys.bilibilias.ui.widget.AsBackIconButton
import com.imcys.bilibilias.ui.widget.BILIBILIASTopAppBarStyle
import com.imcys.bilibilias.ui.widget.shimmer.shimmer
import com.imcys.bilibilias.widget.CommonError
import com.imcys.bilibilias.widget.HistoryPlayVideoCard
import com.imcys.bilibilias.widget.UserWorkCard
import com.imcys.bilibilias.widget.WorkCard
import org.koin.androidx.compose.koinViewModel

/**
 * 通用用户列表页（#4）：点赞 / 投币 / 投稿 / 追番 / 观看历史。
 *
 * 2026-10-05 合并前是四个页面（`LikeVideoScreen`、`WorkListScreen`、`BangumiFollowScreen`、
 * `UserPlayHistoryScreen`），各自写了一遍"网格 + 加载/失败重试 + 翻页"；
 * 「我的」页那四个入口现在都指向这里，只传一个 [UserListSource]。
 *
 * 界面差异只有三处，都按来源分流：
 * 1. 标题（点赞/投币/投稿/追番/历史）；
 * 2. 列数：追番是整行卡片（1 列），其余 2 列；
 * 3. 搜索框：只有投稿需要。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UserListScreen(userListRoute: UserListRoute, onToBack: () -> Unit) {
    val vm = koinViewModel<UserListViewModel>()
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val itemList = vm.items.collectAsLazyPagingItems()
    val query by vm.query.collectAsState()

    LaunchedEffect(userListRoute.source, userListRoute.mid) {
        vm.init(userListRoute.source, userListRoute.mid)
    }

    UserListScaffold(userListRoute.source, scrollBehavior, onToBack) { paddingValues ->
        LazyVerticalGrid(
            modifier = Modifier
                .padding(paddingValues)
                .padding(vertical = 5.dp, horizontal = 10.dp)
                .fillMaxSize(),
            columns = GridCells.Fixed(if (userListRoute.source.singleColumn) 1 else 2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (userListRoute.source.withSearch) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    // ⚠️ 只更新输入框里的文字，防抖在 VM 里（否则每敲一个字发一次请求）
                    ASCardTextField(
                        hint = stringResource(R.string.work_search_hint),
                        autoFocus = false,
                        value = query,
                        onValueChange = { vm.onQueryChange(it) },
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            // ⚠️ key 必须唯一（2026-09-15 复审 H11：撞 key 会让 Compose 直接崩）。
            // 每条的唯一 key 在 PagingSource 里按 UserListKeyRules 生成。
            items(
                count = itemList.itemCount,
                key = { index -> itemList[index]?.uniqueKey ?: "index:$index" },
            ) { index ->
                // ⚠️ animateItem() 是 LazyGridItemScope 的成员扩展：只能在 items 的
                // itemContent 里调用，搬进 UserListItemCard 会编译失败（Unresolved reference）
                itemList[index]?.let { item -> UserListItemCard(item, Modifier.animateItem()) }
            }

            // 空态（#4 合并后统一一处）：加载完成、没有失败、一条都没有 → 告诉用户这里为什么是空的。
            // 原来四份实现都是"不显示任何东西"（真机复验：空追番列表就是一片空白）。
            val refreshState = itemList.loadState.refresh
            if (itemList.itemCount == 0 && refreshState is LoadState.NotLoading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(
                                if (userListRoute.source.withSearch && query.isNotBlank()) {
                                    R.string.user_list_empty_search
                                } else {
                                    userListRoute.source.emptyTextRes()
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            when (val refresh = itemList.loadState.refresh) {
                is LoadState.Error -> item(span = { GridItemSpan(maxLineSpan) }) {
                    CommonError(
                        errorMsg = stringResource(R.string.common_load_failed, refresh.error),
                        onRetry = { itemList.refresh() },
                    )
                }

                is LoadState.Loading -> userListLoading(userListRoute.source)

                else -> Unit
            }

            when (val append = itemList.loadState.append) {
                LoadState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ContainedLoadingIndicator()
                    }
                }

                is LoadState.Error -> item(span = { GridItemSpan(maxLineSpan) }) {
                    CommonError(
                        stringResource(R.string.common_load_failed, append.error),
                        onRetry = { itemList.retry() },
                    )
                }

                else -> Unit
            }
        }
    }
}

/** 一张卡片按条目类型分流（合并前三种卡片的用法保持不变） */
@Composable
private fun UserListItemCard(item: UserListItem, modifier: Modifier = Modifier) {
    when (item) {
        is UserVideoItem -> if (item.upName == null) {
            WorkCard(
                modifier = modifier,
                bvId = item.bvid,
                title = item.title,
                pic = item.pic,
                view = item.view,
                danmu = item.danmu,
            )
        } else {
            UserWorkCard(
                modifier = modifier,
                bvId = item.bvid,
                title = item.title,
                pic = item.pic,
                upName = item.upName ?: "",
                mid = item.upMid,
                view = item.view,
                danmu = item.danmu,
            )
        }

        is UserHistoryItem -> HistoryPlayVideoCard(
            modifier = modifier,
            bvId = item.bvid,
            title = item.title,
            pic = item.pic,
            upName = item.upName,
            mid = item.upMid,
            duration = item.duration,
            progress = item.progress,
        )

        is UserBangumiItem -> BangumiCard(
            modifier = modifier,
            seasonId = item.seasonId,
            title = item.title,
            intro = item.intro,
            updateInfo = item.updateInfo,
            seenInfo = item.seenInfo,
            pic = item.pic,
        )
    }
}

/** 骨架屏：按来源给对应的卡片形状（原来四个页面各有一份） */
private fun LazyGridScope.userListLoading(source: UserListSource) {
    items(10) {
        when (source) {
            UserListSource.BANGUMI_FOLLOW -> BangumiCard(
                modifier = Modifier.shimmer(true),
                title = "标题",
                intro = "",
                updateInfo = "更新至X话",
                seenInfo = "看到",
                pic = "",
            )

            UserListSource.HISTORY -> HistoryPlayVideoCard(modifier = Modifier.shimmer(true))
            UserListSource.WORK -> WorkCard(modifier = Modifier.shimmer(true))
            else -> UserWorkCard(modifier = Modifier.shimmer(true))
        }
    }
}

@StringRes
private fun UserListSource.titleRes(): Int = when (this) {
    UserListSource.LIKE -> R.string.like_page_like
    UserListSource.COIN -> R.string.like_page_coin
    UserListSource.WORK -> R.string.work_list_title
    UserListSource.BANGUMI_FOLLOW -> R.string.user_bangumi
    UserListSource.HISTORY -> R.string.user_recent_play_title
}

/** 空态文案：每种来源说清"这里为什么是空的"（界面上不能只留一片白） */
@StringRes
private fun UserListSource.emptyTextRes(): Int = when (this) {
    UserListSource.LIKE -> R.string.user_list_empty_like
    UserListSource.COIN -> R.string.user_list_empty_coin
    UserListSource.WORK -> R.string.user_list_empty_work
    UserListSource.BANGUMI_FOLLOW -> R.string.user_list_empty_bangumi
    UserListSource.HISTORY -> R.string.user_list_empty_history
}

@Preview
@Composable
fun BangumiCard(
    modifier: Modifier = Modifier,
    seasonId: Long = 0L,
    title: String = "标题",
    intro: String = "介绍",
    updateInfo: String = "更新至X话",
    seenInfo: String = "看到",
    pic: String = "",
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = CardDefaults.shape,
        modifier = modifier.fillMaxWidth(),
        onClick = {
            sendAnalysisEvent(AnalysisEvent("ss${seasonId}"))
        }
    ) {
        Row(
            Modifier
                .padding(8.dp)
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            Column(
                modifier = Modifier
                    .weight(0.3f)
                    .aspectRatio(9f / 16f)
            ) {
                ASAsyncImage(
                    model = pic.toHttps(),
                    shape = CardDefaults.shape,
                    contentDescription = stringResource(R.string.cd_bangumi_cover),
                    modifier = Modifier
                        .fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.width(10.dp))
            Column(
                Modifier.weight(0.7f)
            ) {
                Text(text = title, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(text = intro, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)

                Spacer(Modifier.weight(1f))
                Text(text = seenInfo, fontSize = 14.sp)
                Text(text = updateInfo, fontSize = 14.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserListScaffold(
    source: UserListSource,
    scrollBehavior: TopAppBarScrollBehavior,
    onToBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            ASTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                scrollBehavior = scrollBehavior,
                style = BILIBILIASTopAppBarStyle.Large,
                title = {
                    Text(text = stringResource(source.titleRes()))
                },
                navigationIcon = {
                    AsBackIconButton(onClick = {
                        onToBack.invoke()
                    })
                }
            )
        },
    ) {
        content(it)
    }
}
