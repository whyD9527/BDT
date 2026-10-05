package com.imcys.bilibilias.ui.user.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.imcys.bilibilias.data.repository.UserInfoRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * 通用用户列表页的 ViewModel（#4：四个页面合成一个）。
 *
 * 合并前四份实现的差异只在"数据来源"和"要不要搜索框"，其余（分页配置、缓存、
 * 失败重试）完全一样 —— 都收在这里，翻页与 key 的生成交给 [UserListPagingSource]。
 */
@OptIn(FlowPreview::class)
class UserListViewModel(
    private val userInfoRepository: UserInfoRepository,
) : ViewModel() {

    private data class Params(
        val source: UserListSource,
        val mid: Long,
    )

    private val params = MutableStateFlow<Params?>(null)

    /** 搜索框里的文字（只有「投稿」来源会用到；与请求之间隔一个防抖） */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val items: Flow<PagingData<UserListItem>> = combine(
        params,
        // 空串（刚进页面的初始值）不延迟：否则每个列表都要白等 0.5 秒才发第一次请求
        _query.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }.distinctUntilChanged(),
    ) { params, keyword -> params to keyword }
        .flatMapLatest { (params, keyword) ->
            if (params == null || (params.source.needsMid && params.mid <= 0L)) {
                flowOf(PagingData.empty<UserListItem>())
            } else {
                Pager(
                    PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)
                ) {
                    UserListPagingSource(
                        userInfoRepository = userInfoRepository,
                        source = params.source,
                        mid = params.mid,
                        keyword = keyword.ifBlank { null },
                    )
                }.flow
            }
        }
        .cachedIn(viewModelScope)

    /**
     * 进入页面时调用。
     *
     * 同一个 VM 可能被连续两个列表页复用（导航栈上换 source），所以换来源时必须
     * **清掉搜索词**，否则点赞页会带着投稿页的搜索关键字发请求。
     */
    fun init(source: UserListSource, mid: Long) {
        val current = params.value
        if (current == null || current.source != source || current.mid != mid) {
            _query.value = ""
            params.value = Params(source = source, mid = mid)
        }
    }

    fun onQueryChange(keyword: String) {
        _query.value = keyword
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val SEARCH_DEBOUNCE_MS = 500L
    }
}
