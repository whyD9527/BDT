package com.imcys.bilibilias

import android.app.Application
import com.imcys.bilibilias.common.data.CommonBuildConfig
import com.imcys.bilibilias.data.di.repositoryModule
import com.imcys.bilibilias.database.di.databaseModule
import com.imcys.bilibilias.datastore.di.dataStoreModule
import com.imcys.bilibilias.di.appModule
import com.imcys.bilibilias.network.di.netWorkModule
import com.imcys.bilibilias.ui.error.AppCrashHandler
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class BILIBILIASApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 全局异常捕获（⚠️ 必须放在 Koin 之前：Koin 初始化本身崩溃时也要能记下来。
        // 它只依赖 Application/Context，不碰 DI —— 见 AppCrashHandler 的注释）
        AppCrashHandler.instance.init(this)
        initBuildConfig()
        // Koin依赖注入
        startKoin {
            androidContext(this@BILIBILIASApplication)
            modules(
                dataStoreModule,
                netWorkModule,
                repositoryModule,
                databaseModule,
                appModule,
            )
        }
    }

    private fun initBuildConfig() {
        CommonBuildConfig.enabledAnalytics = BuildConfig.ENABLED_ANALYTICS
    }
}