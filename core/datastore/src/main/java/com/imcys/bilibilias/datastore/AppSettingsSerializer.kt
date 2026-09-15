package com.imcys.bilibilias.datastore

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

val Context.userAppSettingsStore: DataStore<AppSettings> by dataStore(
    fileName = "app_setting.pb",
    serializer = AppSettingsSerializer
)

/**
 * 序列化
 */
object AppSettingsSerializer : Serializer<AppSettings> {

    val appSettingsDefault = AppSettings.getDefaultInstance().toBuilder()
        .setVideoNamingRule("{p_title}")
        .setBangumiNamingRule("{episode_title}")
        .addAllUseToolHistory(listOf("WebParser","FrameExtractor"))
        .setEnabledClipboardAutoHandling(true)
        .setVideoParsePlatform(AppSettings.VideoParsePlatform.Web)
        .setUseVideoContainer("mp4")
        .setUseAudioContainer("m4a")
        .build()


    override val defaultValue: AppSettings = appSettingsDefault

    override suspend fun readFrom(input: InputStream): AppSettings {
        try {
            val parsed = AppSettings.parseFrom(input)
            val builder = parsed.toBuilder()
            var modified = false
            if (parsed.bangumiNamingRule.isBlank()) {
                builder.setBangumiNamingRule(defaultValue.bangumiNamingRule)
                modified = true
            }
            if (parsed.videoNamingRule.isBlank()) {
                builder.setVideoNamingRule(defaultValue.videoNamingRule)
                modified = true
            }
            if (parsed.useToolHistoryList.isEmpty()){
                builder.addAllUseToolHistory(defaultValue.useToolHistoryList)
                modified = true
            }
            if (!parsed.hasEnabledClipboardAutoHandling()){
                builder.setEnabledClipboardAutoHandling(defaultValue.enabledClipboardAutoHandling)
                modified = true
            }
            if (!parsed.hasVideoParsePlatform()){
                builder.setVideoParsePlatform(defaultValue.videoParsePlatform)
                modified = true
            }
            if (parsed.useVideoContainer.isNullOrEmpty()){
                // ⚠️ 这行原先写的是 `setVideoParsePlatform(...)`（复制粘贴漏改）——
                // 后果有两条，都很隐蔽（2026-09-14 全量审计 H7）：
                // ① `useVideoContainer` 永远补不成默认值，而消费方
                //    `AppSettingsRepository.storeMediaContainerFromExtension` 用
                //    `MediaContainer.entries.first { it.extension == "" }` 取容器 → 抛
                //    NoSuchElementException → 进解析页即崩；
                // ② 顺带把用户选好的「TV 解析平台」在每个读取周期静默改回 Web。
                builder.setUseVideoContainer(defaultValue.useVideoContainer)
                modified = true
            }
            if (parsed.useAudioContainer.isNullOrEmpty()){
                builder.setUseAudioContainer(defaultValue.useAudioContainer)
                modified = true
            }
            return if (modified) builder.build() else parsed
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", e)
        }
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) = t.writeTo(output)
}