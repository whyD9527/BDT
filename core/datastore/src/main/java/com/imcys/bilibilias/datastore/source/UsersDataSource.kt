package com.imcys.bilibilias.datastore.source

import androidx.datastore.core.DataStore
import com.imcys.bilibilias.datastore.User
import com.imcys.bilibilias.datastore.copy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull

class UsersDataSource(
    private val dataStore: DataStore<User>,
) {
    /**
     * 读盘异常的兜底出口（2026-09-15 复审 A-M4）。
     *
     * 原来直接把 `dataStore.data` 交出去：文件损坏（corruptionHandler 只兜 CorruptionException）
     * 或读取 IO 出错时，异常会抛给**每个**收集者与 `getUserId()` 调用点 —— 首页/下载/设置
     * 一起崩，而且用户没法从界面自救。这里统一回落成默认值（未登录、用 buvid3）。
     */
    private val safeData: Flow<User> = dataStore.data.catch { e ->
        if (e is CancellationException) throw e
        emit(User.getDefaultInstance())
    }

    val users = safeData

    suspend fun setUserId(id: Long) {
        dataStore.updateData {
            it.copy {
                currentUserId = id
                notUseBuvid3 = false
            }
        }
    }

    suspend fun setNotUseBuvid3(notUse: Boolean) {
        dataStore.updateData {
            it.copy {
                notUseBuvid3 = notUse
            }
        }
    }

    suspend fun getUserId(): Long {
        return safeData.first().currentUserId
    }

    suspend fun isLogin() = getUserId() != 0L

}