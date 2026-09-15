package com.imcys.bilibilias.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imcys.bilibilias.data.model.BILILoginUserModel
import com.imcys.bilibilias.data.repository.AppSettingsRepository
import com.imcys.bilibilias.data.repository.QRCodeLoginRepository
import com.imcys.bilibilias.data.repository.UserInfoRepository
import com.imcys.bilibilias.database.entity.ASSharedCookieEncoding
import com.imcys.bilibilias.database.entity.BILIUserCookiesEntity
import com.imcys.bilibilias.database.entity.BILIUsersEntity
import com.imcys.bilibilias.database.entity.LoginPlatform
import com.imcys.bilibilias.datastore.AppSettings
import com.imcys.bilibilias.datastore.source.UsersDataSource
import com.imcys.bilibilias.network.AsCookiesStorage
import com.imcys.bilibilias.data.download.execution.CookieParsingRules
import com.imcys.bilibilias.network.NetWorkResult
import com.imcys.bilibilias.network.emptyNetWorkResult
import io.ktor.http.Cookie
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CookieLoginViewModel(
    private val qrCodeLoginRepository: QRCodeLoginRepository,
    private val userInfoRepository: UserInfoRepository,
    private val usersDataSource: UsersDataSource,
    private val asCookiesStorage: AsCookiesStorage,
    private val appSettingsRepository: AppSettingsRepository,
) : ViewModel() {


    private val currentCookies = mutableListOf<Cookie>()

    private val _loginUserInfoState =
        MutableStateFlow<NetWorkResult<BILILoginUserModel?>>(emptyNetWorkResult())
    val loginUserInfoState = _loginUserInfoState.asStateFlow()


    fun checkCookies(cookiesStr: String) {
        // ⚠️ 解析交给 CookieParsingRules（纯规则、有单测）：原实现直接
        // `URLDecoder.decode(value, "UTF-8")`，而它遇到不完整/非法的百分号转义
        // （裸 `%`、`%z`、`%2`）会抛 IllegalArgumentException —— 这个方法由输入框的
        // `onValueChange` **每次按键**同步调用、链路无 try/catch，
        // 于是用户每敲一个字符都可能让应用崩掉（2026-09-14 全量审计 H5）。
        // 现在解码失败会退回原串，绝不抛。
        currentCookies.addAll(
            CookieParsingRules.parse(cookiesStr).map { pair ->
                Cookie(
                    name = pair.name,
                    value = pair.value,
                    httpOnly = true,
                    secure = true,
                    domain = "bilibili.com",
                    path = "/"
                )
            }
        )
        viewModelScope.launch {
            asCookiesStorage.updateAllCookies(currentCookies)
            qrCodeLoginRepository.getLoginUserInfo(LoginPlatform.WEB).collect {
                _loginUserInfoState.value = it
            }
        }
    }

    suspend fun saveLoginCookie() {
        val biliLoginUserModel = _loginUserInfoState.value.data
        if (biliLoginUserModel == null) return
        if (biliLoginUserModel.mid == 0L) return
        val oldUserInfo = qrCodeLoginRepository.getBILIUserByMidAndPlatform(
            biliLoginUserModel.mid!!,
            LoginPlatform.WEB,
        )
        val newLoginUserInfo = BILIUsersEntity(
            name = biliLoginUserModel.name ?: "",
            mid = biliLoginUserModel.mid ?: 0L,
            face = biliLoginUserModel.face ?: "",
            level = biliLoginUserModel.level ?: 0,
            vipState = biliLoginUserModel.vipState ?: 0,
            loginPlatform = LoginPlatform.WEB,
            accessToken = null,
            refreshToken = null
        )

        val userId = if (oldUserInfo == null) {
            qrCodeLoginRepository.saveLoginInfo(newLoginUserInfo)
        } else {
            newLoginUserInfo.apply { newLoginUserInfo.id = oldUserInfo.id }
            qrCodeLoginRepository.updateBILIUser(newLoginUserInfo)
            newLoginUserInfo.id
        }

        // 新增Cookie存储
        qrCodeLoginRepository.deleteBILICookiesByUid(userId)

        // 新增
        currentCookies.forEach {
            val cookie = BILIUserCookiesEntity(
                userId = userId,
                name = it.name,
                value = it.value,
                path = it.path,
                secure = it.secure,
                domain = it.domain,
                encoding = ASSharedCookieEncoding.valueOf(it.encoding.name),
                httpOnly = it.httpOnly
            )
            qrCodeLoginRepository.insertBILIUserCookie(cookie)
        }

        usersDataSource.setUserId(userId)
        asCookiesStorage.syncDataBaseCookies()
        appSettingsRepository.updateVideoParsePlatform(AppSettings.VideoParsePlatform.Web)


    }


}