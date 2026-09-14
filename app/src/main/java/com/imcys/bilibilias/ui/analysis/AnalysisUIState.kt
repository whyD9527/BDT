package com.imcys.bilibilias.ui.analysis

import com.imcys.bilibilias.common.utils.TextType
import com.imcys.bilibilias.data.model.download.DownloadViewInfo
import com.imcys.bilibilias.data.model.video.ASLinkResultType
import com.imcys.bilibilias.datastore.AppSettings

data class AnalysisUIState(
    val inputAsText: String = "",
    val linkType: TextType? = null,
    val asLinkResultType: ASLinkResultType? = null,
    val isBILILogin: Boolean = false,
    val downloadInfo: DownloadViewInfo? = null,
    val isCreateDownloadLoading: Boolean = false,
    val analysisBaseInfo: AnalysisBaseInfo = AnalysisBaseInfo(),
    val isSelectSingleModel: Boolean = true,
    val episodeListMode: AppSettings.EpisodeListMode = AppSettings.EpisodeListMode.EpisodeListMode_Grid,
    /**
     * 上一次解析**没能产出结果**的原因；null 表示没有错误。
     *
     * 存在的理由：原先五条失败路径（短链 `onFailure {}`、识别 `null -> {}`、接口 `else -> {}`）
     * 全都什么都不做，界面就停在只有输入框的空白页 —— 用户无从判断是没识别出来、
     * 被风控、还是 App 卡死了。见交接文档第十八轮审查。
     */
    val parseErrorMessage: String? = null,
    )