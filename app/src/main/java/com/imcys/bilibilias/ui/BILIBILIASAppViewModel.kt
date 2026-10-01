package com.imcys.bilibilias.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imcys.bilibilias.data.repository.AppSettingsRepository
import com.imcys.bilibilias.data.repository.QRCodeLoginRepository
import com.imcys.bilibilias.data.repository.UserInfoRepository
import com.imcys.bilibilias.datastore.AppSettings
import com.imcys.bilibilias.datastore.source.UsersDataSource
import com.imcys.bilibilias.network.ApiStatus
import com.imcys.bilibilias.network.AsCookiesStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

class BILIBILIASAppViewModel(
    private val usersDataSource: UsersDataSource,
    private val userInfoRepository: UserInfoRepository,
    private val qrCodeLoginRepository: QRCodeLoginRepository,
    private val asCookiesStorage: AsCookiesStorage,
    private val appSettingsRepository: AppSettingsRepository
) : ViewModel() {
    val appSettings = appSettingsRepository.appSettingsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppSettings.getDefaultInstance()
    )

    val uiState: StateFlow<UIState>
        field = MutableStateFlow<UIState>(UIState.Default)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            appSettingsRepository.appSettingsFlow.collect {
                if (it.knowAboutApp != AppSettings.KnowAboutApp.Know) {
                    uiState.value = UIState.KnowAboutApp
                }
            }
        }
    }

    fun updatePrivacyPolicyAgreement(agreed: AppSettings.AgreePrivacyPolicyState) {
        viewModelScope.launch {
            appSettingsRepository.updatePrivacyPolicyAgreement(agreed)
        }
    }

    fun accountLoginStateError() {

        viewModelScope.launch {
            if (!usersDataSource.isLogin()) return@launch
            uiState.emit(UIState.AccountCheck(true))
            // 检测可用B站账户
            val oldCurrentUser = userInfoRepository.getBILIUserByUid(usersDataSource.getUserId())
            val oldMid = oldCurrentUser?.mid
            userInfoRepository.deleteBILIUserByUid(usersDataSource.getUserId())
            val userList = userInfoRepository.getBILIUserListByMid(oldMid ?: 0)
            if (userList.isEmpty()) {
                // 通知没找到合适的账户平台
                usersDataSource.setUserId(0)
                uiState.emit(UIState.AccountCheck(false))
                delay(1500)
                uiState.emit(UIState.Default)
                return@launch
            }

            // ⚠️ 找到第一个可用账号就必须**立刻停下**（2026-09-15 复审 A-M10）：
            // 原来失败分支会 `setUserId(0)` 且成功后不退出循环 —— 只要最后一个平台账号失效，
            // 前面已经验证成功的账号就会被清成"未登录"，而 `isResult == true` 又会直接 return，
            // 界面回到 Default、实际却处于未登录态（未使用的 `loginCheck@` 标签正是原意）。
            var winner: Long? = null
            for (user in userList) {
                usersDataSource.setUserId(user.id)
                asCookiesStorage.syncDataBaseCookies()
                val loginInfo =
                    qrCodeLoginRepository.getLoginUserInfo(user.loginPlatform).lastOrNull()
                if (loginInfo?.status == ApiStatus.SUCCESS) {
                    winner = user.id
                    break
                }
            }

            if (winner != null) {
                usersDataSource.setUserId(winner)
                asCookiesStorage.syncDataBaseCookies()
                uiState.emit(UIState.Default)
                return@launch
            }

            // 一个可用的都没有
            usersDataSource.setUserId(0)
            uiState.emit(UIState.AccountCheck(false))
            delay(1500)
            uiState.emit(UIState.Default)


        }
    }

    fun onKnowAboutApp() {
        viewModelScope.launch(Dispatchers.IO) {
            appSettingsRepository.updateKnowAboutApp(AppSettings.KnowAboutApp.Know)
            uiState.value = UIState.Default
        }
    }

    fun ontUseTVVoucherInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            usersDataSource.setNotUseBuvid3(true)
            uiState.value = UIState.Default
        }
    }
}