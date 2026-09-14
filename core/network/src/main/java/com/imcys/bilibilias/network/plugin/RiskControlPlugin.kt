package com.imcys.bilibilias.network.plugin

import com.imcys.bilibilias.common.event.sendPlayVoucherErrorEvent
import com.imcys.bilibilias.network.config.API.BILIBILI.WEB_PGC_PLAYER_URL
import com.imcys.bilibilias.network.config.API.BILIBILI.WEB_VIDEO_PLAYER_NO_WEBI_URL
import com.imcys.bilibilias.network.config.API.BILIBILI.WEB_VIDEO_PLAYER_URL
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request

/** 需要检查风控字段（`v_voucher`）的播放接口白名单 */
private val PLAY_VOUCHER_WATCHED_URLS = listOf(
    WEB_VIDEO_PLAYER_URL,
    WEB_VIDEO_PLAYER_NO_WEBI_URL,
    WEB_PGC_PLAYER_URL,
)

/**
 * 检测播放接口是否被风控拦截（响应体里出现 `v_voucher`），命中则通知界面。
 *
 * 2026-09 加固（Step 3）：**先判 URL，再读 body**。
 * 原实现不分请求类型，把每个响应体都整份读成字符串 —— 等于给所有接口
 * 都加了一次完整的内存拷贝与解码，而真正要检查的只有 3 个播放接口。
 *
 * 判定结果与原来**完全一致**：原先也是「body 含 v_voucher **且** URL 命中白名单」。
 */
val RiskControlPlugin = createClientPlugin("RiskControlPlugin") {
    onResponse { response ->
        if (response.isSSE()) return@onResponse

        val url = response.request.url.toString()
        if (PLAY_VOUCHER_WATCHED_URLS.none { url.contains(it) }) return@onResponse

        if (response.bodyAsText().contains("v_voucher")) {
            sendPlayVoucherErrorEvent()
        }
    }
}
