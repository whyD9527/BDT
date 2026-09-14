package com.imcys.bilibilias.network.di

import android.util.Log
import com.imcys.bilibilias.common.data.CommonBuildConfig
import com.imcys.bilibilias.datastore.userAppSettingsStore
import com.imcys.bilibilias.network.AsCookiesStorage
import com.imcys.bilibilias.network.BuildConfig
import com.imcys.bilibilias.network.config.RequestRules
import com.imcys.bilibilias.network.plugin.AutoBILIInfoPlugin
import com.imcys.bilibilias.network.plugin.RiskControlPlugin
import com.imcys.bilibilias.network.plugin.RoamPlugin
import com.imcys.bilibilias.network.service.BILIBILITVAPIService
import com.imcys.bilibilias.network.service.BILIBILIWebAPIService
import com.imcys.bilibilias.network.service.GitHubReleaseService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.BrowserUserAgent
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.serialization.kotlinx.protobuf.protobuf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds


@OptIn(ExperimentalSerializationApi::class)
val netWorkModule = module {
    single {
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            allowSpecialFloatingPointValues = true
            explicitNulls = false
        }
    }

    single {
        OkHttpClient.Builder()
            .pingInterval(1, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .apply {
                        // 只补 Referer（不补 UA）—— 这条"下载走 OkHttp"的规则同样由
                        // RequestRules 统一给值，别再在这里写字面量
                        if (!chain.request().headers("Referer").isNotEmpty()) {
                            header("Referer", RequestRules.DEFAULT_REFERER)
                        }
                    }
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    @OptIn(ExperimentalSerializationApi::class)
    single {
        AsCookiesStorage(get(), get())
    }

    single {
        HttpClient(CIO) {
            BrowserUserAgent()
            install(HttpTimeout) {
                requestTimeoutMillis = 10000
            }
            install(SSE) {
                maxReconnectionAttempts = 4
                reconnectionTime = 2.seconds
            }
            install(AutoBILIInfoPlugin){
                appSetting = androidContext().userAppSettingsStore
            }
            install(RoamPlugin) {
                // 作者自建的漫游代理 bili-api.misakamoe.com 已失效：
                // 实测 season 接口返回 -404「请检查填入的服务器地址是否有效」，
                // playurl 返回 -412「请求被拦截」，继续走它会让番剧解析与下载全部失败。
                // 因此默认不做任何域名替换（直连 api.bilibili.com）。
                // 若你自建了可用代理，把 "api.bilibili.com" to "你的代理域名" 填回来即可恢复漫游。
                domainReplacement = emptyMap()
                biliUsersDao = get()
                appSetting = androidContext().userAppSettingsStore
            }
            install(RiskControlPlugin)
            install(ContentNegotiation) {
                json(get())
                protobuf(contentType = ContentType.Application.OctetStream)
            }
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 3)
                exponentialDelay()
            }
            install(HttpCookies) {
                storage = get<AsCookiesStorage>()
            }
            if (BuildConfig.DEBUG) {
                install(Logging) {
                    logger = object : Logger {
                        private val json: Json = get()

                        override fun log(message: String) {
                            val formattedMessage = try {
                                if (message.contains("{") && message.contains("}")) {
                                    val jsonStart = message.indexOf("{")
                                    val jsonEnd = message.lastIndexOf("}") + 1
                                    val prefix = message.substring(0, jsonStart)
                                    val jsonBody = message.substring(jsonStart, jsonEnd)
                                    val suffix = message.substring(jsonEnd)

                                    // 解析并重新格式化 JSON
                                    val jsonElement = json.parseToJsonElement(jsonBody)
                                    val formattedJson =
                                        if (message.contains("application/octet-stream")) "" else
                                            json.encodeToString(
                                                JsonElement.serializer(),
                                                jsonElement
                                            )

                                    "$prefix\n$formattedJson$suffix"
                                } else {
                                    message
                                }
                            } catch (e: Exception) {
                                message
                            }

                            Log.d("Ktor", formattedMessage)
                        }
                    }
                    level = LogLevel.ALL
                }
            }
        }
    }

    // 专用于下载的纯净HttpClient，不装任何业务插件
    single(qualifier = named("DownloadHttpClient")) {
        HttpClient(CIO) {
            BrowserUserAgent()
            install(HttpTimeout) {
                requestTimeoutMillis = 60000 // 下载可适当延长
            }
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 3)
                exponentialDelay()
            }
            install(HttpCookies) {
                storage = get<AsCookiesStorage>()
            }
            install(AutoBILIInfoPlugin){
                appSetting = androidContext().userAppSettingsStore
            }
            if (BuildConfig.DEBUG){
                install(Logging) {
                    logger = object : Logger {
                        override fun log(message: String) {
                            Log.d("DownloadKtor", message)
                        }
                    }
                    level = LogLevel.HEADERS
                }
            }

        }
    }


    // WebAPI
    single {
        BILIBILIWebAPIService(get())
    }
    // TvAPI
    single {
        BILIBILITVAPIService(get())
    }
    // 更新检查：读本仓库的 GitHub Release（不再用已易主的作者服务器，见交接文档第七轮）
    single {
        GitHubReleaseService(get())
    }
}
