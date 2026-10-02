package com.imcys.bilibilias.common.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "com.imcys.bilibilias.gp.DOWNLOAD_NOTIFICATION"

const val DOWNLOAD_NOTIFICATION_ID = 1000


fun Context.applyDownloadNotificationManager(notificationManagerContent: NotificationManagerCompat.() -> Unit) {
    NotificationManagerCompat.from(this).apply(notificationManagerContent)
}

/**
 * 创建下载通知渠道。
 *
 * ⚠️ `name` / `description` 由**调用方（app 层）**传进来：`:core:common` 是**没有资源的库模块**，
 * 看不到 app 的 `R`（2026-10-02 CI 报 `NotificationUtils.kt:3 Unresolved reference 'R'`）——
 * 与"纯逻辑模块的文案要么返回码、要么由 UI 层传进来"是同一条规矩（见 §16.7）。
 */
fun Context.createDownloadNotificationChannel(channelName: String, channelDescription: String) {
    val channelId = DOWNLOAD_NOTIFICATION_CHANNEL_ID
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(
            channelId,
            channelName,
            importance
        ).apply {
            // ⚠️ 参数名别叫 `description`：`apply` 里 `description` 会优先解析成
            // NotificationChannel 自己的属性 → 自赋值 → `'val' cannot be reassigned`（CI 报过）
            description = channelDescription
        }
        // Register the channel with the system.
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}