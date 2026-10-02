package com.imcys.bilibilias.ui.error

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process.killProcess
import android.os.Process.myPid
import android.util.Log
import com.imcys.bilibilias.common.crash.CrashLogRules
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 全局未捕获异常处理。
 *
 * ## 2026-10-02 重新启用（原先在 `BILIBILIASApplication` 里被注释掉）
 * 关掉它的那段时间，崩溃**没有任何本地线索**：这台 ROM（小米 / Android 16）会过滤 app 自己的
 * logcat（交接文档 §14.2 第 4 条），用户报"闪退"时只能靠猜。现在改成：
 *
 * 1. **先落文件**：完整堆栈（含线程、进程、时间）追加进
 *    `Android/data/<pkg>/files/logs/crash.log`（与 `download-trace.log` 同目录），
 *    并由「存储管理 → 导出诊断日志」一起导出 —— 这是唯一不受 MIUI 过滤的取证渠道；
 * 2. **再开崩溃页**：Intent 里只放**按字节截断**过的一份（[CrashLogRules.INTENT_DETAIL_MAX_BYTES]）——
 *    整份报告塞 Intent 会撞 Binder 事务上限（`TransactionTooLargeException`）；
 * 3. **最后才结束进程**：包在 `runCatching` 里 —— 异常处理器**自己绝不能再抛异常**，否则线索直接丢；
 *    并且**延迟** [CRASH_EXIT_DELAY_MS] 再 kill：`startActivity` 只是把请求发给 AMS，
 *    Activity 真正显示要等 AMS 再调度回来，紧接着 kill 就是"页面还没画出来、进程已经没了"的竞态。
 *
 * 起不了崩溃页（后台限制/系统直接杀）时，交回系统默认处理器一次，至少让系统也记一笔。
 */
class AppCrashHandler private constructor() : Thread.UncaughtExceptionHandler {

    private lateinit var context: Context

    private val defaultSystemExpHandler = Thread.getDefaultUncaughtExceptionHandler()

    fun init(context: Context) {
        this.context = context.applicationContext
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(t: Thread, error: Throwable) {
        val report = runCatching { buildReport(t, error) }.getOrElse { error.toString() }
        // ① 落文件（唯一可靠的取证渠道）
        runCatching { appendCrashLog(report) }
        // 顺便打一份 logcat（MIUI 会过滤，但有比没有强）
        runCatching { Log.e(TAG, report) }

        // ② 崩溃页（只带截断后的报告）
        val started = runCatching {
            context.startActivity(
                Intent(context, AppCrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(
                        EXTRA_ERROR_MSG,
                        CrashLogRules.truncateUtf8(report, CrashLogRules.INTENT_DETAIL_MAX_BYTES),
                    )
                }
            )
        }.isSuccess

        if (!started) {
            // 崩溃页起不来 → 交回系统默认处理器（至少让系统也记一笔），然后照旧结束进程
            runCatching { defaultSystemExpHandler?.uncaughtException(t, error) }
            runCatching { killProcess(myPid()) }
            exitProcess(1)
            return
        }

        // ③ 结束进程 —— **延迟**一点，给 AMS 把崩溃页真正拉起来的时间。
        //    期间用户若点「退出软件」，那条路径直接 exitProcess(0)，不会等到这里。
        Thread {
            runCatching { Thread.sleep(CRASH_EXIT_DELAY_MS) }
            runCatching { killProcess(myPid()) }
            exitProcess(1)
        }.apply { isDaemon = true }.start()
    }

    private fun buildReport(t: Thread, error: Throwable): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())
        val processName = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                context.packageName
            }
        }.getOrElse { context.packageName }
        val stackTrace = StringWriter().also { writer ->
            error.printStackTrace(PrintWriter(writer))
        }.toString()
        return CrashLogRules.format(
            stamp = stamp,
            threadName = t.name,
            processName = processName,
            throwableText = stackTrace,
        )
    }

    /** 追加（或按上限整体重写）崩溃日志；任何失败都只是"没记上"，不能影响崩溃流程 */
    private fun appendCrashLog(report: String) {
        val file = crashLogFile()
        val incomingBytes = report.toByteArray(Charsets.UTF_8).size
        if (CrashLogRules.shouldRotate(file.length(), incomingBytes)) {
            file.delete()
        }
        file.appendText(report)
    }

    private fun crashLogFile(): File =
        File(
            File(context.getExternalFilesDir(null), CrashLogRules.LOG_DIR).apply { mkdirs() },
            CrashLogRules.FILE_NAME,
        )

    companion object {
        private const val TAG = "AppCrashHandler"

        /** 崩溃页读这个 extra（`AppCrashActivity` 里的常量与这里必须一致） */
        const val EXTRA_ERROR_MSG = "appErrorMsg"

        /** 拉起崩溃页后延迟多久结束进程（给 AMS 显示页面的时间） */
        private const val CRASH_EXIT_DELAY_MS = 1500L

        val instance: AppCrashHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            AppCrashHandler()
        }
    }
}
