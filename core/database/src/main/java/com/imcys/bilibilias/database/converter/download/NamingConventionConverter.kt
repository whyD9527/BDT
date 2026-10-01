package com.imcys.bilibilias.database.converter.download

import androidx.room.TypeConverter
import com.imcys.bilibilias.database.entity.download.NamingConventionInfo
import org.json.JSONObject

/**
 * `naming_convention_info` 列的 JSON 往返。
 *
 * ## 原来错在哪（2026-09-15 全量复审 H12）
 * 写入侧写的是实体自己的 `ruleType`（`Video = 1`、`Donghua = 2`），
 * 读取侧却写成 `0 -> Video / 1 -> Donghua`，**错位一格**：
 * - `Video`（1）被读回成 `Donghua`（`pTitle/author/cid` 全丢）；
 * - `Donghua`（2）落到 `else` → **null**；
 * - 而 `handleSuccessor` / `markCompletedWithoutMerge` 会把读回来的值原样写回 DB
 *   → 每下载完一集，这一行的命名信息就被永久改写/清空。
 * 另外读取侧还漏了 `p` / `collection_title` / `collection_season_title` / `season_title`
 * 四个字段，重启后按规则渲染出的名字会变。
 *
 * ## 现在的约定
 * - 取值与 [NamingConventionInfo] 保持一致（`0/1 -> Video`、`2 -> Donghua`；0 是历史兼容）；
 * - **坏 JSON / 认不出的 ruleType 一律返回 null**，不再把 `JSONException` 抛给 Room
 *   （那会让整个下载列表查询崩掉）；
 * - `null` 直接写成 SQL `NULL`，不再落一个 `"{}"` 脏值；
 * - 报文里没有的字段保持 `null`（用 `optStringOrNull`，而不是 `optString` 的 `""`）。
 */
class NamingConventionConverter {

    @TypeConverter
    fun fromString(value: String?): NamingConventionInfo? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val jsonObject = JSONObject(value)
            when (jsonObject.optInt("ruleType", -1)) {
                // 1 = Video（实体定义）；0 是更早版本可能写过的标记，一并当 Video 读
                0, 1 -> NamingConventionInfo.Video(
                    title = jsonObject.optStringOrNull("title"),
                    pTitle = jsonObject.optStringOrNull("pTitle"),
                    author = jsonObject.optStringOrNull("author"),
                    bvId = jsonObject.optStringOrNull("bvId"),
                    aid = jsonObject.optStringOrNull("aid"),
                    cid = jsonObject.optStringOrNull("cid"),
                    p = jsonObject.optStringOrNull("p"),
                    collectionTitle = jsonObject.optStringOrNull("collectionTitle"),
                    collectionSeasonTitle = jsonObject.optStringOrNull("collectionSeasonTitle"),
                )

                // 2 = Donghua（实体定义）
                2 -> NamingConventionInfo.Donghua(
                    title = jsonObject.optStringOrNull("title"),
                    episodeTitle = jsonObject.optStringOrNull("episodeTitle"),
                    episodeNumber = jsonObject.optStringOrNull("episodeNumber"),
                    cid = jsonObject.optStringOrNull("cid"),
                    seasonTitle = jsonObject.optStringOrNull("seasonTitle"),
                )

                else -> null
            }
        }.getOrNull()
    }

    @TypeConverter
    fun stringToDownloadStage(namingConventionInfo: NamingConventionInfo?): String? {
        // ⚠️ 不要写成 `"{}"`：那会变成"有 JSON、却解不出类型"的脏数据（读回来还是 null，白占一列）
        if (namingConventionInfo == null) return null
        return JSONObject().apply {
            put("ruleType", namingConventionInfo.ruleType)
            when (namingConventionInfo) {
                is NamingConventionInfo.Video -> {
                    put("title", namingConventionInfo.title)
                    put("pTitle", namingConventionInfo.pTitle)
                    put("author", namingConventionInfo.author)
                    put("bvId", namingConventionInfo.bvId)
                    put("aid", namingConventionInfo.aid)
                    put("cid", namingConventionInfo.cid)
                    put("p", namingConventionInfo.p)
                    put("collectionTitle", namingConventionInfo.collectionTitle)
                    put("collectionSeasonTitle", namingConventionInfo.collectionSeasonTitle)
                }

                is NamingConventionInfo.Donghua -> {
                    put("title", namingConventionInfo.title)
                    put("episodeTitle", namingConventionInfo.episodeTitle)
                    put("episodeNumber", namingConventionInfo.episodeNumber)
                    put("cid", namingConventionInfo.cid)
                    put("seasonTitle", namingConventionInfo.seasonTitle)
                }
            }
        }.toString()
    }

    /** `optString` 对缺失字段会返回 `""`，而实体字段是可空的 —— 这里保持"缺失 = null" */
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null
}
