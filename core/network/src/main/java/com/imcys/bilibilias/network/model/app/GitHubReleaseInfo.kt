package com.imcys.bilibilias.network.model.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GitHub Releases API（`/releases/latest`）的精简模型。
 *
 * 只声明我们真正用到的字段，其余靠 OkHttp/Ktor 的 `ignoreUnknownKeys = true` 忽略。
 *
 * **所有字段都有默认值**：远端数据结构由 GitHub 控制，一旦字段缺失/改名，
 * 我们希望得到"一个空对象"而不是反序列化异常（后者会变成界面上一串报错）。
 */
@Serializable
data class GitHubReleaseInfo(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("body") val body: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
)
