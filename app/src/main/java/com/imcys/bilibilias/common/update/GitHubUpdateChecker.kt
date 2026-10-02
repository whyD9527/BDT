package com.imcys.bilibilias.common.update

import com.imcys.bilibilias.data.update.GitHubUpdateRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从 **GitHub Releases** 检查更新（取代原先只有 Google Play 的应用内更新）。
 *
 * 为什么换（2026-10-02）：alpha 渠道 `ENABLED_PLAY_APP_MODE = false` → 原生更新检查**从不执行**；
 * 而且侧载安装的包 Play 也查不到新版本。用户要求按 GitHub 来。
 *
 * 行为约定：
 * - **隐私门槛**：调用方必须先确认已同意隐私政策（`GitHubUpdateRules.shouldCheck`），否则**不发请求**；
 * - **失败静默**：网络异常/非 200/JSON 异常/版本解析失败 → 一律按"无更新"处理（不打扰用户）；
 * - **只读**：只 GET 一次 `releases/latest`；带 User-Agent 与超时；
 * - 结果与失败原因都通过 [log] 落诊断日志，便于真机复验与排障。
 */
object GitHubUpdateChecker {

    private const val LATEST_URL = "https://api.github.com/repos/whyD9527/BDT/releases/latest"
    private const val USER_AGENT = "BDT-Android-Updater"
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 10_000

    /** 有新版本时的结果 */
    data class UpdateInfo(
        val version: GitHubUpdateRules.SemVer,
        val tag: String,
        /** Release 说明（"本次更新"要点），用于应用内展示 */
        val notes: String,
        /** 本机 ABI 对应的 APK 下载地址；挑不到为 null（只提示、不给下载） */
        val apkUrl: String?,
    )

    /**
     * @param currentVersionName 本机版本名（如 `3.3.3`），用 `BuildConfig.VERSION_NAME`
     * @param abi 本机 ABI（如 `arm64-v8a`）
     * @param lastSkippedCode 用户点过"跳过此版本"的编码（`SemVer.encode()`），0 表示没跳过
     * @param log 诊断日志（tag, message）
     * @return 有新版本且没被跳过时返回 [UpdateInfo]，否则 null（也代表"无更新/查不到"）
     */
    suspend fun check(
        currentVersionName: String,
        abi: String,
        lastSkippedCode: Int,
        log: (String, String) -> Unit = { _, _ -> },
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        val local = GitHubUpdateRules.parseVersion(currentVersionName)
        if (local == null) {
            log("更新检查", "本地版本号解析失败：$currentVersionName")
            return@withContext null
        }

        val body = try {
            requestLatest()
        } catch (e: Exception) {
            // 失败静默：按"无更新"处理，但把原因写进诊断日志
            log("更新检查", "请求失败，按无更新处理：${e.javaClass.simpleName} ${e.message}")
            return@withContext null
        } ?: run {
            log("更新检查", "未取到 releases/latest，按无更新处理")
            return@withContext null
        }

        val tag = body.optString("tag_name").trim()
        val remote = GitHubUpdateRules.parseVersion(tag)
        if (!GitHubUpdateRules.isNewer(remote, local)) {
            log("更新检查", "已是最新：本地=$local 远端=${remote ?: tag}")
            return@withContext null
        }
        if (GitHubUpdateRules.shouldSkip(remote, lastSkippedCode)) {
            log("更新检查", "远端 $remote 已被用户跳过（lastSkipped=$lastSkippedCode）")
            return@withContext null
        }

        val assetsJson = body.optJSONArray("assets")
        val names = mutableListOf<String>()
        val urls = mutableMapOf<String, String>()
        if (assetsJson != null) {
            for (i in 0 until assetsJson.length()) {
                val a = assetsJson.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.isNotEmpty()) {
                    names += name
                    urls[name] = a.optString("browser_download_url")
                }
            }
        }
        val pickedName = GitHubUpdateRules.selectApkAsset(names, abi)
        val apkUrl = pickedName?.let { urls[it] }
        val notes = body.optString("body").trim()

        log(
            "更新检查",
            "发现新版本 tag=$tag（本地=$local，ABI=$abi，资产=${names.size}，选中=${pickedName ?: "无"}）",
        )
        UpdateInfo(
            version = remote!!,
            tag = tag,
            notes = notes,
            apkUrl = apkUrl,
        )
    }

    private fun requestLatest(): JSONObject? {
        val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
