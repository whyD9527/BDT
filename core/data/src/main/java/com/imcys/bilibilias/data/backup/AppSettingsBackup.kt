package com.imcys.bilibilias.data.backup

import com.imcys.bilibilias.datastore.AppSettings
import org.json.JSONObject

/**
 * 设置备份：导出成 JSON、以后导回来。
 *
 * ## 为什么需要它（2026-10-02）
 * 用户一天之内重装了 3 次应用，每次都丢：命名规则、解析平台、画质/编码偏好、容器格式、
 * 多线程分片设置、动态取色、剪贴板自动化…… 这些都得重新点一遍。
 *
 * ## 设计取舍
 * - **只备"用户调过、重装会丢"的字段**：隐私同意状态、公告已读、`download_uri`（SAF 授权，
 *   本身就跨不过重装）、首页布局、工具历史都不备（要么是设备相关、要么有默认值即可）；
 * - **用 `org.json` 而不是 kotlinx.serialization 的 `@Serializable`**：`core:data` 没上
 *   序列化插件，为一个 DTO 引插件不划算；`org.json` 在 Android 运行时自带、单测里用真实现
 *   （`libs.json`）就行；
 * - **枚举存名字**（`"TV"` / `"DownloadSort_TitleAsc"`）而不是序号：以后枚举加值/调顺序也不会读错；
 * - **恢复时只覆盖非空项**（[AppSettingsBackupRules.overlay]）：一份旧备份不能把用户后来设的东西抹掉。
 */
data class AppSettingsBackup(
    val schema: Int = CURRENT_SCHEMA,
    val exportedAt: String = "",
    val appVersion: String = "",
    val videoNamingRule: String? = null,
    val bangumiNamingRule: String? = null,
    val videoParsePlatform: String? = null,
    val episodeListMode: String? = null,
    val downloadSortType: String? = null,
    val useVideoContainer: String? = null,
    val useAudioContainer: String? = null,
    val biliLineHost: String? = null,
    val enabledDynamicColor: Boolean? = null,
    val enabledClipboardAutoHandling: Boolean? = null,
    val enabledRoam: Boolean? = null,
    val segmentedDownloadEnabled: Boolean? = null,
    val segmentedDownloadConcurrency: Int? = null,
    val wifiOnlyDownload: Boolean? = null,
    val downloadSpeedLimitKbps: Int? = null,
    val skipDownloaded: Boolean? = null,
) {

    fun toJson(): String {
        val o = JSONObject()
        o.put("schema", schema)
        o.put("exportedAt", exportedAt)
        o.put("appVersion", appVersion)
        videoNamingRule?.let { o.put("videoNamingRule", it) }
        bangumiNamingRule?.let { o.put("bangumiNamingRule", it) }
        videoParsePlatform?.let { o.put("videoParsePlatform", it) }
        episodeListMode?.let { o.put("episodeListMode", it) }
        downloadSortType?.let { o.put("downloadSortType", it) }
        useVideoContainer?.let { o.put("useVideoContainer", it) }
        useAudioContainer?.let { o.put("useAudioContainer", it) }
        biliLineHost?.let { o.put("biliLineHost", it) }
        enabledDynamicColor?.let { o.put("enabledDynamicColor", it) }
        enabledClipboardAutoHandling?.let { o.put("enabledClipboardAutoHandling", it) }
        enabledRoam?.let { o.put("enabledRoam", it) }
        segmentedDownloadEnabled?.let { o.put("segmentedDownloadEnabled", it) }
        segmentedDownloadConcurrency?.let { o.put("segmentedDownloadConcurrency", it) }
        wifiOnlyDownload?.let { o.put("wifiOnlyDownload", it) }
        downloadSpeedLimitKbps?.let { o.put("downloadSpeedLimitKbps", it) }
        skipDownloaded?.let { o.put("skipDownloaded", it) }
        return o.toString(2)
    }

    companion object {
        const val CURRENT_SCHEMA = 1

        /** 解析失败（不是 JSON / 不是备份文件）返回 null —— 调用方据此提示"这不是备份文件" */
        fun fromJson(text: String): AppSettingsBackup? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            return AppSettingsBackup(
                schema = o.optInt("schema", 0),
                exportedAt = o.optString("exportedAt", ""),
                appVersion = o.optString("appVersion", ""),
                videoNamingRule = o.optStringOrNull("videoNamingRule"),
                bangumiNamingRule = o.optStringOrNull("bangumiNamingRule"),
                videoParsePlatform = o.optStringOrNull("videoParsePlatform"),
                episodeListMode = o.optStringOrNull("episodeListMode"),
                downloadSortType = o.optStringOrNull("downloadSortType"),
                useVideoContainer = o.optStringOrNull("useVideoContainer"),
                useAudioContainer = o.optStringOrNull("useAudioContainer"),
                biliLineHost = o.optStringOrNull("biliLineHost"),
                enabledDynamicColor = o.optBooleanOrNull("enabledDynamicColor"),
                enabledClipboardAutoHandling = o.optBooleanOrNull("enabledClipboardAutoHandling"),
                enabledRoam = o.optBooleanOrNull("enabledRoam"),
                segmentedDownloadEnabled = o.optBooleanOrNull("segmentedDownloadEnabled"),
                segmentedDownloadConcurrency = o.optIntOrNull("segmentedDownloadConcurrency"),
                wifiOnlyDownload = o.optBooleanOrNull("wifiOnlyDownload"),
                downloadSpeedLimitKbps = o.optIntOrNull("downloadSpeedLimitKbps"),
                skipDownloaded = o.optBooleanOrNull("skipDownloaded"),
            )
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

        private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
            if (has(key) && !isNull(key)) optBoolean(key) else null

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (has(key) && !isNull(key)) optInt(key) else null
    }
}

/**
 * 备份与恢复的**纯规则**（好测：不碰文件、不碰 DataStore）。
 */
object AppSettingsBackupRules {

    /** 从当前设置生成备份 */
    fun from(settings: AppSettings, exportedAt: String, appVersion: String): AppSettingsBackup =
        AppSettingsBackup(
            schema = AppSettingsBackup.CURRENT_SCHEMA,
            exportedAt = exportedAt,
            appVersion = appVersion,
            videoNamingRule = settings.videoNamingRule,
            bangumiNamingRule = settings.bangumiNamingRule,
            videoParsePlatform = settings.videoParsePlatform?.name,
            episodeListMode = settings.episodeListMode?.name,
            downloadSortType = settings.downloadSortType?.name,
            useVideoContainer = settings.useVideoContainer,
            useAudioContainer = settings.useAudioContainer,
            biliLineHost = settings.biliLineHost,
            enabledDynamicColor = settings.enabledDynamicColor,
            enabledClipboardAutoHandling = if (settings.hasEnabledClipboardAutoHandling()) {
                settings.enabledClipboardAutoHandling
            } else {
                null
            },
            enabledRoam = settings.enabledRoam,
            segmentedDownloadEnabled = if (settings.hasSegmentedDownloadEnabled()) {
                settings.segmentedDownloadEnabled
            } else {
                null
            },
            segmentedDownloadConcurrency = if (settings.hasSegmentedDownloadConcurrency()) {
                settings.segmentedDownloadConcurrency
            } else {
                null
            },
            wifiOnlyDownload = if (settings.hasWifiOnlyDownload()) {
                settings.wifiOnlyDownload
            } else {
                null
            },
            downloadSpeedLimitKbps = if (settings.hasDownloadSpeedLimitKbps()) {
                settings.downloadSpeedLimitKbps
            } else {
                null
            },
            skipDownloaded = if (settings.hasSkipDownloaded()) {
                settings.skipDownloaded
            } else {
                null
            },
        )

    /**
     * 把备份**叠加**到当前设置上：只覆盖备份里"确实有值"的字段，其余保持不动。
     *
     * 为什么不整体替换：备份可能来自旧版本（那时还没有某个字段），
     * 整体替换会把用户在当前版本里设好的东西抹掉。
     */
    fun overlay(current: AppSettings, backup: AppSettingsBackup): AppSettings {
        val b = current.toBuilder()

        backup.videoNamingRule?.let { b.setVideoNamingRule(it) }
        backup.bangumiNamingRule?.let { b.setBangumiNamingRule(it) }
        backup.useVideoContainer?.let { b.setUseVideoContainer(it) }
        backup.useAudioContainer?.let { b.setUseAudioContainer(it) }
        backup.biliLineHost?.let { b.setBiliLineHost(it) }
        backup.enabledDynamicColor?.let { b.setEnabledDynamicColor(it) }
        backup.enabledRoam?.let { b.setEnabledRoam(it) }
        backup.enabledClipboardAutoHandling?.let { b.setEnabledClipboardAutoHandling(it) }
        backup.segmentedDownloadEnabled?.let { b.setSegmentedDownloadEnabled(it) }
        backup.segmentedDownloadConcurrency?.let { b.setSegmentedDownloadConcurrency(it) }
        backup.wifiOnlyDownload?.let { b.setWifiOnlyDownload(it) }
        backup.downloadSpeedLimitKbps?.let { b.setDownloadSpeedLimitKbps(it) }
        backup.skipDownloaded?.let { b.setSkipDownloaded(it) }

        // 枚举按**名字**读回来；名字不认识（旧版/手改过）就保持原值，不猜
        backup.videoParsePlatform
            ?.let { name -> runCatching { AppSettings.VideoParsePlatform.valueOf(name) }.getOrNull() }
            ?.let { b.setVideoParsePlatform(it) }
        backup.episodeListMode
            ?.let { name -> runCatching { AppSettings.EpisodeListMode.valueOf(name) }.getOrNull() }
            ?.let { b.setEpisodeListMode(it) }
        backup.downloadSortType
            ?.let { name -> runCatching { AppSettings.DownloadSortType.valueOf(name) }.getOrNull() }
            ?.let { b.setDownloadSortType(it) }

        return b.build()
    }
}
