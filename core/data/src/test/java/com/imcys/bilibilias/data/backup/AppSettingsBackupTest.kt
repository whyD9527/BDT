package com.imcys.bilibilias.data.backup

import com.imcys.bilibilias.datastore.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置备份的纯规则测试：JSON 往返、只看非空项、坏文件不崩、枚举按名字读。
 */
class AppSettingsBackupTest {

    private fun sampleSettings(): AppSettings = AppSettings.newBuilder()
        .setVideoNamingRule("{title}_{p_title}")
        .setBangumiNamingRule("{season_title}/{episode_number}_{episode_title}")
        .setVideoParsePlatform(AppSettings.VideoParsePlatform.TV)
        .setEpisodeListMode(AppSettings.EpisodeListMode.EpisodeListMode_List)
        .setDownloadSortType(AppSettings.DownloadSortType.DownloadSort_SizeDesc)
        .setUseVideoContainer("mkv")
        .setUseAudioContainer("m4a")
        .setBiliLineHost("custom.example.com")
        .setEnabledDynamicColor(true)
        .setEnabledRoam(false)
        .setSegmentedDownloadEnabled(true)
        .setSegmentedDownloadConcurrency(6)
        .build()

    @Test
    fun `导出再导入，字段一个不少`() {
        val backup = AppSettingsBackupRules.from(sampleSettings(), "2026-10-02 12:00", "3.3.3")
        val restored = AppSettingsBackup.fromJson(backup.toJson())

        assertEquals(backup, restored)
        assertEquals("{title}_{p_title}", restored?.videoNamingRule)
        assertEquals("TV", restored?.videoParsePlatform)
        assertEquals(6, restored?.segmentedDownloadConcurrency)
    }

    @Test
    fun `恢复只覆盖备份里有值的字段，不抹掉当前设置`() {
        val backup = AppSettingsBackup(
            videoNamingRule = "{title}",
            segmentedDownloadConcurrency = 2,
        )
        val current = sampleSettings()

        val merged = AppSettingsBackupRules.overlay(current, backup)

        assertEquals("{title}", merged.videoNamingRule)
        assertEquals(2, merged.segmentedDownloadConcurrency)
        assertEquals(current.bangumiNamingRule, merged.bangumiNamingRule)
        assertEquals(current.videoParsePlatform, merged.videoParsePlatform)
        assertEquals(current.biliLineHost, merged.biliLineHost)
        assertEquals(current.enabledRoam, merged.enabledRoam)
    }

    @Test
    fun `枚举按名字读，认不出的名字保持原值`() {
        val current = sampleSettings()
        val merged = AppSettingsBackupRules.overlay(
            current,
            AppSettingsBackup(videoParsePlatform = "未来才有的平台", downloadSortType = "DownloadSort_TitleAsc"),
        )
        assertEquals(current.videoParsePlatform, merged.videoParsePlatform)
        assertEquals(AppSettings.DownloadSortType.DownloadSort_TitleAsc, merged.downloadSortType)
    }

    @Test
    fun `不是备份文件时返回 null`() {
        assertNull(AppSettingsBackup.fromJson("这不是 JSON"))
        assertNull(AppSettingsBackup.fromJson(""))
        assertEquals(AppSettingsBackup(), AppSettingsBackup.fromJson("""{"hello":"world"}"""))
    }

    @Test
    fun `没设置过的 optional 字段不会写进备份`() {
        val fresh = AppSettings.newBuilder().build()
        val backup = AppSettingsBackupRules.from(fresh, "", "")
        assertNull(backup.segmentedDownloadEnabled)
        assertNull(backup.segmentedDownloadConcurrency)
        assertNull(backup.enabledClipboardAutoHandling)
        assertTrue(backup.toJson().contains("videoNamingRule"))
    }
}
