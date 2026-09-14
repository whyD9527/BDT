package com.imcys.bilibilias.download

import com.imcys.bilibilias.common.utils.autoRequestRetry
import com.imcys.bilibilias.data.download.bvid.DownloadBvIdResolver
import com.imcys.bilibilias.data.model.download.DownloadViewInfo
import com.imcys.bilibilias.data.repository.DownloadTaskRepository
import com.imcys.bilibilias.data.repository.VideoInfoRepository
import com.imcys.bilibilias.database.entity.download.DownloadMode
import com.imcys.bilibilias.database.entity.download.DownloadSegment
import com.imcys.bilibilias.database.entity.download.DownloadSubTaskType
import com.imcys.bilibilias.database.entity.download.DownloadTaskNodeType
import com.imcys.bilibilias.database.entity.download.NamingConventionInfo
import com.imcys.bilibilias.network.ApiStatus
import com.imcys.bilibilias.network.NetWorkResult
import com.imcys.bilibilias.network.model.video.BILIDonghuaOgvPlayerInfo
import com.imcys.bilibilias.network.model.video.BILIDonghuaPlayerInfo
import com.imcys.bilibilias.network.model.video.BILIDonghuaPlayerSynthesize
import com.imcys.bilibilias.network.model.video.BILIVideoDash
import com.imcys.bilibilias.network.model.video.BILIVideoDurl
import com.imcys.bilibilias.network.model.video.BILIVideoPlayerInfo
import com.imcys.bilibilias.network.model.video.convertAudioQualityIdValue
import com.imcys.bilibilias.network.model.video.convertVideoQualityIdValue

/**
 * 视频信息获取器
 * 负责从Bilibili API获取视频播放信息并提取下载URL
 */
class VideoInfoFetcher(
    private val videoInfoRepository: VideoInfoRepository,
    private val downloadTaskRepository: DownloadTaskRepository,
) {
    private companion object {
        const val TAG = "ASVideoInfo"
    }

    /**
     * 获取视频播放信息
     */
    suspend fun fetchVideoPlayerInfo(
        segment: DownloadSegment,
        nodeType: DownloadTaskNodeType,
        downloadViewInfo: DownloadViewInfo
    ): NetWorkResult<Any?> {
        return when (nodeType) {
            DownloadTaskNodeType.BILI_VIDEO_INTERACTIVE,
            DownloadTaskNodeType.BILI_VIDEO_PAGE,
            DownloadTaskNodeType.BILI_VIDEO_SECTION_EPISODES -> {
                autoRequestRetry {
                    videoInfoRepository.getVideoPlayerInfo(
                        cid = segment.platformId.toLong(),
                        bvId = getSegmentBvId(segment),
                        curLanguage = downloadViewInfo.selectAudioLanguage?.lang,
                        curProductionType = downloadViewInfo.selectAudioLanguage?.productionType
                    )
                }
            }

            DownloadTaskNodeType.BILI_DONGHUA_EPISOD,
            DownloadTaskNodeType.BILI_DONGHUA_SEASON,
            DownloadTaskNodeType.BILI_DONGHUA_SECTION -> {
                autoRequestRetry {
                    videoInfoRepository.getDonghuaPlayerInfo(
                        epId = segment.platformId.toLong(),
                        null
                    )
                }
            }

            else -> throw IllegalStateException("缓存类型不支持: ${nodeType.name}")
        }
    }

    /**
     * 从播放信息中提取视频数据（Dash或Durl）
     */
    fun extractVideoData(
        playerInfo: NetWorkResult<Any?>,
        downloadViewInfo: DownloadViewInfo
    ): Any? {
        if (playerInfo.status != ApiStatus.SUCCESS) {
            return null
        }

        return when (val result = playerInfo.data) {
            is BILIDonghuaPlayerInfo -> result.dash ?: result.durls?.firstOrNull {
                it.quality == downloadViewInfo.selectVideoQualityId
            }?.durl?.first() ?: result.durls?.first()?.durl?.first()

            is BILIDonghuaOgvPlayerInfo -> result.videoInfo.dash
                ?: result.videoInfo.durls?.firstOrNull {
                    it.quality == downloadViewInfo.selectVideoQualityId
                }?.durl?.first() ?: result.videoInfo.durls?.first()?.durl?.first()

            is BILIDonghuaPlayerSynthesize -> {
                result.dash
                    ?: result.durls?.firstOrNull {
                        it.quality == downloadViewInfo.selectVideoQualityId
                    }?.durl?.first() ?: result.durls?.first()?.durl?.first()
            }

            is BILIVideoPlayerInfo -> result.dash ?: result.durls?.firstOrNull {
                it.quality == downloadViewInfo.selectVideoQualityId
            }?.durl?.first() ?: result.durls?.first()?.durl?.first()

            else -> null
        }
    }

    /**
     * 从视频数据中获取下载URL
     */
    suspend fun getDownloadUrl(
        videoData: Any,
        subTaskType: DownloadSubTaskType,
        downloadViewInfo: DownloadViewInfo,
        namingConventionInfo: NamingConventionInfo?,
        onQuality: suspend (String) -> Unit = { _ -> },
    ): String? {



        return when (videoData) {
            is BILIVideoDash -> {
                when (subTaskType) {
                    DownloadSubTaskType.VIDEO -> {
                        val video =  selectVideoQuality(
                            videoData.video,
                            downloadViewInfo
                        )
                        if (downloadViewInfo.downloadMode != DownloadMode.AUDIO_ONLY) {
                            onQuality(convertVideoQualityIdValue(video.id))
                        }
                        video.finalUrl
                    }

                    DownloadSubTaskType.AUDIO -> {
                        val audio = selectAudioQuality(
                            videoData,
                            downloadViewInfo
                        )
                        if (downloadViewInfo.downloadMode == DownloadMode.AUDIO_ONLY) {
                            onQuality(convertAudioQualityIdValue(audio.id))
                        }
                        audio.finalUrl
                    }
                }
            }

            is BILIVideoDurl -> {
                when (subTaskType) {
                    DownloadSubTaskType.VIDEO -> {
                        onQuality(convertVideoQualityIdValue(downloadViewInfo.selectVideoQualityId ?: 0L))
                        videoData.url
                    }
                    DownloadSubTaskType.AUDIO -> null
                }
            }

            else -> null
        }
    }

    /**
     * 选择视频质量
     */
    private fun selectVideoQuality(
        videos: List<BILIVideoDash.Video>,
        downloadViewInfo: DownloadViewInfo
    ): BILIVideoDash.Video {
        return videos.filter {
            it.id == downloadViewInfo.selectVideoQualityId
        }.firstOrNull {
            it.codecs.contains(downloadViewInfo.selectVideoCode)
        } ?: videos.firstOrNull() ?: throw IllegalStateException("无可用视频流")
    }

    /**
     * 选择音频质量
     */
    private fun selectAudioQuality(
        dash: BILIVideoDash,
        downloadViewInfo: DownloadViewInfo
    ): BILIVideoDash.Audio {
        return dash.audio.firstOrNull {
            it.id == downloadViewInfo.selectAudioQualityId
        } ?: dash.dolby?.audio?.firstOrNull {
            it.id == downloadViewInfo.selectAudioQualityId
        } ?: dash.flac?.audio?.takeIf {
            it.id == downloadViewInfo.selectAudioQualityId
        } ?: dash.audio.firstOrNull() ?: throw IllegalStateException("无可用音频流")
    }

    /**
     * 获取 segment 对应的 bvId。
     *
     * 具体取哪一条由 `DownloadBvIdResolver` 决定（纯规则、有单测），这里只负责查库：
     *
     * 1. 有子任务（合集章节里的分 P）→ 子任务的 `platformId`；
     * 2. `platformInfo` 里**显式**有 `bvid` 字段 → 用它；
     * 3. 都没有 → 所属节点的 `platformId`。
     *
     * ⚠️ 第 3 条是这次补上的兜底。改造前它是这么写的：
     * ```kotlin
     * try { platformInfo["bvid"] as? String } catch (e: Exception) { …按 nodeId 反查… }
     * ```
     * 而 `platformInfo` 对普通视频页存的是 `BILIVideoViewInfo.Page`、对番剧集存的是
     * `BILIDonghuaSeasonInfo.Episode` —— `decodeFromString` 这两种都**正常返回**、
     * `platformInfo["bvid"] as? String` 只是得到 null，**根本不抛异常**，
     * 所以"按 nodeId 反查"那条兜底**一次都没执行过**。现在按"字段缺失 / 字段为空"
     * 显式分情况，不再拿异常当分支。
     *
     * `DownloadBvIdResolver` 只认真正 BV 号形状的值，所以节点 `platformId` 是
     * 数字（章节 / 季度 ID）时也不会被当成 bvid 用出去。
     */
    private suspend fun getSegmentBvId(segment: DownloadSegment): String? {
        val taskPlatformId = segment.taskId
            ?.let { downloadTaskRepository.getTaskById(it)?.platformId }

        // 节点查库**按需**做：子任务那条已经命中时就不必多打一次查询。
        // （规则本身是纯函数 —— 查库这件事留在调用方，`DownloadBvIdResolver` 才好单测。）
        val nodePlatformId = if (DownloadBvIdResolver.isValidBvId(taskPlatformId)) {
            null
        } else {
            downloadTaskRepository.getTaskByNodeId(segment.nodeId)?.platformId
        }

        val resolved = DownloadBvIdResolver.resolve(
            taskPlatformId = taskPlatformId,
            platformInfoJson = segment.platformInfo,
            nodePlatformId = nodePlatformId,
        )

        // 取证日志（长期保留）：TV 平台那条路必须带上 bvid，而"到底解析出了哪个、
        // 是从哪儿来的"光看代码看不出来 —— 真机验证时 grep 这一行即可。
        android.util.Log.d(
            TAG,
            "bvid 解析: platformId=${segment.platformId} nodeId=${segment.nodeId} " +
                "子任务=$taskPlatformId 节点=$nodePlatformId 结果=$resolved",
        )
        return resolved
    }
}
