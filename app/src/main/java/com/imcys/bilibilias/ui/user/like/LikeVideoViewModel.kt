package com.imcys.bilibilias.ui.user.like

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imcys.bilibilias.data.repository.UserInfoRepository
import com.imcys.bilibilias.network.NetWorkResult
import com.imcys.bilibilias.network.emptyNetWorkResult
import com.imcys.bilibilias.network.model.user.BILIUserVideoLikeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


enum class LikePageType {
    LIKE,
    COIN
}


class LikeVideoViewModel(
    private val userInfoRepository: UserInfoRepository
) : ViewModel() {

    data class UIState(
        val mid: Long = 0L,
        val currentMediaId: Long = 0L
    )

    private val _uiState = MutableStateFlow(UIState())
    val uiState: StateFlow<UIState> = _uiState.asStateFlow()
    private val _likeVideoList =
        MutableStateFlow<NetWorkResult<BILIUserVideoLikeInfo?>>(emptyNetWorkResult())
    val likeVideoList = _likeVideoList.asStateFlow()

    /** 最近一次加载用的类型：重试时要按同一个类型重拉（2026-09-15 复审 L10） */
    private var lastType: LikePageType = LikePageType.LIKE

    fun initMid(mid: Long, type: LikePageType) {
        lastType = type
        if (mid != _uiState.value.mid) {
            _uiState.value = _uiState.value.copy(mid = mid, currentMediaId = 0L)
            load()
        }
    }

    /** 失败卡片上的「重试」：按当前 mid 与类型重新加载一次 */
    fun retry() {
        load()
    }

    private fun load() {
        val mid = _uiState.value.mid
        if (mid == 0L) return
        viewModelScope.launch {
            when (lastType) {
                LikePageType.LIKE -> userInfoRepository.getLikeVideoList(mid).collect {
                    _likeVideoList.value = it
                }

                LikePageType.COIN -> userInfoRepository.getCoinVideoList(mid).collect {
                    _likeVideoList.value = it
                }
            }
        }
    }


}