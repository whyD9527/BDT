package com.imcys.bilibilias.data.repository

import android.util.Log
import com.imcys.bilibilias.data.model.BILILoginUserModel
import com.imcys.bilibilias.network.utils.WebiTokenUtils
import com.imcys.bilibilias.database.dao.BILIUserCookiesDao
import com.imcys.bilibilias.database.dao.BILIUsersDao
import com.imcys.bilibilias.database.entity.BILIUserCookiesEntity
import com.imcys.bilibilias.database.entity.BILIUsersEntity
import com.imcys.bilibilias.database.entity.LoginPlatform
import com.imcys.bilibilias.network.FlowNetWorkResult
import com.imcys.bilibilias.network.mapData
import com.imcys.bilibilias.network.model.QRCodeInfo
import com.imcys.bilibilias.network.model.QRCodePollInfo
import com.imcys.bilibilias.network.service.BILIBILITVAPIService
import com.imcys.bilibilias.network.service.BILIBILIWebAPIService
import kotlinx.coroutines.flow.map

class QRCodeLoginRepository(
    private val webApiService: BILIBILIWebAPIService,
    private val tvAPIService: BILIBILITVAPIService,
    private val biliUsersDao: BILIUsersDao,
    private val biliUserCookiesDao: BILIUserCookiesDao
) {
    suspend fun getLoginQRCodeInfo(loginPlatform: LoginPlatform): FlowNetWorkResult<QRCodeInfo> {
        return when (loginPlatform) {
            LoginPlatform.WEB -> webApiService.qrcodeGenerate()
            LoginPlatform.MOBILE,
            LoginPlatform.TV -> tvAPIService.qrcodeGenerate().map { networkResult ->
                networkResult.mapData { tvQRCodeInfo, apiResponse ->
                    tvQRCodeInfo?.let {
                        QRCodeInfo(
                            qrcodeKey = it.authCode,
                            url = it.url
                        )
                    }
                }
            }
        }
    }

    /**
     * 获取扫码状态
     */
    suspend fun getQRScanState(
        loginPlatform: LoginPlatform,
        qrcodeKey: String
    ): FlowNetWorkResult<QRCodePollInfo> {
        return when (loginPlatform) {
            LoginPlatform.WEB -> webApiService.qrcodePoll(qrcodeKey).map { networkResult ->
                networkResult.mapData { tvQRCodeInfo, apiResponse ->
                    // ⚠️ 不要再把整个响应头打进 logcat（2026-09-15 复审 L18）：
                    // 扫码轮询的响应头里带 `Set-Cookie`，那是登录凭据。
                    Log.d("networkResult", "getLoginQRCodeInfo: code=${apiResponse?.code}")
                    tvQRCodeInfo
                }
            }

            LoginPlatform.MOBILE,
            LoginPlatform.TV -> tvAPIService.qrcodePoll(qrcodeKey).map { networkResult ->
                networkResult.mapData { tvQRCodePollInfo, apiResponse ->
                    tvQRCodePollInfo?.let {
                        QRCodePollInfo(
                            code = apiResponse?.code ?: 0,
                            message = apiResponse?.message ?: "",
                            refreshToken = it.refreshToken,
                            accessToken = it.accessToken,
                            url = "",
                            timestamp = it.expiresIn,
                            cookieInfo = it.cookieInfo
                        )
                    }
                }
            }
        }
    }


    suspend fun getLoginUserInfo(
        loginPlatform: LoginPlatform,
        accessKey: String? = null
    ): FlowNetWorkResult<BILILoginUserModel> {
        return when (loginPlatform) {
            LoginPlatform.WEB -> webApiService.getLoginUserInfo().map { networkResult ->
                networkResult.mapData { loginInfo, apiResponse ->
                    if (WebiTokenUtils.key == null) {
                        // 检测Webi
                        loginInfo?.wbiImg?.let { WebiTokenUtils.setKey(it) }
                    }
                    BILILoginUserModel(
                        face = loginInfo?.face,
                        level = loginInfo?.levelInfo?.currentLevel,
                        name = loginInfo?.uname,
                        mid = loginInfo?.mid,
                        vipState = loginInfo?.vip?.status,
                    )
                }
            }

            LoginPlatform.MOBILE,
            LoginPlatform.TV -> tvAPIService.getLoginUserInfo(accessKey ?: "")
                .map { networkResult ->
                    networkResult.mapData { loginInfo, apiResponse ->
                        BILILoginUserModel(
                            face = loginInfo?.face,
                            mid = loginInfo?.mid,
                            level = loginInfo?.level,
                            name = loginInfo?.name,
                            vipState = loginInfo?.vip?.status,
                        )
                    }
                }
        }

    }

    /**
     * 校验某个平台账号现在还管不管用。
     *
     * ⚠️ 2026-09-15 复审 A-M2：两个 service 的 `checkLoginUserInfo` 原先把响应**剥掉包装层**
     * 直接解成用户模型，于是真实字段（在 `data` 里）永远解不出来、`code != 0` 也看不出来，
     * `Result.isSuccess` 恒为 true —— 失效/被风控的账号永远不会被清理，用户也不会被重新登录。
     * 现在按 `code` 判定：非 0 一律当成**失败**（`runCatching` 转成 Result.failure）。
     */
    suspend fun checkLoginUserInfo(
        loginPlatform: LoginPlatform,
        accessKey: String? = null
    ): Result<BILILoginUserModel> {
        return runCatching {
            when (loginPlatform) {
                LoginPlatform.WEB -> {
                    val response = webApiService.checkLoginUserInfo()
                    if (response.code != 0) {
                        error("登录信息获取失败: ${response.message ?: response.code}")
                    }
                    val loginInfo = response.data ?: error("登录信息为空")
                    if (WebiTokenUtils.key == null) {
                        // 检测Webi
                        loginInfo.wbiImg?.let { WebiTokenUtils.setKey(it) }
                    }
                    BILILoginUserModel(
                        face = loginInfo.face,
                        level = loginInfo.levelInfo?.currentLevel,
                        name = loginInfo.uname,
                        mid = loginInfo.mid,
                        vipState = loginInfo.vip?.status,
                    )
                }

                LoginPlatform.MOBILE,
                LoginPlatform.TV -> {
                    val response = tvAPIService.checkLoginUserInfo(accessKey ?: "")
                    if (response.code != 0) {
                        error("登录信息获取失败: ${response.message ?: response.code}")
                    }
                    val loginInfo = response.data ?: error("登录信息为空")
                    BILILoginUserModel(
                        face = loginInfo.face,
                        mid = loginInfo.mid,
                        level = loginInfo.level,
                        name = loginInfo.name,
                        vipState = loginInfo.vip?.status,
                    )
                }
            }
        }
    }


    suspend fun saveLoginInfo(biliUsersEntity: BILIUsersEntity) =
        biliUsersDao.insertBILIUser(biliUsersEntity)

    suspend fun updateBILIUser(biliUsersEntity: BILIUsersEntity) =
        biliUsersDao.updateBILIUser(biliUsersEntity)

    suspend fun getBILIUserByMidAndPlatform(mid: Long, loginPlatform: LoginPlatform) =
        biliUsersDao.getBILIUserByMidAndPlatform(mid, loginPlatform)

    suspend fun deleteBILICookiesByUid(uid: Long) =
        biliUserCookiesDao.deleteBILICookiesByUid(uid)

    suspend fun insertBILIUserCookie(biliUserCookiesEntity: BILIUserCookiesEntity) =
        biliUserCookiesDao.insertBILIUserCookie(biliUserCookiesEntity)

    suspend fun getBILIUserListByUid(uid: Long) =
        biliUsersDao.getBILIUserListByUid(uid)
}