package com.imcys.bilibilias.network.plugin

import android.util.Log
import androidx.datastore.core.DataStore
import com.imcys.bilibilias.datastore.AppSettings
import com.imcys.bilibilias.datastore.isTv
import com.imcys.bilibilias.network.config.REFERER
import com.imcys.bilibilias.network.config.RequestRules
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.flow.first


class AutoBILIInfoPluginConfig {
    var appSetting: DataStore<AppSettings>? = null
}

/**
 * 自动补齐 B 站请求头。
 *
 * 规则本身**全部**来自 [RequestRules]（唯一真相源），这里只负责按顺序应用：
 *
 *  1. 媒体 CDN（命中 `bilivideo` / `akamaized` / …）→ 无条件补齐浏览器 UA + Referer；
 *  2. 解析平台 = TV 且命中 TV 端点 → **删掉** UA 与 Referer（TV 端接口的要求）；
 *  3. 其余请求 → 缺 Referer 时补默认 Referer。
 *
 * 历史教训：原实现在 TV 平台下把 CDN 请求的 UA 和 Referer 一起删掉，
 * 导致所有走 CDN 的下载被 403 拒绝。**CDN 分支必须排在 TV 规则前面**，
 * 这个顺序不是风格问题，而是修 bug 修出来的，别调整。
 */
val AutoBILIInfoPlugin = createClientPlugin("AutoBILIInfoPlugin", ::AutoBILIInfoPluginConfig) {

    val appSettings = pluginConfig.appSetting

    onRequest { request, _ ->
        val url = request.url.toString()
        val host = request.url.host

        // 1) 媒体 CDN：补齐（缺什么补什么，**不覆盖调用方已经给的具体 Referer**）
        val cdnKeyword = RequestRules.matchedMediaCdnKeyword(host)
        if (cdnKeyword != null) {
            // 只在命中媒体 CDN 时打印（不是每个请求都打），噪音很低。
            // 目的：抓一次 logcat 就能知道真实命中了哪些 CDN 主机、以及靠哪个关键字命中 ——
            // 这是判断"能否收窄 edge 关键字"的唯一依据（见 RequestRules 的说明）。
            Log.i("ASRequest", "媒体CDN请求: host=$host  命中关键字=$cdnKeyword")

            // ⚠️ 2026-09-15 复审 L3：原来是 `remove` + `append(DEFAULT_REFERER)` ——
            // 那会把 `DownloadExecutor` 精心拼的**具体稿件 Referer**（`buildRefererUrl`：
            // `/video/BV…` 或 `/bangumi/play/ss…`）直接抹掉，等于那段逻辑从来没生效过。
            // 这里改成"只有缺失时才补默认值"，行为对没带 Referer 的调用方不变。
            if (request.headers[HttpHeaders.Referrer] == null) {
                request.headers.append(HttpHeaders.Referrer, RequestRules.DEFAULT_REFERER)
            }
            if (request.headers[HttpHeaders.UserAgent] == null) {
                request.headers.append(HttpHeaders.UserAgent, RequestRules.DEFAULT_USER_AGENT)
            }
            return@onRequest
        }

        // 2) TV 平台的这些端点：要求不带 UA / Referer
        if (appSettings?.data?.first()?.videoParsePlatform?.isTv == true &&
            RequestRules.shouldStripHeadersOnTvPlatform(url)
        ) {
            request.headers.remove(HttpHeaders.Referrer)
            request.headers.remove(HttpHeaders.UserAgent)
            return@onRequest
        }

        // 3) 其余：补默认 Referer
        if (request.headers[REFERER] == null) {
            request.headers.append(REFERER, RequestRules.DEFAULT_REFERER)
        }
    }
}
