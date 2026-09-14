package com.imcys.bilibilias.network.utils

import com.imcys.bilibilias.network.config.WTS
import com.imcys.bilibilias.network.config.W_RID
import com.imcys.bilibilias.network.model.BILILoginUserInfo
import com.imcys.bilibilias.network.service.BILIBILIWebAPIService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.util.TreeMap

/**
 * wbi 签名（B 站 Web 端接口的签名方案）。
 *
 * ## 2026-09 加固（Step 3）改了三处确定性缺陷
 *
 * 1. **key 永不刷新**：原实现只在 `key == null` 时取一次，一旦 B 站轮换 wbi key，
 *    所有 wbi 接口会一直失败**直到 App 重启**。现在按 TTL 刷新；
 *    且刷新失败时**保留旧 key**，不会把本来可用的状态打坏。
 * 2. **并发不安全**：`key` 是无保护的全局可变状态。现标 `@Volatile`，
 *    刷新用 `Mutex` 串行化（多个协程同时签名不会重复发请求）。
 * 3. **越界崩溃**：mixKey 长度判定写成 `>= 置换表最大值(63)`，
 *    而长度恰为 63 时访问下标 63 会 `IndexOutOfBoundsException`。
 *    正确要求是 **长度 > 63**，已修正。
 *
 * ## 已知耦合（刻意保留，未改）
 *
 * [encWbi] / [checkToken] 写成 `BILIBILIWebAPIService` 的扩展函数，是因为
 * 刷新 key 必须调用该 service 的接口（`getWebIInfoNoCheckLogin`）。
 * 这属于分层上的耦合，但它没有引入新状态；为解耦而改动 7 个调用点
 * 收益不抵风险，因此保留并在此注明。
 */
object WebiTokenUtils {

    /** 有 key 时的刷新周期：到期重新取一次（B 站会不定期轮换 wbi key） */
    private const val KEY_TTL_MS = 6 * 60 * 60 * 1000L

    /** 无 key 时的重试间隔：取失败后 30 秒再试，既不卡死也不至于每个请求都打网络 */
    private const val FAILURE_RETRY_MS = 30 * 1000L

    /**
     * 置换表：用它的下标从 `img_key + sub_key` 里抽取字符。
     *
     * ⚠️ 表里有 **64** 项，但官方算法**只取前 32 项**（见 [MIXIN_KEY_LENGTH]）。
     * 这个细节极易漏：2026-09 我重写本文件时丢了 `.take(32)`，
     * 让 mixin key 变成 64 字符，结果**所有 wbi 签名全部失效**，
     * B 站按风控处理（-352），用户页直接报错。别删那个 take。
     */
    private val MIXIN_KEY_INDEXES = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45,
        35, 27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38,
        41, 13, 37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60,
        51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
        20, 34, 44, 52
    )

    /** mixin key 的长度：置换表虽然 64 项，但只取前 32 项 */
    private const val MIXIN_KEY_LENGTH = 32

    /** 置换表里的最大下标（=63）。mixKey 长度必须**大于**它，否则取下标会越界 */
    private const val MAX_MIXIN_INDEX = 63

    @Volatile
    private var cachedKey: String? = null

    /** 上次「尝试」刷新 key 的时间。记的是尝试而非成功 —— 失败也要记，否则每个请求都会打网络 */
    @Volatile
    private var lastRefreshAttemptAt = 0L

    /**
     * 当前 mixin key（**只读**，写入口只有 [setKey]）。
     *
     * 外部（如 `QRCodeLoginRepository`）会在拿到登录信息时判断 `key == null`，
     * 顺手用它初始化一次 —— 这个用法保留，所以这里仍然开放读访问。
     */
    val key: String?
        get() = cachedKey

    /** 串行化刷新，避免并发重复取 key */
    private val refreshMutex = Mutex()

    private fun isStale(): Boolean {
        val elapsed = System.currentTimeMillis() - lastRefreshAttemptAt
        return if (cachedKey == null) {
            elapsed > FAILURE_RETRY_MS   // 还没有 key：快速重试，尽快恢复
        } else {
            elapsed > KEY_TTL_MS         // 已有 key：按周期刷新
        }
    }

    /**
     * 由 img_key + sub_key 推导 mixin key。
     *
     * @return 是否设置成功。失败时**不动**已有的 key（原来的写法会把它清成 null，
     *         导致后续所有签名直接抛异常）。
     */
    fun setKey(loginInfo: BILILoginUserInfo.WbiImg): Boolean {
        val imgKey = loginInfo.imgUrl.replace(".png", "").split('/').last()
        val subKey = loginInfo.subUrl.replace(".png", "").split('/').last()
        val mixKey = imgKey + subKey

        // 长度必须 > 63，否则下面的 mixKey[it] 会越界
        if (mixKey.length <= MAX_MIXIN_INDEX) return false

        // 只取前 32 项（官方算法）—— 漏掉 take 会让签名整体失效，别删
        cachedKey = MIXIN_KEY_INDEXES.map { mixKey[it] }
            .take(MIXIN_KEY_LENGTH)
            .joinToString("")
        // 刚拿到新 key，刷新计时一并归零 —— 否则会被立刻判为过期、白跑一次请求
        lastRefreshAttemptAt = System.currentTimeMillis()
        return true
    }

    /** 生成带签名的参数（在原参数基础上补 `wts` 与 `w_rid`） */
    suspend fun BILIBILIWebAPIService.encWbi(params: Map<String, String>): Map<String, String> {
        checkToken()

        val parameters = mutableMapOf<String, String>().apply {
            put(WTS, (System.currentTimeMillis() / 1000).toString())
            putAll(params)
        }

        val secretKey = key ?: throw IllegalStateException("Key is not set. Call setKey() first.")

        val sortedParams = TreeMap(parameters)
        val dataStr = sortedParams.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        } + secretKey

        parameters[W_RID] = md5Hex(dataStr) ?: ""

        return parameters
    }

    /**
     * 需要签名时调用：key 过期或缺失才去刷新（带并发去重）。
     */
    private suspend fun BILIBILIWebAPIService.checkToken() {
        if (!isStale()) return
        refreshMutex.withLock {
            // 双检：等锁期间可能已被别的协程刷过
            if (!isStale()) return
            lastRefreshAttemptAt = System.currentTimeMillis()
            runCatching { updateWebiKey() }
        }
    }

    /**
     * 更新 wbi key。
     *
     * 失败时静默返回（`runCatching`），由 [isStale] 的重试间隔决定下次再试；
     * 已有的旧 key 会被保留，不影响正在进行的签名。
     */
    suspend fun BILIBILIWebAPIService.updateWebiKey() {
        runCatching {
            getWebIInfoNoCheckLogin()
        }.onSuccess {
            it.data?.wbiImg?.let { wbiImg -> setKey(wbiImg) }
        }
    }
}
