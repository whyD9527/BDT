package com.imcys.bilibilias.data.download.startup

import com.imcys.bilibilias.database.entity.download.DownloadState

/**
 * 「重启后怎么处理上次没下完的记录」的纯规则。
 *
 * ## 原先是什么样（第十八轮审查）
 * `NewDownloadManager.initDownloadList()` 只做一件事：
 * `if (state !in listOf(PAUSE, COMPLETED)) deleteSegment(...)`。三个后果：
 *
 * 1. **PAUSE 变幽灵**：行留在 DB 里，但重启后内存任务列表是空的 —— 界面压根不显示它，
 *    `resumeTask` 也找不到它（它按 segmentId 在内存列表里找），用户既看不到也恢复不了；
 * 2. **WAITING/ERROR 被静默删除**：失败过的任务连记录都不剩，用户不知道为什么没了；
 * 3. **失败任务的 `.downloading` / `.downloadpart` 永不清理**：只删 DB 行，不碰文件。
 *
 * ## 为什么不是"把 PAUSE 恢复成可继续的任务"
 * 跨重启恢复需要**用户当初选的那套质量参数**（`DownloadViewInfo` 里的
 * selectVideoQualityId / selectVideoCode / selectAudioQualityId），而它**没有落盘**
 * （`segment.platformInfo` 存的是平台的 page/episode JSON，不含选择）。
 * 拿一套猜出来的参数去续写一个已经下了一半的临时文件，会写出
 * **长度对、内容却来自另一条码流**的坏文件 —— 这正是本项目最怕的那类静默损坏。
 * 所以：**宁可不去恢复，也不猜**。记录直接丢弃（不再有幽灵），
 * 而"接着下"这件事交给下次重新添加时既有的 Range/分片续传逻辑。
 */
object DownloadStartupRules {

    /** 重启时对一条 segment 记录的处理方式 */
    enum class StartupAction {
        /** 保留：已完成，出现在「已完成下载」里 */
        KEEP,

        /** 丢弃记录，但**保留**临时文件：还能接着下（下次添加同一内容时 Range/边车会复用） */
        DISCARD_KEEP_FILES,

        /** 丢弃记录，并**删掉**临时文件：这次下载已经终结，残留没有意义 */
        DISCARD_AND_CLEAN,
    }

    /**
     * 状态 → 处理方式。
     *
     * - `COMPLETED` 是唯一要留的；
     * - **还没下完、被中断**的（暂停 / 排队 / 下载中 / 合并中 / 前置阶段）→ 丢记录、留文件；
     * - **已经终结**的（失败 / 取消）→ 丢记录、连文件一起清掉（这就是"失败任务的临时文件永不清理"那条）。
     */
    fun actionFor(state: DownloadState): StartupAction = when (state) {
        DownloadState.COMPLETED -> StartupAction.KEEP

        DownloadState.PAUSE,
        DownloadState.WAITING,
        DownloadState.PRE_TASK,
        DownloadState.DOWNLOADING,
        DownloadState.MERGING,
        DownloadState.POST_TASK,
        -> StartupAction.DISCARD_KEEP_FILES

        DownloadState.ERROR,
        DownloadState.CANCELLED,
        -> StartupAction.DISCARD_AND_CLEAN
    }

    /**
     * 某个 segment 的中间产物文件名前缀。
     *
     * 子任务文件名固定是 `<platformId>_<VIDEO|AUDIO>.<ext>`（见 `NewDownloadManager.createSubTask`），
     * 边车元数据是它的 `.downloading` / `.downloadpart` 变体，所以按前缀就能一网打尽。
     *
     * ⚠️ **结尾那个下划线不能省**：`platformId` 是数字串，`"12_"` 不会误伤 `"123_..."`，
     * 而只用 `"12"` 就会。
     */
    fun tempFilePrefix(platformId: String): String = "${platformId}_"

    /** 这个文件名是不是属于这个 segment 的中间产物 */
    fun isTempFileOf(fileName: String, platformId: String): Boolean =
        platformId.isNotBlank() && fileName.startsWith(tempFilePrefix(platformId))
}
