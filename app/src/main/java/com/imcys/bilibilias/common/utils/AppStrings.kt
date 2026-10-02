package com.imcys.bilibilias.common.utils

import android.app.Application
import androidx.annotation.StringRes

/**
 * 给**拿不到 Context 的 ViewModel** 取字符串用的小包装（i18n 批次 E7c）。
 *
 * ## 为什么不直接把 `Application` 注进 VM
 * 1. 本项目现有代码里，需要 `Application` 的组件都是**显式**传
 *    `androidApplication()`（`FileOutputManager` / `FfmpegMerger` / `NewDownloadManager`…），
 *    而 `viewModelOf(::XxxViewModel)` 没有"直接注入 `Application`"的先例 ——
 *    它在 Koin 图里能不能解析是版本细节，不值得赌（本地没有 JDK，编译只能靠 CI，一次来回 7 分钟）；
 * 2. 显式依赖一个"只干 `getString`"的小对象，意图更清楚：ViewModel 只是要文案，
 *    不该顺手拿到整个 `Application`。
 *
 * 注册方式（KoinDI）：`single { AppStrings(androidApplication()) }` —— 与上面那些组件同一种写法。
 */
class AppStrings(private val application: Application) {

    /** 与 `Context.getString(resId, *args)` 等价 */
    fun get(@StringRes resId: Int, vararg formatArgs: Any): String =
        application.getString(resId, *formatArgs)
}
