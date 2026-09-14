package com.imcys.bilibilias.download

import com.imcys.bilibilias.data.download.naming.NamingConventionRenderer
import com.imcys.bilibilias.data.repository.AppSettingsRepository
import com.imcys.bilibilias.database.entity.download.FileNamePlaceholder
import com.imcys.bilibilias.database.entity.download.NamingConventionInfo
import com.imcys.bilibilias.database.entity.download.donghuaNamingRules
import com.imcys.bilibilias.database.entity.download.videoNamingRules
import kotlinx.coroutines.flow.first

/**
 * 命名规则处理器
 * 负责根据命名规则生成文件名
 */
class NamingConventionHandler(
    private val appSettingsRepository: AppSettingsRepository
) {
    /**
     * 根据命名规则构建文件名
     */
    suspend fun buildFileName(
        conventionInfo: NamingConventionInfo?,
        fileExtension: String
    ): String {
        val namingRule = when (conventionInfo) {
            is NamingConventionInfo.Video -> {
                appSettingsRepository.appSettingsFlow.first().videoNamingRule
            }
            is NamingConventionInfo.Donghua -> {
                appSettingsRepository.appSettingsFlow.first().bangumiNamingRule
            }
            else -> return "unknown.$fileExtension"
        }

        return buildFileNameWithConvention(namingRule, conventionInfo, fileExtension)
    }

    /**
     * 应用命名规则
     *
     * ⚠️ 真正的渲染在 **`core:data` 的 `NamingConventionRenderer`** 里（纯函数、有单测）。
     * 原来这段是就地实现的，而它踩过一个坑：每替换一个占位符就对**整个累计字符串**做
     * `Regex("_+")` 塌缩，于是**标题里本来就有的下划线被一起吃掉了**
     * （`我的_世界` → `我的世界`）。那个塌缩本来只是给"某个占位符取值为空、
     * 留下多余分隔符"兜底的。渲染器把"内容里的下划线"与"分隔用的下划线"分开处理，
     * 并配了单测守住（`:app` 的单测在本机跑不了，见交接文档第十四轮第 1 条）。
     *
     * 这里只负责把"规则 + 取值"翻译成渲染器要的 `Map`，不放任何拼字符串的逻辑。
     */
    private fun buildFileNameWithConvention(
        namingRule: String,
        conventionInfo: NamingConventionInfo?,
        fileSuffix: String
    ): String {
        val placeholders = when (conventionInfo) {
            is NamingConventionInfo.Video -> videoNamingRules.map { it.placeholder }
            is NamingConventionInfo.Donghua -> donghuaNamingRules.map { it.placeholder }
            else -> emptyList()
        }

        val values: Map<String, String?> = when (conventionInfo) {
            is NamingConventionInfo.Video -> mapOf(
                FileNamePlaceholder.Video.Title.placeholder to conventionInfo.title,
                FileNamePlaceholder.Video.PTitle.placeholder to conventionInfo.pTitle,
                FileNamePlaceholder.Video.Author.placeholder to conventionInfo.author,
                FileNamePlaceholder.Video.BvId.placeholder to conventionInfo.bvId,
                FileNamePlaceholder.Video.Aid.placeholder to conventionInfo.aid,
                FileNamePlaceholder.Video.Cid.placeholder to conventionInfo.cid,
                FileNamePlaceholder.Video.P.placeholder to conventionInfo.p,
                FileNamePlaceholder.Video.CollectionTitle.placeholder to conventionInfo.collectionTitle,
                FileNamePlaceholder.Video.CollectionSeasonTitle.placeholder to
                    conventionInfo.collectionSeasonTitle,
            )

            is NamingConventionInfo.Donghua -> mapOf(
                FileNamePlaceholder.Donghua.Cid.placeholder to conventionInfo.cid,
                FileNamePlaceholder.Donghua.EpisodeNumber.placeholder to conventionInfo.episodeNumber,
                FileNamePlaceholder.Donghua.EpisodeTitle.placeholder to conventionInfo.episodeTitle,
                FileNamePlaceholder.Donghua.Title.placeholder to conventionInfo.title,
                FileNamePlaceholder.Donghua.SeasonTitle.placeholder to conventionInfo.seasonTitle,
            )

            else -> emptyMap()
        }

        return NamingConventionRenderer.render(
            template = namingRule,
            placeholders = placeholders,
            values = values,
            fileExtension = fileSuffix,
        )
    }
}
