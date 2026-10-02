package com.imcys.bilibilias.common.event

import com.imcys.bilibilias.common.base.crash.AppException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

object LoginError

// 登录校验异常
private val _loginErrorChannel = Channel<LoginError>(Channel.UNLIMITED)
val loginErrorChannel = _loginErrorChannel.receiveAsFlow()

fun sendLoginErrorEvent() {
    _loginErrorChannel.trySend(LoginError)
}

// 应用异常处理
private val _appErrorHandleChannel = Channel<AppException>(Channel.UNLIMITED)
val appErrorHandleChannel = _appErrorHandleChannel.receiveAsFlow()

fun sendAppErrorEvent(appException: AppException) {
    _appErrorHandleChannel.trySend(appException)
}


data class AnalysisEvent(
    val analysisText: String,
)

// 分析事件处理
private val _analysisHandleChannel = Channel<AnalysisEvent>(Channel.UNLIMITED)
val analysisHandleChannel = _analysisHandleChannel.receiveAsFlow()

fun sendAnalysisEvent(analysisEvent: AnalysisEvent) {
    _analysisHandleChannel.trySend(analysisEvent)
}


object PlayVoucherError

// 播放接口风控异常
private val _playVoucherErrorChannel = Channel<PlayVoucherError>(Channel.UNLIMITED)
val playVoucherErrorChannel = _playVoucherErrorChannel.receiveAsFlow()
fun sendPlayVoucherErrorEvent() {
    _playVoucherErrorChannel.trySend(PlayVoucherError)
}


// 请求频繁事件
data class RequestFrequentEvent(
    val url: String,
)

// 请求频繁事件处理
private val _requestFrequentHandleChannel = Channel<RequestFrequentEvent>(Channel.UNLIMITED)
val requestFrequentHandleChannel = _requestFrequentHandleChannel.receiveAsFlow()
fun sendRequestFrequentEvent(url: String) {
    _requestFrequentHandleChannel.trySend(RequestFrequentEvent(url))
}

// 刷新账户事件
data object UpdateAccountChannel

private val _updateAccountChannel = Channel<UpdateAccountChannel>(Channel.UNLIMITED);
val updateAccountChannel = _updateAccountChannel.receiveAsFlow()

fun sendUpdateAccountEvent() {
    _updateAccountChannel.trySend(UpdateAccountChannel)
}

/**
 * 「启动目标」（B3 点通知直达）：从通知/外部 Intent 进来时，App 应该把用户送到哪个页面。
 *
 * 为什么放在 `:core:common` 的事件通道里：`MainActivity`（解析 Intent 的地方）与
 * 导航层 `BILIBILAISNavDisplay`（决定去哪个页面的地方）是两个不同的 Composable 作用域，
 * 现有几个跨作用域跳转（解析事件 / 风控页 / 请求频繁）走的都是同一条 `Channel` 通道 ——
 * 沿用同一套写法，不另造机制。
 */
enum class StartTarget {
    /** 下载管理页（缓存通知点进来的默认落点） */
    DOWNLOAD_LIST,
}

private val _startTargetChannel = Channel<StartTarget>(Channel.UNLIMITED)
val startTargetChannel = _startTargetChannel.receiveAsFlow()

fun sendStartTargetEvent(target: StartTarget) {
    _startTargetChannel.trySend(target)
}