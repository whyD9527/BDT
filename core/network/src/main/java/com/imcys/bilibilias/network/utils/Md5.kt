package com.imcys.bilibilias.network.utils

import java.security.MessageDigest

/**
 * B 站签名用的 MD5 十六进制摘要。
 *
 * App 签名（[BiliAppSigner]）与 wbi 签名（[WebiTokenUtils]）都要算 MD5，
 * 原先各写了一份实现 —— 签名相关的东西只该有一份，收拢到这里。
 *
 * 失败返回 null，由调用方决定怎么降级（原两处各自的降级行为保持不变）。
 *
 * 注意：这里用 `b.toInt() and 0xff` 而不是 `String.format("%02x", b)`。
 * 字节是带符号的，直接格式化容易出歧义；先掩码成 0..255 才是明确的写法。
 */
internal fun md5Hex(input: String): String? = try {
    val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(2 * digest.size)
    for (b in digest) {
        sb.append(String.format("%02x", b.toInt() and 0xff))
    }
    sb.toString()
} catch (e: Exception) {
    e.printStackTrace()
    null
}
