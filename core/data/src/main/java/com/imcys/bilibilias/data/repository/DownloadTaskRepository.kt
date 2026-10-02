package com.imcys.bilibilias.data.repository

import com.imcys.bilibilias.data.download.naming.PageNamingRules
import com.imcys.bilibilias.data.model.download.DownloadTaskTree
import com.imcys.bilibilias.data.model.download.DownloadTreeNode
import com.imcys.bilibilias.data.model.download.DownloadViewInfo
import com.imcys.bilibilias.data.model.download.MediaContainerConfig
import com.imcys.bilibilias.data.model.video.ASLinkResultType
import com.imcys.bilibilias.database.dao.BILIUsersDao
import com.imcys.bilibilias.data.download.record.DownloadRecordReuseRules
import com.imcys.bilibilias.database.dao.DownloadTaskDao
import com.imcys.bilibilias.database.entity.download.DownloadMode
import com.imcys.bilibilias.database.entity.download.DownloadPlatform
import com.imcys.bilibilias.database.entity.download.DownloadSegment
import com.imcys.bilibilias.database.entity.download.DownloadState
import com.imcys.bilibilias.database.entity.download.DownloadTask
import com.imcys.bilibilias.database.entity.download.DownloadTaskNode
import com.imcys.bilibilias.database.entity.download.DownloadTaskNodeType
import com.imcys.bilibilias.database.entity.download.DownloadTaskType
import com.imcys.bilibilias.database.entity.download.NamingConventionInfo
import com.imcys.bilibilias.datastore.source.UsersDataSource
import com.imcys.bilibilias.network.ApiStatus
import com.imcys.bilibilias.network.FlowNetWorkResult
import com.imcys.bilibilias.network.NetWorkResult
import com.imcys.bilibilias.network.model.video.BILIDonghuaSeasonInfo
import com.imcys.bilibilias.network.model.video.BILIVideoViewInfo
import com.imcys.bilibilias.network.model.video.filterWithSinglePage
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.Date
import kotlin.text.ifEmpty

class DownloadTaskRepository(
    private val json: Json,
    private val downloadTaskDao: DownloadTaskDao,
    private val videoInfoRepository: VideoInfoRepository,
    private val appSettingsRepository: AppSettingsRepository,
    private val usersDataSource: UsersDataSource,
    private val biliUsersDao: BILIUsersDao
) {

    /**
     * 「查已有记录 → 决定插/改」这段必须串行（2026-09-15 复审 M9 的一部分）。
     *
     * `download_segment` 上**没有** `(node_id, platform_id)` 唯一约束、全仓也没有事务，
     * 所以两个并发调用都会读到 `existing == null` → 各插一条 → 又回到
     * "两条同名记录、其中一条永远指向不存在的文件"。UI 没有禁用按钮，双击就能触发。
     * （真正彻底的修法是加唯一索引 + 事务，那要动 Room schema/迁移，按上一份审计的建议单独立批。）
     */
    private val createTaskMutex = Mutex()

    /**
     * 统一入口：根据链接类型创建下载任务
     */
    suspend fun createDownloadTask(
        asLinkResultType: ASLinkResultType,
        downloadViewInfo: DownloadViewInfo
    ): Result<DownloadTaskTree> = createTaskMutex.withLock {
        val result = when (asLinkResultType) {
            is ASLinkResultType.BILI.Donghua -> {
                createDonghuaDownloadTask(
                    downloadViewInfo.downloadMode,
                    asLinkResultType.currentEpId,
                    downloadViewInfo.selectedEpId,
                    downloadViewInfo.mediaContainerConfig
                )
            }

            is ASLinkResultType.BILI.Video -> {
                createVideoDownloadTask(
                    downloadViewInfo.downloadMode,
                    asLinkResultType.currentBvId,
                    downloadViewInfo.selectedCid,
                    downloadViewInfo.mediaContainerConfig
                )
            }

            else -> Result.failure(IllegalArgumentException("不支持的链接类型"))
        }

        // ⚠️ 空选择不能当成功（2026-09-15 复审 M2）：原来 pages 过滤后为空会返回空 roots，
        // 上层却无条件弹「已添加到下载队列」—— 队列里什么都没有，DB 里还留一条没有 segment 的 task。
        result.mapCatching { tree ->
            val segmentCount = countSegments(tree.roots)
            if (segmentCount == 0) {
                throw IllegalStateException("没有可下载的分集（勾选可能已过期，请重新勾选后再试）")
            }
            tree
        }
    }

    private fun countSegments(nodes: List<DownloadTreeNode>): Int =
        nodes.sumOf { node -> node.segments.size + countSegments(node.children) }

    private suspend fun <T> autoRequestRetry(
        onErrorTip: (NetWorkResult<T?>?) -> String,
        block: suspend () -> FlowNetWorkResult<T>
    ): T? {
        var count = 1
        var lastResult: NetWorkResult<T?>? = null
        while (count < 3) {
            val result = block().last()
            lastResult = result
            if (result.status == ApiStatus.SUCCESS) {
                return result.data
            }
            count++
        }
        error(onErrorTip(lastResult))
    }

    /**
     * 创建番剧下载任务
     */
    private suspend fun createDonghuaDownloadTask(
        downloadMode: DownloadMode,
        currentEpId: Long,
        selectedEpId: List<Long>,
        mediaContainerConfig: MediaContainerConfig
    ): Result<DownloadTaskTree> = runCatching {
        val donghuaInfo = autoRequestRetry(onErrorTip = { donghuaInfo ->
            "番剧接口异常:${donghuaInfo?.errorMsg}"
        }) { videoInfoRepository.getDonghuaSeasonViewInfo(currentEpId) }
        val data = donghuaInfo!!

        val task = getOrCreateTask(
            platformId = data.seasonId.toString(),
            title = data.title,
            description = data.evaluate,
            cover = data.cover,
            type = DownloadTaskType.BILI_DONGHUA
        )

        val namingConventionInfo = NamingConventionInfo.Donghua(
            title = data.title,
        )
        val roots =
            buildDonghuaTree(task.taskId, data, selectedEpId, downloadMode, namingConventionInfo,mediaContainerConfig)


        DownloadTaskTree(task = task, roots = roots)
    }

    /**
     * 创建视频下载任务
     */
    private suspend fun createVideoDownloadTask(
        downloadMode: DownloadMode,
        bvid: String,
        selectedCid: List<Long>,
        mediaContainerConfig: MediaContainerConfig
    ): Result<DownloadTaskTree> = runCatching {
        val videoInfo = videoInfoRepository.getVideoView(bvid).last()
        if (videoInfo.status != ApiStatus.SUCCESS) error("视频接口异常:${videoInfo.errorMsg}")
        val data = videoInfo.data!!

        // 判断是否为合集
        val isUgcSeason = !data.ugcSeason?.sections.isNullOrEmpty()
        val isSteinGate = data.rights?.isSteinGate != 0L

        val (task, taskType) = when {
            isUgcSeason ->// 合集
                getOrCreateTask(
                    platformId = data.ugcSeason!!.id.toString(),
                    title = data.ugcSeason!!.title,
                    description = data.desc,
                    cover = data.ugcSeason!!.cover,
                    type = DownloadTaskType.BILI_VIDEO_SECTION
                ) to DownloadTaskType.BILI_VIDEO_SECTION

            else -> {
                // 普通视频
                getOrCreateTask(
                    platformId = data.bvid,
                    title = data.title,
                    description = data.desc,
                    cover = data.pic,
                    type = DownloadTaskType.BILI_VIDEO
                ) to DownloadTaskType.BILI_VIDEO
            }
        }

        val namingConventionInfo = when {
            isUgcSeason -> {
                NamingConventionInfo.Video(
                    title = data.ugcSeason!!.title,
                )
            }

            else -> {
                NamingConventionInfo.Video(
                    title = data.title,
                )
            }
        }.copy(
            bvId = data.bvid,
            aid = data.aid.toString(),
            author = data.owner.name
        )

        val roots = when {
            isUgcSeason -> {
                buildUgcSeasonTree(
                    task.taskId,
                    task,
                    data,
                    selectedCid,
                    downloadMode,
                    namingConventionInfo,
                    mediaContainerConfig
                )
            }

            isSteinGate -> {
                buildSteinGateTree(
                    task.taskId,
                    task,
                    data,
                    selectedCid,
                    downloadMode,
                    namingConventionInfo,
                    mediaContainerConfig
                )
            }

            else -> {
                val pages = data.pages?.filter { selectedCid.contains(it.cid) }.orEmpty()
                if (pages.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        buildVideoPageTree(
                            task.taskId,
                            task,
                            data,
                            pages,
                            downloadMode,
                            namingConventionInfo = namingConventionInfo,
                            allPages = data.pages ?: emptyList(),
                            mediaContainerConfig = mediaContainerConfig
                        )
                    )
                }
            }
        }

        DownloadTaskTree(task, roots)
    }

    /**
     * 获取或创建下载任务
     */
    private suspend fun getOrCreateTask(
        platformId: String,
        title: String,
        description: String,
        cover: String,
        type: DownloadTaskType
    ): DownloadTask {
        // ⚠️ 必须按 **type** 一起匹配（2026-09-15 复审 M9）：番剧 seasonId 与合集 ugcSeasonId
        // 是同一个数字命名空间，只按 platform_id 查会把两种任务混成一条（后续只取第一行）。
        return downloadTaskDao.getTaskByPlatformId(platformId)
            ?.takeIf { it.type == type }
            ?.copy(updateTime = Date())
            ?.also { downloadTaskDao.updateTask(it) }
            ?: DownloadTask(
            title = title,
            description = description,
            platformId = platformId,
            downloadPlatform = DownloadPlatform.BILIBILI,
            cover = cover,
            type = type
        ).let {
            val taskId = downloadTaskDao.insertTask(it)
            it.copy(taskId = taskId)
        }
    }

    /**
     * 构建番剧树结构：season -> episode
     */
    private suspend fun buildDonghuaTree(
        taskId: Long,
        data: BILIDonghuaSeasonInfo,
        selectedEpId: List<Long>,
        downloadMode: DownloadMode,
        namingConventionInfo: NamingConventionInfo.Donghua,
        mediaContainerConfig: MediaContainerConfig
    ): List<DownloadTreeNode> {
        val roots = mutableListOf<DownloadTreeNode>()

        // 1. 构建季度节点
        data.seasons.forEach { season ->
            namingConventionInfo.seasonTitle = season.seasonTitle
            val episodes = if (season.seasonId == data.seasonId) {
                data.episodes
            } else {
                // 获取其他季度的剧集信息
                val seasonInfo =
                    videoInfoRepository.getDonghuaSeasonViewInfo(seasonId = season.seasonId).last()
                seasonInfo.data?.episodes ?: emptyList()
            }

            val filteredEpisodes = episodes.filter { selectedEpId.contains(it.epId) }
            if (filteredEpisodes.isNotEmpty()) {
                roots += buildSeasonNode(
                    taskId,
                    season,
                    filteredEpisodes,
                    downloadMode,
                    namingConventionInfo,
                    episodes,
                    mediaContainerConfig
                )
            }
        }

        // 2. 构建预告章节节点
        data.section.forEach { section ->
            val filteredEpisodes = section.episodes.filter { selectedEpId.contains(it.epId) }
            if (filteredEpisodes.isNotEmpty()) {
                roots += buildSectionNode(
                    taskId,
                    section,
                    filteredEpisodes,
                    downloadMode,
                    namingConventionInfo,
                    mediaContainerConfig
                )
            }
        }


        // 3. 构建特殊的正片
        val epList = data.episodes.filter { selectedEpId.contains(it.epId) }
        if (epList.isNotEmpty()) {
            roots += buildNoeEpisodeNode(taskId, data, epList, downloadMode, namingConventionInfo,mediaContainerConfig)
        }
        return roots
    }

    /**
     * 构建合集树结构：section -> episode
     */
    private suspend fun buildUgcSeasonTree(
        taskId: Long,
        task: DownloadTask,
        data: BILIVideoViewInfo,
        selectedCid: List<Long>,
        downloadMode: DownloadMode,
        namingConventionInfo: NamingConventionInfo.Video,
        mediaContainerConfig: MediaContainerConfig
    ): List<DownloadTreeNode> {


        return data.ugcSeason?.sections?.mapNotNull { section ->
            // 视频章节
            val filteredEpisodes = section.episodes
                .filter {
                    if (it.pages.isNotEmpty()) it.pages.any { page -> page.cid in selectedCid } else selectedCid.contains(
                        it.cid
                    )
                }
            if (filteredEpisodes.isNotEmpty()) {
                buildUgcSectionNode(
                    taskId,
                    task,
                    data.ugcSeason,
                    section,
                    filteredEpisodes,
                    downloadMode,
                    selectedCid,
                    namingConventionInfo,
                    mediaContainerConfig
                )
            } else null
        } ?: emptyList()
    }

    /**
     * 构建互动视频树树结构：node -> node -> page
     * 暂未实现节点绑定
     */
    private suspend fun DownloadTaskRepository.buildSteinGateTree(
        taskId: Long,
        task: DownloadTask,
        data: BILIVideoViewInfo,
        selectedCid: List<Long>,
        downloadMode: DownloadMode,
        namingConventionInfo: NamingConventionInfo.Video,
        mediaContainerConfig: MediaContainerConfig
    ): List<DownloadTreeNode> {
        val playerInfoV2 = videoInfoRepository.getVideoPlayerInfoV2(
            cid = data.pages?.firstOrNull()?.cid ?: 0,
            bvId = data.bvid,
            aid = data.aid
        ).last()
        val steinGateInfo = videoInfoRepository.getSteinEdgeInfoV2(
            bvId = data.bvid,
            aid = data.aid.toString(),
            graphVersion = playerInfoV2.data?.interaction?.graphVersion ?: 0L
        ).last()

        val nodeList = mutableListOf<DownloadTreeNode>()

        steinGateInfo.data?.storyList?.filter { it.cid in selectedCid }
            ?.forEach { story ->
                val node = getOrCreateNode(
                    taskId = taskId,
                    platformId = story.nodeId.toString(),
                    title = story.title,
                    nodeType = DownloadTaskNodeType.BILI_VIDEO_INTERACTIVE,
                    pic = story.cover,
                )

                val mNamingConvention = namingConventionInfo.copy(
                    pTitle = story.title,
                    p = ((steinGateInfo.data?.storyList?.indexOf(story) ?: 1)).toString(),
                    cid = story.cid.toString(),
                )

                val segment = createSegment(
                    nodeId = node.nodeId,
                    title = story.title,
                    cover = story.cover,
                    platformId = story.cid.toString(),
                    platformUniqueId = story.cid.toString(),
                    segmentOrder = 0L,
                    platformInfo = json.encodeToString(story),
                    duration = 0,
                    downloadMode = downloadMode,
                    mNamingConventionInfo = mNamingConvention,  // 互动视频不需要子任务
                    mediaContainerConfig = mediaContainerConfig
                )

                nodeList.add(
                    DownloadTreeNode(
                        node = node,
                        segments = listOf(segment),
                        children = emptyList()
                    )
                )
            }


        return nodeList

    }

    /**
     * 构建普通视频树结构：video -> page
     */
    private suspend fun buildVideoPageTree(
        taskId: Long,
        task: DownloadTask,
        data: BILIVideoViewInfo,
        pages: List<BILIVideoViewInfo.Page>,
        downloadMode: DownloadMode,
        parentNodeId: Long? = null,
        childTaskId: Long? = null,
        namingConventionInfo: NamingConventionInfo.Video,
        allPages: List<BILIVideoViewInfo.Page>,
        mediaContainerConfig: MediaContainerConfig,
    ): DownloadTreeNode {
        val node = getOrCreateNode(
            taskId = taskId,
            platformId = data.bvid,
            title = data.title,
            nodeType = DownloadTaskNodeType.BILI_VIDEO_PAGE,
            pic = data.pic,
            parentNodeId = parentNodeId
        )
        val segments = pages.map { page ->

            val mNamingConvention = namingConventionInfo.copy(
                // ⚠️ 只有多 P 才填分 P 标题：单 P 的 `page.part` 基本等于视频标题本身，
                // 填进去会渲染成「标题_标题.mp4」（2026-10-02 真机实测，见 PageNamingRules）
                pTitle = PageNamingRules.partTitleForNaming(allPages.size, page.part),
                p = (allPages.indexOf(page) + 1).toString(),
                cid = page.cid.toString(),
            )

            createSegment(
                nodeId = node.nodeId,
                title = page.part,
                cover = task.cover,
                platformId = page.cid.toString(),
                platformUniqueId = page.cid.toString(),
                segmentOrder = page.page,
                platformInfo = json.encodeToString(page),
                duration = page.duration,
                downloadMode = downloadMode,
                childTaskId = childTaskId,
                mNamingConventionInfo = mNamingConvention,  // 普通视频不需要子任务
                mediaContainerConfig = mediaContainerConfig
            )
        }

        return DownloadTreeNode(node, segments, emptyList())
    }

    /**
     * 构建番剧季度节点
     */
    private suspend fun buildSeasonNode(
        taskId: Long,
        season: BILIDonghuaSeasonInfo.Season,
        episodes: List<BILIDonghuaSeasonInfo.Episode>,
        downloadMode: DownloadMode,
        namingConventionInfo: NamingConventionInfo.Donghua,
        allEpisodes: List<BILIDonghuaSeasonInfo.Episode>,
        mediaContainerConfig: MediaContainerConfig
    ): DownloadTreeNode {
        val node = getOrCreateNode(
            taskId = taskId,
            platformId = season.seasonId.toString(),
            title = season.seasonTitle,
            nodeType = DownloadTaskNodeType.BILI_DONGHUA_SEASON
        )

        val segments = episodes.map { episode ->

            val mNamingConvention = namingConventionInfo.copy(
                episodeTitle = episode.longTitle.ifEmpty { episode.title },
                cid = episode.cid.toString(),
                episodeNumber = (allEpisodes.indexOf(episode) + 1).toString()
            )

            createSegment(
                nodeId = node.nodeId,
                title = episode.longTitle.ifEmpty { episode.title },
                cover = episode.cover,
                platformId = episode.epId.toString(),
                platformUniqueId = episode.cid.toString(),
                segmentOrder = episode.longTitle.ifEmpty { episode.title }.filter { it.isDigit() }
                    .toLongOrNull() ?: 0L,
                platformInfo = json.encodeToString(episode),
                duration = episode.duration,
                downloadMode = downloadMode,
                mNamingConventionInfo = mNamingConvention,  // 番剧不需要子任务
                mediaContainerConfig = mediaContainerConfig
            )
        }

        return DownloadTreeNode(node, segments, emptyList())
    }

    /**
     * 构建番剧预告章节节点
     */
    private suspend fun buildSectionNode(
        taskId: Long,
        section: BILIDonghuaSeasonInfo.Section,
        episodes: List<BILIDonghuaSeasonInfo.Episode>,
        downloadMode: DownloadMode,
        namingConventionInfo: NamingConventionInfo.Donghua,
        mediaContainerConfig:MediaContainerConfig
    ): DownloadTreeNode {
        val node = getOrCreateNode(
            taskId = taskId,
            platformId = section.id.toString(),
            title = section.title,
            nodeType = DownloadTaskNodeType.BILI_DONGHUA_SECTION
        )
        val segments = episodes.map { episode ->
            val mNamingConvention = namingConventionInfo.copy(
                episodeNumber = (section.episodes.indexOf(episode) + 1).toString(),
                episodeTitle = episode.longTitle.ifEmpty { episode.title },
                cid = episode.cid.toString(),
            )

            createSegment(
                nodeId = node.nodeId,
                title = episode.longTitle.ifEmpty { episode.title },
                cover = episode.cover,
                platformId = episode.epId.toString(),
                platformUniqueId = episode.cid.toString(),
                segmentOrder = 0L,
                platformInfo = json.encodeToString(episode),
                duration = episode.duration,
                downloadMode = downloadMode,
                mNamingConventionInfo = mNamingConvention,  // 番剧预告不需要子任务
                mediaContainerConfig = mediaContainerConfig
            )
        }

        return DownloadTreeNode(node, segments, emptyList())
    }


    /**
     * 构建番剧正片节点
     */
    private suspend fun buildNoeEpisodeNode(
        taskId: Long,
        data: BILIDonghuaSeasonInfo,
        episodes: List<BILIDonghuaSeasonInfo.Episode>,
        downloadMode: DownloadMode,
        namingConventionInfo: NamingConventionInfo.Donghua,
        mediaContainerConfig: MediaContainerConfig
    ): DownloadTreeNode {

        val node = getOrCreateNode(
            taskId = taskId,
            platformId = "${data.seasonId}",
            title = data.title,
            nodeType = DownloadTaskNodeType.BILI_DONGHUA_EPISOD
        )

        val segments = episodes.map { episode ->
            val mNamingConvention = namingConventionInfo.copy(
                episodeNumber = (data.episodes.indexOf(episode) + 1).toString(),
                episodeTitle = episode.longTitle.ifEmpty { episode.title },
                cid = episode.cid.toString(),
            )

            createSegment(
                nodeId = node.nodeId,
                title = episode.longTitle.ifEmpty { episode.title },
                cover = episode.cover,
                platformId = episode.epId.toString(),
                platformUniqueId = episode.cid.toString(),
                segmentOrder = 0L,
                platformInfo = json.encodeToString(episode),
                duration = episode.duration,
                downloadMode = downloadMode,
                mNamingConventionInfo = mNamingConvention,  // 番剧正片不需要子任务
                mediaContainerConfig = mediaContainerConfig
            )
        }

        return DownloadTreeNode(node, segments, emptyList())
    }

    /**
     * 构建合集章节节点
     */
    private suspend fun buildUgcSectionNode(
        taskId: Long,
        task: DownloadTask,
        ugcSeason: BILIVideoViewInfo.UgcSeason?,
        section: BILIVideoViewInfo.UgcSeason.Section,
        episodes: List<BILIVideoViewInfo.UgcSeason.Section.Episode>,
        downloadMode: DownloadMode,
        selectedCid: List<Long>,
        namingConventionInfo: NamingConventionInfo.Video,
        mediaContainerConfig: MediaContainerConfig
    ): DownloadTreeNode {
        val node = getOrCreateNode(
            taskId = taskId,
            platformId = section.id.toString(),
            title = section.title,
            nodeType = DownloadTaskNodeType.BILI_VIDEO_SECTION_EPISODES
        )

        val pageEpList = episodes.mapNotNull { episode ->

            if (episode.pages.size > 1) {
                val videoInfo = videoInfoRepository.getVideoView(episode.bvid).last().data
                if (videoInfo == null) throw IllegalStateException("合集分P视频信息获取失败: bv：${episode.bvid} cid：${episode.cid}")
                // 普通视频
                val newTask = getOrCreateTask(
                    platformId = videoInfo.bvid,
                    title = videoInfo.title,
                    description = videoInfo.desc,
                    cover = videoInfo.pic,
                    type = DownloadTaskType.BILI_VIDEO
                )

                buildVideoPageTree(
                    task.taskId,
                    task,
                    videoInfo,
                    episode.pages.filter { page -> page.cid in selectedCid },
                    downloadMode,
                    parentNodeId = node.nodeId,
                    childTaskId = newTask.taskId,  // 关联子任务ID
                    namingConventionInfo,
                    episode.pages,
                    mediaContainerConfig = mediaContainerConfig,
                ).segments

            } else null
        }

        val segments = episodes.filterWithSinglePage().map { episode ->

            // 为每个子视频创建独立的下载任务
            val childTask = getOrCreateTask(
                platformId = episode.bvid,
                title = episode.title,
                cover = episode.arc.pic,
                description = episode.arc.desc,
                type = DownloadTaskType.BILI_VIDEO
            )

            val mNamingConvention = namingConventionInfo.copy(
                collectionTitle = ugcSeason?.title,
                collectionSeasonTitle = section.title,
                pTitle = episode.title,
                p = (section.episodes.indexOf(episode) + 1).toString(),
                cid = episode.cid.toString(),
            )

            // 创建关联到子任务的segment
            createSegment(
                nodeId = node.nodeId,
                title = episode.title,
                cover = episode.arc.pic,
                platformId = episode.cid.toString(),
                platformUniqueId = episode.cid.toString(),
                segmentOrder = episode.page?.page ?: 0L,
                platformInfo = json.encodeToString(episode),
                duration = episode.page?.duration,
                downloadMode = downloadMode,
                childTaskId = childTask.taskId,  // 关联子任务ID
                mediaContainerConfig = mediaContainerConfig,
                mNamingConventionInfo = mNamingConvention
            )
        }

        return DownloadTreeNode(node, segments + pageEpList.flatten(), emptyList())
    }

    /**
     * 获取或创建节点
     */
    private suspend fun getOrCreateNode(
        taskId: Long,
        platformId: String,
        title: String,
        nodeType: DownloadTaskNodeType,
        pic: String? = null,
        parentNodeId: Long? = null
    ): DownloadTaskNode {
        return downloadTaskDao.getTaskNodeByTaskIdAndPlatformId(taskId, platformId)?.copy(
            updateTime = Date()
        )?.also {
            downloadTaskDao.updateNode(it)
        } ?: DownloadTaskNode(
            parentNodeId = parentNodeId,
            taskId = taskId,
            platformId = platformId,
            title = title,
            nodeType = nodeType,
            pic = pic
        ).let {
            val nodeId = downloadTaskDao.insertNode(it)
            it.copy(nodeId = nodeId)
        }
    }

    /**
     * 创建下载片段
     */
    private suspend fun createSegment(
        nodeId: Long,
        title: String,
        cover: String? = null,
        platformId: String,
        platformUniqueId: String,
        segmentOrder: Long,
        platformInfo: String,
        duration: Long?,
        downloadMode: DownloadMode,
        childTaskId: Long? = null,  // 新增：子任务ID参数
        qualityDescription: String? = null,
        mNamingConventionInfo: NamingConventionInfo,
        mediaContainerConfig: MediaContainerConfig,
    ): DownloadSegment {
        // 一条记录 = 一份「产物」：键 = (nodeId, platformId, downloadMode, mediaContainer)。
        //
        // 历史（第二十四轮）：原先"已完成"的分支会返回 null → 再插一条新的，于是出现两条
        // 同 (nodeId, platformId) 的记录；而落盘文件名由命名规则决定（同一集必然同名），
        // 后一次下载会覆盖前一次的文件 —— 第二条记录永远指向一个不存在的文件（真机时 DB 里积了 3 条）。
        // 修法是**有就复用**，并把已完成的那条重置为「待下载」（否则它带着 COMPLETED 进内存列表，
        // 而队列只挑 WAITING，用户点了下载会什么都不发生）。
        //
        // ④ 阶段 1（2026-10-02）：键里**再加上产物形态**。旧键只看 (nodeId, platformId)，
        // 于是"同一集先下音频、再下视频"会命中同一条记录并覆盖它的 download_mode/media_container
        // —— 先下那份文件留在磁盘上却没有记录（在「存储管理 → 下载目录文件」里显示成孤儿）。
        // ⚠️ **音质不进键**：默认命名规则（`{title}_{p_title}`）不含音质，交付时是同名替换，
        // 两条记录会指向同一个文件 → 旧记录变成「文件已丢失」的僵尸记录。理由与取证见
        // `SegmentIdentityRules.ProductForm` 的注释。
        val productContainer = when (downloadMode) {
            DownloadMode.AUDIO_ONLY -> mediaContainerConfig.audioContainer
            else -> mediaContainerConfig.videoContainer
        }
        val existing = downloadTaskDao.getSegmentByProduct(
            nodeId = nodeId,
            platformId = platformId,
            // 传"存进 DB 的那个值"：模式 = 枚举名；容器 = 扩展名（见 DAO 上的说明）
            downloadMode = downloadMode.name,
            mediaContainer = productContainer.extension,
        )

        return when (DownloadRecordReuseRules.persistAction(existing != null)) {
            DownloadRecordReuseRules.PersistAction.UPDATE_EXISTING -> {
                val reused = existing!!.copy(
                    title = title,
                    cover = cover,
                    segmentOrder = segmentOrder,
                    platformUniqueId = platformUniqueId,
                    platformInfo = platformInfo,
                    updateTime = Date(),
                    downloadMode = downloadMode,
                    taskId = childTaskId,
                    namingConventionInfo = mNamingConventionInfo,
                    mediaContainer = productContainer,
                    qualityDescription = qualityDescription,
                    downloadState = DownloadRecordReuseRules.stateWhenReused(existing.downloadState),
                )
                downloadTaskDao.updateSegment(reused)
                reused
            }

            DownloadRecordReuseRules.PersistAction.INSERT_NEW -> DownloadSegment(
                nodeId = nodeId,
                taskId = childTaskId,  // 关联子任务
            title = title,
            cover = cover,
            segmentOrder = segmentOrder,
            platformId = platformId,
            platformUniqueId = platformUniqueId,
            platformInfo = platformInfo,
            duration = duration,
            downloadMode = downloadMode,
            savePath = "",
            fileSize = 0,
            namingConventionInfo = mNamingConventionInfo,
            qualityDescription = qualityDescription,
            mediaContainer = productContainer,
        ).let { fresh ->
            val segmentId = downloadTaskDao.insertSegment(fresh)
            if (segmentId > 0L) {
                fresh.copy(segmentId = segmentId)
            } else {
                // -1：被「产物身份」唯一索引忽略（同一份产物已经有行了）。
                // 应用层是"先查再写"，所以这只可能是并发/残留，但既然发生了就必须**复用**那一条 ——
                // 绝不能返回一条 segmentId 都不存在的记录。
                val winner = downloadTaskDao.getSegmentByProduct(
                    nodeId = nodeId,
                    platformId = platformId,
                    downloadMode = downloadMode.name,
                    mediaContainer = productContainer.extension,
                ) ?: error("同产物记录插入被唯一索引拒绝，回查也找不到（数据异常）")
                val reused = winner.copy(
                    updateTime = Date(),
                    downloadState = DownloadRecordReuseRules.stateWhenReused(winner.downloadState),
                )
                downloadTaskDao.updateSegment(reused)
                reused
            }
        }
        }
    }


    // ================== 数据库操作方法 ==================

    suspend fun getTaskByPlatformId(platformId: String): DownloadTask? =
        downloadTaskDao.getTaskByPlatformId(platformId)

    suspend fun getTaskById(taskId: Long): DownloadTask? =
        downloadTaskDao.getTaskById(taskId)


    suspend fun getTaskByNodeId(nodeId: Long): DownloadTask? =
        downloadTaskDao.getTaskByNodeId(nodeId)


    suspend fun getTaskNodeByTaskIdAndPlatformId(
        taskId: Long,
        platformId: String
    ): DownloadTaskNode? =
        downloadTaskDao.getTaskNodeByTaskIdAndPlatformId(taskId, platformId)

    suspend fun getTaskNodeByNodeId(
        nodeId: Long,
    ): DownloadTaskNode? = downloadTaskDao.getTaskNodeByNodeId(nodeId)


    suspend fun getSegmentBySegmentId(segmentId: Long) =
        downloadTaskDao.getSegmentBySegmentId(segmentId)

    fun getSegmentAll() =
        downloadTaskDao.getSegmentAll()


    suspend fun insertTask(task: DownloadTask): Long =
        downloadTaskDao.insertTask(task)

    suspend fun insertNode(node: DownloadTaskNode): Long =
        downloadTaskDao.insertNode(node)

    suspend fun insertSegment(segment: DownloadSegment): Long =
        downloadTaskDao.insertSegment(segment)

    suspend fun updateTask(task: DownloadTask) =
        downloadTaskDao.updateTask(task)

    suspend fun updateNode(node: DownloadTaskNode) =
        downloadTaskDao.updateNode(node)

    suspend fun updateSegment(segment: DownloadSegment) =
        downloadTaskDao.updateSegment(segment)

    /** 只改某条下载记录的标题（B2 批量重命名用；不动 updateTime、不整行覆盖） */
    suspend fun updateSegmentTitle(segmentId: Long, title: String) =
        downloadTaskDao.updateSegmentTitle(segmentId, title)

    suspend fun deleteSegmentById(segmentId: Long) =
        downloadTaskDao.deleteSegmentById(segmentId)


    suspend fun deleteTask(taskId: Long) =
        downloadTaskDao.deleteTask(taskId)

    suspend fun deleteNode(nodeId: Long) =
        downloadTaskDao.deleteNode(nodeId)

    suspend fun deleteSegment(segmentId: Long) =
        downloadTaskDao.deleteSegment(segmentId)

}
