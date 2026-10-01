package com.imcys.bilibilias.common.update

import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.clientVersionStalenessDays
import com.imcys.bilibilias.data.repository.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine


class GooglePlayAppUpdateManage(
    context: Context,
    private val appSettingsRepository: AppSettingsRepository
) : ASAppUpdateManage() {
    private companion object {
        const val TAG = "ASAppUpdate"
    }

    private var appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(context)

    private var lastAppUpdateType = AppUpdateType.FLEXIBLE


    private val appSettingsFlow = appSettingsRepository.appSettingsFlow

    private var lastSkipUpdateVersionCode = 0

    // 原先使用 GlobalScope，改为受控作用域，避免协程泄漏
    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        updateScope.launch {
            appSettingsFlow.collect {
                lastSkipUpdateVersionCode = it.lastSkipUpdateVersionCode
            }
        }
    }

    override suspend fun checkAppImmediateUpdate(): Boolean {
        return checkUpdateAvailability(AppUpdateType.IMMEDIATE)
    }

    override suspend fun checkAppFlexibleUpdate(): Boolean {
        return checkUpdateAvailability(AppUpdateType.FLEXIBLE)
    }

    suspend fun getUpdateVersion(): Int {
        return suspendCancellableCoroutine { cont ->
            appUpdateManager
                .appUpdateInfo
                .addOnSuccessListener { appUpdateInfo ->
                    if (cont.isActive) {
                        cont.resumeWith(Result.success(appUpdateInfo.availableVersionCode()))
                    }
                }
                // ⚠️ 必须有失败回调（2026-10-01 复审）：原来只挂 addOnSuccessListener，
                // 而 Play Core 的任务在“没有 Play 服务 / 网络失败 / 被风控 / 任务被取消”时**只会回调失败** ——
                // 于是 suspendCancellableCoroutine **永不恢复**，调用方（更新检查那条路）就静默挂死。
                .addOnFailureListener { e ->
                    Log.w(TAG, "获取可更新版本失败，按 0（无更新）处理", e)
                    if (cont.isActive) cont.resumeWith(Result.success(0))
                }
        }
    }

    fun startUpdate(
        activityResultLauncher: ActivityResultLauncher<IntentSenderRequest>,
        updateFinish: () -> Unit
    ) {
        appUpdateManager
            .appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->
                // 如果已经下载完成，直接提示完成安装
                if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                    updateFinish()
                    return@addOnSuccessListener
                }

                // 如果有更新，启动更新
                appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    activityResultLauncher,
                    AppUpdateOptions.newBuilder(lastAppUpdateType).build()
                )
            }
            .addOnFailureListener { e -> Log.w(TAG, "取取更新信息失败，无法启动更新流程", e) }
    }


    fun registerFlexibleUpdateListener(listener: InstallStateUpdatedListener) {
        // 注册灵活更新监听器
        appUpdateManager.registerListener(listener)
    }

    fun completeUpdate() {
        appUpdateManager.completeUpdate()
    }


    private suspend fun checkUpdateAvailability(updateType: Int): Boolean {
        val appUpdateInfoTask = appUpdateManager.appUpdateInfo
        return suspendCancellableCoroutine { cont ->
            appUpdateInfoTask
                .addOnSuccessListener { appUpdateInfo ->
                    val versionCode = appUpdateInfo.availableVersionCode()
                    if (lastSkipUpdateVersionCode == versionCode) {
                        if (cont.isActive) cont.resumeWith(Result.success(false))
                        return@addOnSuccessListener
                    }

                    if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                        && appUpdateInfo.isUpdateTypeAllowed(updateType)
                    ) {
                        lastAppUpdateType = updateType
                        if (cont.isActive) cont.resumeWith(Result.success(true))
                    } else {
                        if (cont.isActive) cont.resumeWith(Result.success(false))
                    }
                }
                // ⚠️ 失败回调要挂在**任务**上（addOnSuccessListener 返回 Task，可以继续链式调用）：
                // 原来完全没有失败回调，Play Core 任务失败时 suspendCancellableCoroutine
                // **永不恢复** → 更新检查静默挂死。
                .addOnFailureListener { e ->
                    Log.w(TAG, "检查更新失败，按无更新处理", e)
                    if (cont.isActive) cont.resumeWith(Result.success(false))
                }
        }
    }

}