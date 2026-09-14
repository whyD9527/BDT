package com.imcys.bilibilias.download.service

import android.Manifest.*
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager.*
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.imcys.bilibilias.MainActivity
import com.imcys.bilibilias.R
import com.imcys.bilibilias.common.utils.DOWNLOAD_NOTIFICATION_CHANNEL_ID


class DownloadService : Service() {

    companion object {
        const val DOWNLOAD_SERVICE_ID = 100
    }

    lateinit var notificationCompat: NotificationCompat.Builder

    inner class DownloadBinder : Binder() {
        val service: DownloadService?
            get() = this@DownloadService
    }

    private val binder = DownloadBinder()

    override fun onBind(intent: Intent?): IBinder? = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 防止用户突然进后台
        runCatching { startForeground() }
        // ⚠️ 必须 **NOT_STICKY**。原先返回 START_STICKY：进程被系统/用户杀掉后，
        // Android 会用**空 intent** 把这个服务再拉起来 → 又走一次 startForeground()
        // → 留下一条 0% 的「AS视频缓存中…」通知，而这时队列根本不存在（管理器状态随进程没了），
        // 那条通知会一直挂着，只能杀进程。见交接文档第十八轮审查。
        return START_NOT_STICKY
    }

    fun startForeground() {
        // 构造通知
        notificationCompat = buildDownloadFileNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(permission.FOREGROUND_SERVICE) != PERMISSION_GRANTED) {
                stopSelf()
                return
            }
        }
        // 启动前台服务
        ServiceCompat.startForeground(
            this,
            DOWNLOAD_SERVICE_ID,
            notificationCompat.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )

    }

    /**
     * 构造通知
     */
    private fun buildDownloadFileNotification() =
        run {
            val intent = Intent(this, MainActivity::class.java)
            val pIntent =
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

            NotificationCompat.Builder(
                this,
                DOWNLOAD_NOTIFICATION_CHANNEL_ID
            ).apply {
                setContentTitle("缓存通知")
                setContentText("AS视频缓存中...")
                    .setProgress(100, 0, false)
                setContentIntent(pIntent)
                setSmallIcon(R.drawable.ic_logo_mini)
                setPriority(NotificationCompat.PRIORITY_DEFAULT)
                setOnlyAlertOnce(true)
            }
        }

    fun updateNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean = false
    ) {
        notificationCompat.setContentTitle(title)
            .setContentText(text)
            .setProgress(100, progress, indeterminate)
        ServiceCompat.startForeground(
            this,
            DOWNLOAD_SERVICE_ID,
            notificationCompat.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    /**
     * 队列收工（全仓唯一会调它的地方是 `NewDownloadManager.startDownloadQueue`）。
     *
     * ⚠️ 必须**连服务本身一起停掉**。原先只 `stopForeground(REMOVE)`：通知是没了，
     * 但服务仍然是"已启动"状态 —— 它当初是被 `startForegroundService` 拉起来的，
     * 不 `stopSelf()` 就会一直挂着（进程也随之常驻）；配 START_STICKY 更是杀掉还会被拉回来。
     */
    fun onDownloadFinished() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // 兜底：任何路径下服务销毁都不该留下通知
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }
}
