package com.imcys.bilibilias.network.service

import com.imcys.bilibilias.network.model.app.GitHubReleaseInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header

/**
 * 读取**本仓库**的 GitHub Release，用于「有新版本」提示。
 *
 * 为什么从作者服务器换到这里：原来那份更新信息来自 `api.misakamoe.com`，
 * 而该域名已不在原作者控制下 —— 它返回的 `url` 会直接变成"立即更新"弹窗并打开任意地址，
 * 是一条可被远程利用的投递路径（详见交接文档第七轮）。
 * 现在读的是我们自己的仓库，而且只用它的版本号、说明和 Release 页面地址。
 *
 * **任何异常都返回 null**（网络不通 / 被墙 / 限流 / 仓库没有 Release），
 * 由调用方静默处理：更新提示失败绝不该打扰用户。
 */
class GitHubReleaseService(private val httpClient: HttpClient) {

    suspend fun getLatestRelease(): GitHubReleaseInfo? = runCatching {
        httpClient.get(LATEST_RELEASE_URL) {
            header("Accept", "application/vnd.github+json")
        }.body<GitHubReleaseInfo>()
    }.getOrNull()

    companion object {
        const val REPO = "whyD9527/BDT"
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"

        /** Release 列表页：更新提示里作为兜底跳转地址 */
        const val RELEASES_PAGE = "https://github.com/$REPO/releases"
    }
}
