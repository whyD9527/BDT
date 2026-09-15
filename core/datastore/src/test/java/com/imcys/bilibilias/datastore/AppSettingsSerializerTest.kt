package com.imcys.bilibilias.datastore

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * `AppSettingsSerializer` 的兜底逻辑测试。
 *
 * 这个模块以前**没有任何单测**，于是里面一个复制粘贴级的错误一直没被拦住
 * （2026-09-14 全量审计 H7）：`useVideoContainer` 为空时补的是
 * `setVideoParsePlatform(...)` —— 容器永远补不成默认值（消费方 `first{}` 抛
 * `NoSuchElementException` → 进解析页崩），还顺手把用户的 TV 平台改回 Web。
 *
 * 这几条用例把"哪个字段该被补成什么"钉死。
 */
class AppSettingsSerializerTest {

    /** 读一份"几乎什么都没有"的设置（模拟老版本写下的 pb）。 */
    private fun readEmpty(): com.imcys.bilibilias.datastore.AppSettings {
        val bytes = com.imcys.bilibilias.datastore.AppSettings.getDefaultInstance()
            .toByteArray()
        return runBlocking { AppSettingsSerializer.readFrom(ByteArrayInputStream(bytes)) }
    }

    @Test
    fun `空视频容器要补成默认的 mp4（不能补到别的字段上）`() {
        val settings = readEmpty()
        assertTrue(
            "空容器必须被补成默认值，否则消费方 first{} 会抛 NoSuchElementException",
            settings.useVideoContainer.isNotEmpty(),
        )
        assertEquals(AppSettingsSerializer.appSettingsDefault.useVideoContainer, settings.useVideoContainer)
    }

    @Test
    fun `空音频容器要补成默认的 m4a`() {
        val settings = readEmpty()
        assertTrue(settings.useAudioContainer.isNotEmpty())
        assertEquals(AppSettingsSerializer.appSettingsDefault.useAudioContainer, settings.useAudioContainer)
    }

    @Test
    fun `补容器时不能顺手改掉用户的解析平台`() {
        // 这是那个 bug 的第二半：原实现每次都在这里调 setVideoParsePlatform。
        val settings = readEmpty()
        assertEquals(
            "补容器不该影响 videoParsePlatform",
            AppSettingsSerializer.appSettingsDefault.videoParsePlatform,
            settings.videoParsePlatform,
        )
    }

    @Test
    fun `命名规则为空时补默认值`() {
        val settings = readEmpty()
        assertTrue(settings.videoNamingRule.isNotBlank())
        assertTrue(settings.bangumiNamingRule.isNotBlank())
    }

    @Test
    fun `已经设置过的值要原样保留`() {
        val preset = AppSettingsSerializer.appSettingsDefault.toBuilder()
            .setUseVideoContainer("mkv")
            .setVideoParsePlatform(AppSettings.VideoParsePlatform.TV)
            .setVideoNamingRule("{title}")
            .build()
        val read = runBlocking { AppSettingsSerializer.readFrom(ByteArrayInputStream(preset.toByteArray())) }
        assertEquals("mkv", read.useVideoContainer)
        assertEquals(AppSettings.VideoParsePlatform.TV, read.videoParsePlatform)
        assertEquals("{title}", read.videoNamingRule)
    }
}
