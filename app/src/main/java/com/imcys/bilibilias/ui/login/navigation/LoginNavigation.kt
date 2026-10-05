package com.imcys.bilibilias.ui.login.navigation

import androidx.navigation3.runtime.NavKey
import com.imcys.bilibilias.database.entity.LoginPlatform
import kotlinx.serialization.Serializable

/**
 * 「登录」页的两个 Tab（#5：原来 `LoginRoute` + `QRCodeLoginRoute` + `CookeLoginRoute`
 * 三个路由，现在是同一个页面的两个 Tab）。
 */
enum class LoginTab {
    QR_CODE,
    COOKIE,
}

/**
 * 合并后的登录路由。
 *
 * 字段与原 `QRCodeLoginRoute` 一一对应（扫码登录要按来源平台请求、登录成功后去路也不同）；
 * `initialTab` 让"要输 Cookie"的入口能直接落在 Cookie Tab 上。
 */
@Serializable
data class LoginRoute(
    val defaultLoginPlatform: LoginPlatform = LoginPlatform.WEB,
    val isFromRoam: Boolean = false,
    val isFromAnalysis: Boolean = false,
    val initialTab: LoginTab = LoginTab.QR_CODE,
) : NavKey
