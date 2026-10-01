package com.imcys.bilibilias.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imcys.bilibilias.data.repository.AppSettingsRepository
import com.imcys.bilibilias.data.repository.UserInfoRepository
import com.imcys.bilibilias.database.dao.BILIUserCookiesDao
import com.imcys.bilibilias.database.dao.BILIUsersDao
import com.imcys.bilibilias.datastore.AppSettings
import com.imcys.bilibilias.datastore.source.UsersDataSource
import com.imcys.bilibilias.network.AsCookiesStorage
import com.imcys.bilibilias.network.model.video.Subtitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch

class SettingViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    private val usersDataSource: UsersDataSource,
    private val biliUsersDao: BILIUsersDao,
    private val biliUserCookiesDao: BILIUserCookiesDao,
    private val userInfoRepository: UserInfoRepository,
    private val asCookiesStorage: AsCookiesStorage
) : ViewModel() {

    val appSettings = appSettingsRepository.appSettingsFlow

    private val _uiState = MutableStateFlow(SettingUIState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            usersDataSource.users.collect {
                var mid = 0L
                if (it.currentUserId != 0L) {
                    val user = biliUsersDao.getBILIUserByUid(it.currentUserId)
                    mid = user?.mid ?: 0L
                }
                _uiState.value = _uiState.value.copy(
                    isLogin = it.currentUserId != 0L,
                    currentMid = mid
                )
            }
        }
    }

    fun updatePrivacyPolicyAgreement(agreed: AppSettings.AgreePrivacyPolicyState) {
        viewModelScope.launch {
            appSettingsRepository.updatePrivacyPolicyAgreement(agreed)
        }
    }

    fun updateEnabledDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.updateEnabledDynamicColor(enabled)
        }
    }

    fun updateClipboardAutoHandling(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.updateClipboardAutoHandling(enabled)
        }
    }

    /** 多线程分片下载开关 */
    fun updateSegmentedDownloadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.updateSegmentedDownloadEnabled(enabled)
        }
    }

    /** 分片并发数（仓储侧会夹到 2..8） */
    fun updateSegmentedDownloadConcurrency(concurrency: Int) {
        viewModelScope.launch {
            appSettingsRepository.updateSegmentedDownloadConcurrency(concurrency)
        }
    }

    suspend fun logout() {
        val user = biliUsersDao.getBILIUserByUid(usersDataSource.getUserId())
        user?.let {
            val cookies = biliUserCookiesDao.getBILIUserCookiesByUid(it.id)

            // ⚠️ 2026-09-15 复审 A-M9：本地清理必须**无条件**执行，不能挂在"拿得到 bili_jct"上。
            // 原写法是 `…?.value?.apply { 删 Cookie/删用户/setUserId(0) } ?: return` ——
            // TV/非常规登录（或 cookie 缺失）时取不到 bili_jct 就直接 return，
            // 本地一条都不清，而 UI 那边照样关弹窗回首页 → 用户以为退出了，其实还是登录态。
            val biliJct = cookies.firstOrNull { cookie -> cookie.name == "bili_jct" }?.value

            usersDataSource.setUserId(0L)
            biliUserCookiesDao.deleteBILICookiesByUid(user.id)
            biliUsersDao.deleteBILIUserByUid(user.id)

            _uiState.value = _uiState.value.copy(
                isLogin = false,
                currentMid = 0
            )

            asCookiesStorage.clearCookies()
            asCookiesStorage.syncDataBaseCookies()

            // 服务端注销是"尽力而为"：没有 bili_jct（或接口失败）都不该影响本地已完成的登出
            if (biliJct != null) {
                runCatching { userInfoRepository.logout(biliJct).last() }
            }
        }
    }

}