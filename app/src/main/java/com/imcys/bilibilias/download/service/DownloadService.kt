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
import com.imcys.bilibilias.download.NewDownloadManager


class DownloadService : Service() {

    companion object {
        const val DOWNLOAD_SERVICE_ID = 100

        /** 通知上的两个动作（B3）。聚合通知只能做"全部"语义，这样才不含糊。 */
        const val ACTION_PAUSE_ALL = "com.imcys.bilibilias.download.action.PAUSE_ALL"
        const val ACTION_CANCEL_ALL = "com.imcys.bilibilias.download.action.CANCEL_ALL"
    }

    lateinit var notificationCompat: NotificationCompat.Builder

    inner class DownloadBinder : Binder() {
        val service: DownloadService?
            get() = this@DownloadService
    }

    private val binder = DownloadBinder()

    override fun onBind(intent: Intent?): IBinder? = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知动作（B3）：暂停全部 / 取消全部。
        // 管理器是 Koin single（本服务与它同进程），这里拿一次就够了；
        // 用 runCatching 包住：拿不到也不能让服务崩掉（它就是条通知而已）。
        runCatching {
            val manager = org.koin.core.context.GlobalContext.get().get<NewDownloadManager>()
            when (intent?.action) {
                ACTION_PAUSE_ALL -> manager.pauseAllActive()
                ACTION_CANCEL_ALL -> manager.cancelAllActive()
            }
        }
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
                // B3 点通知直达：带上"启动目标"，MainActivity 收到后让导航层跳到下载管理页
                .putExtra(MainActivity.EXTRA_START_TARGET, MainActivity.START_TARGET_DOWNLOAD_LIST)
            // ⚠️ 必须带 FLAG_UPDATE_CURRENT：这个 PendingIntent 的 requestCode 固定为 0，
            // 在 FLAG_IMMUTABLE 下不带 update 时系统会复用**已存在的**那个（extras 是老的就丢了）。
            val pIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            NotificationCompat.Builder(
                this,
                DOWNLOAD_NOTIFICATION_CHANNEL_ID
            ).apply {
                setContentTitle(getString(R.string.notification_cache_title))
                setContentText(getString(R.string.download_caching))
                    .setProgress(100, 0, false)
                setContentIntent(pIntent)
                setSmallIcon(R.drawable.ic_logo_mini)
                setPriority(NotificationCompat.PRIORITY_DEFAULT)
                setOnlyAlertOnce(true)

                // B3：两个动作按钮。理由同 pauseAllActive 的注释 —— 聚合通知只做"全部"。
                val pauseAllIntent = PendingIntent.getService(
                    this@DownloadService,
                    1,
                    Intent(this@DownloadService, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                val cancelAllIntent = PendingIntent.getService(
                    this@DownloadService,
                    2,
                    Intent(this@DownloadService, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                addAction(android.R.drawable.ic_media_pause, getString(R.string.notification_pause_all), pauseAllIntent)
                addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_cancel_all), cancelAllIntent)
            }
        }

    fun updateNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean = false
    ) {
        // ⚠️ `notificationCompat` 是 lateinit，只在 `onStartCommand → startForeground()` 里赋值。
        // 服务若只被 `bindService(BIND_AUTO_CREATE)` 创建（或 `startForegroundService` 抛异常被
        // `NewDownloadManager` 的 runCatching 吞掉、只剩 bind 成功），这里就会抛
        // `UninitializedPropertyAccessException` —— 而它发生在**每个进度回调**里，
        // 会被下载层当成"读写异常"，重试 5 次后任务误报失败（2026-09-15 复审 A-M6）。
        if (!::notificationCompat.isInitialized) {
            notificationCompat = buildDownloadFileNotification()
        }
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
