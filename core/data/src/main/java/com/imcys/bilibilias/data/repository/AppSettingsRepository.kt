package com.imcys.bilibilias.data.repository

import androidx.datastore.core.DataStore
import com.imcys.bilibilias.data.download.segmented.SegmentedDownloadPlan
import com.imcys.bilibilias.database.entity.LoginPlatform
import com.imcys.bilibilias.database.entity.download.MediaContainer
import com.imcys.bilibilias.datastore.AppSettings
import com.imcys.bilibilias.datastore.copy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class AppSettingsRepository(
    private val dataStore: DataStore<AppSettings>,
) {
    private val TAG: String = "AppSettingsRepository"

    val appSettingsFlow: Flow<AppSettings> = dataStore.data

    // ------------------------------------------------------------------
    // 多线程分片下载（开关 + 并发数）
    //
    // proto 里这两个字段是 `optional`，"没设置过"与"设置成 false/0"是两回事：
    // 前者要按默认值走（默认开、并发 4），后者才是用户的真实意愿。
    // 解析（含夹取）统一交给 SegmentedDownloadPlan.resolve*，只留一处真相源、且可单测。
    // ------------------------------------------------------------------

    /** 分片下载开关；**用户没设置过时为 true** */
    val segmentedDownloadEnabledFlow: Flow<Boolean> = appSettingsFlow.map { settings ->
        SegmentedDownloadPlan.resolveEnabled(
            if (settings.hasSegmentedDownloadEnabled()) settings.segmentedDownloadEnabled else null
        )
    }

    /** 分片并发数（已夹到 2..8）；**用户没设置过时为 4** */
    val segmentedDownloadConcurrencyFlow: Flow<Int> = appSettingsFlow.map { settings ->
        SegmentedDownloadPlan.resolveConcurrency(
            if (settings.hasSegmentedDownloadConcurrency()) settings.segmentedDownloadConcurrency
            else null
        )
    }

    suspend fun isSegmentedDownloadEnabled(): Boolean = segmentedDownloadEnabledFlow.first()

    suspend fun getSegmentedDownloadConcurrency(): Int = segmentedDownloadConcurrencyFlow.first()

    suspend fun updateSegmentedDownloadEnabled(enabled: Boolean) {
        dataStore.updateData { currentSettings ->
            currentSettings.copy {
                segmentedDownloadEnabled = enabled
            }
        }
    }

    suspend fun updateSegmentedDownloadConcurrency(concurrency: Int) {
        // 写进去之前先夹一次：免得非法值落到磁盘上，之后每次读都得靠调用方兜
        val safe = SegmentedDownloadPlan.resolveConcurrency(concurrency)
        dataStore.updateData { currentSettings ->
            currentSettings.copy {
                segmentedDownloadConcurrency = safe
            }
        }
    }

    // 获取当前平台类型
    suspend fun getVideoParsePlatform(): AppSettings.VideoParsePlatform =
        appSettingsFlow.first().videoParsePlatform

    // 同意了隐私政策
    suspend fun hasAgreedPrivacyPolicy(): Boolean {
        val currentSettings = dataStore.data.first()
        return currentSettings.agreePrivacyPolicy == AppSettings.AgreePrivacyPolicyState.Agreed
    }


    // 添加更新隐私政策同意状态的方法
    suspend fun updatePrivacyPolicyAgreement(agreed: AppSettings.AgreePrivacyPolicyState) {
        dataStore.updateData { currentSettings ->
            currentSettings.toBuilder()
                .setAgreePrivacyPolicy(agreed)
                .build()
        }
    }


    // 添加更新隐私政策同意状态的方法
    suspend fun updateKnowAboutApp(knowAboutApp: AppSettings.KnowAboutApp) {
        dataStore.updateData { currentSettings ->
            currentSettings.toBuilder()
                .setKnowAboutApp(knowAboutApp)
                .build()
        }
    }

    suspend fun updateRoamEnabledState(enabled: Boolean) {
        dataStore.updateData { currentSettings ->
            currentSettings.copy {
                enabledRoam = enabled
            }
        }
    }

    suspend fun updateEnabledDynamicColor(enabled: Boolean) {
        dataStore.updateData { currentSettings ->
            currentSettings.copy {
                enabledDynamicColor = enabled
            }
        }
    }

    suspend fun updateClipboardAutoHandling(enabled: Boolean) {
        dataStore.updateData { currentSettings ->
            currentSettings.copy {
                enabledClipboardAutoHandling = enabled
            }
        }
    }

    suspend fun updateLastSkipUpdateVersionCode(versionCode: Int) {
        dataStore.updateData { currentSettings ->
            currentSettings.toBuilder()
                .setLastSkipUpdateVersionCode(versionCode)
                .build()
        }
    }

    suspend fun asyncHomeLayoutTypesetList(): List<AppSettings.HomeLayoutItem> {
        val defaultList = createDefaultHomeLayoutItems()
        val existingList = dataStore.data.first().homeLayoutTypesetList.toMutableList()

        return if (existingList.isEmpty()) {
            dataStore.updateData { currentSettings ->
                currentSettings.toBuilder()
                    .clearHomeLayoutTypeset()
                    .addAllHomeLayoutTypeset(defaultList)
                    .build()
            }
            defaultList
        } else {
            val existingTypes = existingList.map { it.type }.toSet()
            val missingItems = defaultList.filterNot { it.type in existingTypes }
            existingList.addAll(missingItems)
            existingList
        }
    }

    private fun createDefaultHomeLayoutItems(): List<AppSettings.HomeLayoutItem> {
        val defaultTypes = listOf(
            AppSettings.HomeLayoutType.Banner,
            AppSettings.HomeLayoutType.Announcement,
            AppSettings.HomeLayoutType.UpdateInfo,
            AppSettings.HomeLayoutType.Tools,
            AppSettings.HomeLayoutType.DownloadList
        )

        return defaultTypes.map { type ->
            AppSettings.HomeLayoutItem.newBuilder()
                .setType(type)
                .setIsHidden(false)
                .build()
        }
    }

    suspend fun updateHomeLayoutTypesetList(newList: List<AppSettings.HomeLayoutItem>) {
        dataStore.updateData { currentSettings ->
            currentSettings.toBuilder()
                .clearHomeLayoutTypeset()
                .addAllHomeLayoutTypeset(newList)
                .build()
        }
    }


    suspend fun updateLastBulletinContent(content: String) {
        dataStore.updateData { currentSettings ->
            currentSettings.toBuilder()
                .setLastBulletinContent(content)
                .build()
        }
    }

    suspend fun saveDownloadSAFUriString(uriString: String) {
        dataStore.updateData { currentSettings ->
            currentSettings.toBuilder()
                .setDownloadUri(uriString)
                .build()
        }
    }

    suspend fun updateEpisodeListMode(it: AppSettings.EpisodeListMode) {
        dataStore.updateData { currentSettings ->
            currentSettings.copy {
                episodeListMode = it
            }
        }
    }

    suspend fun updateVideoNamingRule(rule: String) {
        dataStore.updateData { currentSettings ->
            currentSettings.copy {
                videoNamingRule = rule
            }
        }
    }

    suspend fun updateBangumiNamingRule(rule: String) {
        dataStore.updateData { currentSettings ->
            currentSettings.copy {
                bangumiNamingRule = rule
            }
        }
    }

    suspend fun updateLineHost(lineHost: String) {
        dataStore.updateData { currentSettings ->
            currentSettings.copy {
                this.biliLineHost = lineHost
            }
        }
    }

    // 存储使用工具记录
    suspend fun updateUseToolRecord(toolName: String) {
        dataStore.updateData { currentSettings ->
            val historyList = currentSettings.useToolHistoryList.toMutableList()
            if (historyList.size > 10) {
                historyList.removeLastOrNull()
            }
            historyList.add(0, toolName)
            // 去重
            val distinctList = historyList.distinct()
            currentSettings.toBuilder()
                .clearUseToolHistory()
                .addAllUseToolHistory(distinctList)
                .build()
        }
    }

    suspend fun updateVideoParsePlatform(platform: AppSettings.VideoParsePlatform) {
        dataStore.updateData { currentSettings ->
            currentSettings.copy {
                videoParsePlatform = platform
            }
        }
    }

    suspend fun updateDownloadSortType(sortType: AppSettings.DownloadSortType) {
        dataStore.updateData { currentSettings ->
            currentSettings.copy {
                downloadSortType = sortType
            }
        }
    }

    suspend fun updateUseVideoContainer(
        videoContainer: MediaContainer
    ) {
        dataStore.updateData { currentSettings ->
            currentSettings.copy {
                useVideoContainer = videoContainer.extension
            }
        }
    }

    suspend fun updateUseAudioContainer(
        audioContainer: MediaContainer
    ) {
        dataStore.updateData { currentSettings ->
            currentSettings.copy {
                useAudioContainer = audioContainer.extension
            }
        }
    }

    /**
     * 把磁盘上的扩展名字符串翻译回容器枚举。
     *
     * ⚠️ 原写法是 `MediaContainer.entries.first { it.extension == extension }` ——
     * 一旦扩展名为空或是个不认识的历史脏值（例如老版本 pb 里字段缺失、或用户回滚版本），
     * `first` 会抛 `NoSuchElementException`，而它在 `AnalysisViewModel` 的 settings 收集协程里
     * **没有任何捕获** → 进解析页直接崩（2026-09-14 全量审计 H7）。
     *
     * 现在取不到就**回落默认容器**：设置项读不出来时应该退化成"老样子"，而不是崩。
     */
    fun storeMediaContainerFromExtension(extension: String): MediaContainer =
        MediaContainer.entries.firstOrNull { it.extension.equals(extension, ignoreCase = true) }
            ?: MediaContainer.MP4
}


fun AppSettings.VideoParsePlatform.getDescription(): String = this.name

fun AppSettings.VideoParsePlatform.toDatabaseType(): LoginPlatform = when (this) {
    AppSettings.VideoParsePlatform.Web -> LoginPlatform.WEB
    AppSettings.VideoParsePlatform.TV -> LoginPlatform.TV
    AppSettings.VideoParsePlatform.Mobile -> LoginPlatform.MOBILE
    else -> LoginPlatform.WEB
}

fun LoginPlatform.toDataStoreType() = when (this) {
    LoginPlatform.WEB -> AppSettings.VideoParsePlatform.Web
    LoginPlatform.MOBILE -> AppSettings.VideoParsePlatform.Mobile
    LoginPlatform.TV -> AppSettings.VideoParsePlatform.TV
}


fun AppSettings.HomeLayoutType.getDescription(): String = when (this) {
    AppSettings.HomeLayoutType.Banner -> "轮播图"
    AppSettings.HomeLayoutType.Announcement -> "公告信息"
    AppSettings.HomeLayoutType.UpdateInfo -> "更新信息"
    AppSettings.HomeLayoutType.Tools -> "工具列表"
    AppSettings.HomeLayoutType.DownloadList -> "下载列表"
    else -> this.name
}