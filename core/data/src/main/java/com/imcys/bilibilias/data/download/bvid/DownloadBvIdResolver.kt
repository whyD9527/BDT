package com.imcys.bilibilias.data.download.bvid

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 「这次下载该用哪个 bvid」的纯规则（可单测）。
 *
 * ## 原来的写法（第十八轮审查的低危项）
 * `VideoInfoFetcher.getSegmentBvId` 长这样：
 * ```kotlin
 * if (segment.taskId != null) { …取子任务的 platformId… }
 * else {
 *     try {
 *         val platformInfo = json.decodeFromString<Map<String, Any>>(segment.platformInfo)
 *         platformInfo["bvid"] as? String          // ← 解不出来/没有这个字段 → null，**不抛异常**
 *     } catch (e: Exception) {                     // ← 只有"反序列化失败"才走这里
 *         …再按 nodeId 反查节点…
 *     }
 * }
 * ```
 * 两处脆弱：
 * 1. **兜底挂在"解码必然抛异常"上**。而 `platformInfo` 对普通视频页存的是
 *    `BILIVideoViewInfo.Page`（**根本没有 `bvid` 字段**）、对番剧集存的是
 *    `BILIDonghuaSeasonInfo.Episode`（`bvid` 可空）。这两种情况下
 *    `decodeFromString` **正常返回**、`platformInfo["bvid"] as? String` 只是得到 null ——
 *    catch 分支**一次都不会执行**，"按 nodeId 反查"这条兜底等于没写。
 *    反过来，一旦哪天 `platformInfo` 变成数组/非法 JSON，就会走进与"没有 bvid"
 *    完全无关的分支 —— 两种语义被挤进同一个 catch。
 * 2. 于是"取不到 bvid"这件事被静默接受：调用方拿到 null 照样去请求。
 *
 * ## 现在的规则
 * 三条候选**各自独立判断**，不再用异常当分支：
 * 1. 子任务（合集章节里的分 P）存在 → 它的 `platformId` 就是 bvid；
 * 2. `platformInfo` 里**显式**有 `bvid` 字段 → 用它（**有字段但值为 null = 明确没有**，
 *    此时不再往下兜底；字段缺失才继续）；
 * 3. 都没有 → 用所属节点的 `platformId`（普通视频页节点存的就是 BV 号）。
 *
 * 第 3 条是这次补上的：它原来被埋在 catch 里，实际从没执行过。
 */
object DownloadBvIdResolver {

    /** BV 号的形状：`BV` 开头 + 10 位 base58 字符（B 站现行格式） */
    private val bvIdPattern = Regex("^BV[0-9A-Za-z]{10}$")

    /**
     * 按优先级挑一个 bvid。
     *
     * @param taskPlatformId 关联子任务的 `platformId`（没有子任务时传 null）
     * @param platformInfoJson segment 上存的平台 JSON（可能是 Page / Episode / 互动故事…）
     * @param nodePlatformId 所属节点的 `platformId`（调用方按需查库后传进来）
     */
    fun resolve(
        taskPlatformId: String?,
        platformInfoJson: String?,
        nodePlatformId: String?,
    ): String? {
        taskPlatformId?.takeIf { this.isValidBvId(it) }?.let { return it }

        when (val fromJson = bvidFromPlatformInfo(platformInfoJson)) {
            // 字段存在但为空：这是"明确没有"，不再兜底（继续兜底只会掩盖上游数据问题）
            PlatformInfoBvId.Present -> return null
            is PlatformInfoBvId.Value -> return fromJson.bvId
            PlatformInfoBvId.Absent -> Unit
        }

        return nodePlatformId?.takeIf { this.isValidBvId(it) }
    }

    /**
     * 从平台 JSON 里读 `bvid`，并区分"没这个字段"与"有这个字段但为空"。
     *
     * 解析失败一律按"没有"处理（不再抛异常、不再靠异常改分支）。
     */
    fun bvidFromPlatformInfo(platformInfoJson: String?): PlatformInfoBvId {
        if (platformInfoJson.isNullOrBlank()) return PlatformInfoBvId.Absent
        val obj: JsonObject = runCatching {
            Json.parseToJsonElement(platformInfoJson).jsonObject
        }.getOrNull() ?: return PlatformInfoBvId.Absent

        if (!obj.containsKey("bvid")) return PlatformInfoBvId.Absent
        val value = (obj["bvid"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (value.isNullOrBlank()) PlatformInfoBvId.Present else PlatformInfoBvId.Value(value)
    }

    /** `platformId` 是不是一个 BV 号（数字 CID / 章节 ID / 季度 ID 都不是） */
    fun isValidBvId(value: String?): Boolean = value != null && bvIdPattern.matches(value)

    /** 平台 JSON 里 `bvid` 字段的三种状态 */
    sealed interface PlatformInfoBvId {
        /** 没有这个字段（或 JSON 根本解不出对象） */
        data object Absent : PlatformInfoBvId

        /** 有这个字段但值为空/空白 */
        data object Present : PlatformInfoBvId

        /** 有这个字段且非空 */
        data class Value(val bvId: String) : PlatformInfoBvId
    }
}
