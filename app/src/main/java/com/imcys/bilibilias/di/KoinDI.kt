package com.imcys.bilibilias.di

import androidx.datastore.core.DataStore
import com.imcys.bilibilias.BILIBILIASApplication
import com.imcys.bilibilias.datastore.AppSettings
import com.imcys.bilibilias.datastore.userAppSettingsStore
import com.imcys.bilibilias.download.DownloadExecutor
import com.imcys.bilibilias.download.FfmpegMerger
import com.imcys.bilibilias.download.FileOutputManager
import com.imcys.bilibilias.download.NamingConventionHandler
import com.imcys.bilibilias.download.NewDownloadManager
import com.imcys.bilibilias.download.SubtitleDownloader
import com.imcys.bilibilias.download.VideoInfoFetcher
import com.imcys.bilibilias.ui.BILIBILIASAppViewModel
import com.imcys.bilibilias.ui.analysis.AnalysisViewModel
import com.imcys.bilibilias.ui.download.DownloadViewModel
import com.imcys.bilibilias.ui.event.playvoucher.PlayVoucherErrorViewModel
import com.imcys.bilibilias.ui.event.requestFrequent.RequestFrequentViewModel
import com.imcys.bilibilias.ui.home.HomeViewModel
import com.imcys.bilibilias.ui.login.CookieLoginViewModel
import com.imcys.bilibilias.ui.login.QRCodeLoginViewModel
import com.imcys.bilibilias.ui.setting.SettingViewModel
import com.imcys.bilibilias.ui.setting.contract.NamingConventionViewModel
import com.imcys.bilibilias.ui.setting.developer.LineConfigViewModel
import com.imcys.bilibilias.ui.setting.layout.LayoutTypesetViewModel
import com.imcys.bilibilias.ui.setting.platform.ParsePlatformViewModel
import com.imcys.bilibilias.ui.setting.storage.StorageManagementViewModel
import com.imcys.bilibilias.ui.tools.frame.FrameExtractorViewModel
import com.imcys.bilibilias.ui.tools.parser.WebParserViewModel
import com.imcys.bilibilias.ui.user.UserViewModel
import com.imcys.bilibilias.ui.user.bangumifollow.BangumiFollowViewModel
import com.imcys.bilibilias.ui.user.folder.UserFolderViewModel
import com.imcys.bilibilias.ui.user.history.UserPlayHistoryViewModel
import com.imcys.bilibilias.ui.user.like.LikeVideoViewModel
import com.imcys.bilibilias.ui.user.work.WorkListViewModel
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module


val appModule = module {
    single { androidContext().assets }
    single { androidContext().contentResolver }
    single<DataStore<AppSettings>> {
        // 必须复用 userAppSettingsStore —— 也就是 AppSettingsRepository 使用的同一个实例。
        //
        // 原实现用 DataStoreFactory.create 又建了一个指向同一文件 app_setting.pb 的
        // 新实例。两个实例各自持有独立的内存缓存：写入方（Repository）改了文件，
        // 读取方（StorageUtil 通过 Koin inject 拿到的是本实例）永远读到旧值。
        // 具体表现就是「SAF 授权成功后 downloadUri 依然为空」——
        // 权限提示永不消失、已下载音视频大小恒为 0 B。
        androidContext().userAppSettingsStore
    }
    viewModelOf(::HomeViewModel)
    viewModelOf(::HomeViewModel)
    viewModelOf(::QRCodeLoginViewModel)
    viewModelOf(::BILIBILIASAppViewModel)
    viewModelOf(::UserViewModel)
    viewModelOf(::AnalysisViewModel)
    viewModelOf(::DownloadViewModel)
    viewModelOf(::PlayVoucherErrorViewModel)
    viewModelOf(::WorkListViewModel)
    viewModelOf(::BangumiFollowViewModel)
    viewModelOf(::UserFolderViewModel)
    viewModelOf(::LikeVideoViewModel)
    viewModelOf(::SettingViewModel)
    viewModelOf(::LayoutTypesetViewModel)
    viewModelOf(::UserPlayHistoryViewModel)
    viewModelOf(::FrameExtractorViewModel)
    viewModelOf(::CookieLoginViewModel)
    viewModelOf(::StorageManagementViewModel)
    viewModelOf(::NamingConventionViewModel)
    viewModelOf(::RequestFrequentViewModel)
    viewModelOf(::LineConfigViewModel)
    viewModelOf(::WebParserViewModel)
    viewModelOf(::ParsePlatformViewModel)

    single { VideoInfoFetcher(get(), get(), get()) }
    single { FileOutputManager(androidApplication()) }
    single { DownloadExecutor(get(qualifier = named("DownloadHttpClient")), get()) }
    single { FfmpegMerger(androidApplication(),get()) }
    single { NamingConventionHandler(get()) }
    single { SubtitleDownloader(get(), get(), androidApplication()) }

    single {
        NewDownloadManager(
            context = androidApplication(),
            downloadTaskRepository = get(),
            videoInfoRepository = get(),
            httpClient = get(qualifier = named("DownloadHttpClient")),
            okHttpClient = get(),
            appSettingsRepository = get(),
            videoInfoFetcher = get(),
            fileOutputManager = get(),
            downloadExecutor = get(),
            ffmpegMerger = get(),
            namingConventionHandler = get(),
            subtitleDownloader = get()
        )
    }
}