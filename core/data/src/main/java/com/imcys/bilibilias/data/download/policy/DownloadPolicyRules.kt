package com.imcys.bilibilias.data.download.policy

/**
 * 下载策略的纯规则（仅 Wi-Fi、限速）—— 抽出来是为了能在 JVM 单测里钉住。
 */
object DownloadPolicyRules {

    /**
     * 「仅 Wi-Fi」时该不该拦下这次下载。
     *
     * @param wifiOnly 用户是否开了"仅 Wi-Fi 下载"
     * @param isWifi 当前网络是否是 Wi-Fi
     * @return true = 拦住（提示用户）
     */
    fun shouldBlockForWifiOnly(wifiOnly: Boolean, isWifi: Boolean): Boolean = wifiOnly && !isWifi

    /**
     * 按限速算"这批字节写完该等多久"（毫秒）。
     *
     * 语义：把"已经写掉的字节"和"按限速在已经过去的时间里**允许**写掉的字节"比较，
     * 超了才等 —— 这样网络本来就比限速慢时**一点都不会拖慢**（返回 0）。
     *
     * @param bytesWritten 本次下载**累计**已写字节
     * @param elapsedMs 本次下载**累计**已用毫秒
     * @param limitKbps 限速（KB/s）；<= 0 表示不限速
     */
    fun sleepMillisFor(bytesWritten: Long, elapsedMs: Long, limitKbps: Int): Long {
        if (limitKbps <= 0) return 0
        if (elapsedMs <= 0) return 0
        val bytesPerMs = limitKbps.toLong() * 1024L / 1000L
        if (bytesPerMs <= 0L) return 0
        val allowed = bytesPerMs * elapsedMs
        if (bytesWritten <= allowed) return 0
        return ((bytesWritten - allowed) / bytesPerMs).coerceAtLeast(0L)
    }
}
