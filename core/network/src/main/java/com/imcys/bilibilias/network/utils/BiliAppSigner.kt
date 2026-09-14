package com.imcys.bilibilias.network.utils

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import kotlin.random.Random


/**
 * B 站 App 端签名（`appkey` + `sign`）。
 *
 * 2026-09 加固（Step 3）：
 * - [appSign] 原先会**修改调用方传入的 map**（往里塞 `appkey`）——
 *   调用方若复用这个 map，会莫名多出一个参数。现在不改输入，只在内部副本上加。
 * - MD5 实现与 wbi 签名重复，已收拢到 [md5Hex]。
 * - 签名结果本身**没有变化**（同样的排序、同样的 `md5(query + APP_SEC)`）。
 */
object BiliAppSigner {
    const val APP_KEY: String = "4409e2ce8ffd12b8"
    const val APP_SEC: String = "59b43e04ad6965f34319062b478f83dd"

    private val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_0123456789".toCharArray()

    fun appSign(params: Map<String, String>): String? {
        // 内部副本：不改调用方的 map（原实现直接 put 到入参上）
        val sortedParams: MutableMap<String, String> = TreeMap<String, String>(params).apply {
            put("appkey", APP_KEY)
        }

        // 序列化参数
        val queryBuilder = StringBuilder()
        for (entry in sortedParams.entries) {
            if (queryBuilder.isNotEmpty()) {
                queryBuilder.append('&')
            }
            queryBuilder
                .append(URLEncoder.encode(entry.key, "UTF-8"))
                .append('=')
                .append(URLEncoder.encode(entry.value, "UTF-8"))
        }
        return md5Hex(queryBuilder.append(APP_SEC).toString())
    }

    private fun randomString(len: Int, rnd: Random = Random.Default): String =
        buildString(len) { repeat(len) { append(CHARS[rnd.nextInt(CHARS.size)]) } }


    val biliTvDeviceInfo by lazy {
        val now = Date()
        val sdf = SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.CHINA)
        val deviceId = randomString(20)
        val buvid = randomString(37)
        val fingerprint = sdf.format(now) + randomString(45)

        val p = mutableMapOf<String, String>()
        p["bili_local_id"] = deviceId
        p["build"] = "102801"
        p["buvid"] = buvid
        p["channel"] = "master"
        p["device"] = "OnePlus"
        p["device_id"] = deviceId
        p["device_name"] = "OnePlus7TPro"
        p["device_platform"] = "Android10OnePlusHD1910"
        p["fingerprint"] = fingerprint
        p["guid"] = buvid
        p["local_fingerprint"] = fingerprint
        p["local_id"] = buvid
        p["mobi_app"] = "android_tv_yst"
        p["networkstate"] = "wifi"
        p["platform"] = "android"
        p["sys_ver"] = "29"
        p
    }
}
