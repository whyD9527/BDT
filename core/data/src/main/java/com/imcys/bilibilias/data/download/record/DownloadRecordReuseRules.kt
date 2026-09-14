package com.imcys.bilibilias.data.download.record

import com.imcys.bilibilias.database.entity.download.DownloadState

/**
 * 「同一集被重新下载」时的两条纯规则。
 *
 * ## ① 一个 `(nodeId, platformId)` 只能有一条记录
 * `DownloadTaskRepository.createSegment` 原先的写法是：已有记录且**已完成** → 返回 null →
 * **再插一条新的**。而落盘文件名是由命名规则算出来的（同一集必然同名），
 * 后一次下载会**覆盖前一次的那个文件** —— 于是第二条记录永远指向一个不存在的文件：
 * 「已完成下载」里出现两条同名条目，其中一条点开是"文件不存在"
 * （第二十四轮真机时 DB 里就积了 3 条重复记录）。
 *
 * ## ② 删同名旧文件必须在新文件写完之后
 * `FileOutputManager` 移动成品进下载目录时，**先删同名旧文件、再写新文件**：
 * 中途失败（空间不足、被占用、MediaStore 拒绝）就会把用户**已有的那个文件弄没了**，
 * 而新文件又没生成 —— 净效果是"重下一次反而丢了原来的"。
 * 修法是把顺序倒过来：先用**临时名字**把新文件完整写出来，成功之后才允许删旧文件、改名。
 */
object DownloadRecordReuseRules {

    /** 已有记录该怎么处理 */
    enum class PersistAction {
        /** 插一条新记录 */
        INSERT_NEW,

        /** 复用已有那条（改它，不要再插） */
        UPDATE_EXISTING,
    }

    /**
     * 同一 `(nodeId, platformId)` 只允许一条记录：**有就复用**。
     *
     * 注意这里不是在"省一次插入"：插第二条记录必然指向一个不存在的文件（见类注释 ①）。
     */
    fun persistAction(hasExistingSegment: Boolean): PersistAction =
        if (hasExistingSegment) PersistAction.UPDATE_EXISTING else PersistAction.INSERT_NEW

    /**
     * 复用那条记录时，状态要改成什么。
     *
     * ⚠️ **已完成的必须重置为"待下载"**：否则复用出来的任务带着 `COMPLETED` 进内存列表，
     * 而队列只挑 `WAITING` 的任务（`DownloadQueueRules.nextTaskIndex`）→
     * 用户点了下载却**永远停在"已完成"、什么都不会发生**。
     * 其它状态（暂停/等待/失败…）保持原样：那时界面上本来就该继续显示它原来的样子。
     */
    fun stateWhenReused(previous: DownloadState): DownloadState =
        if (previous == DownloadState.COMPLETED) DownloadState.WAITING else previous

    // ------------------------------------------------------------------ 落盘顺序

    /**
     * 写新文件时先用的**临时名**。
     *
     * 必须与正式名不同：MediaStore 的 `DISPLAY_NAME + RELATIVE_PATH` 可能指向同一个底层文件，
     * 直接用正式名插进去，就可能一边写一边覆盖掉用户已有的那个文件（正是要避免的事）。
     */
    fun stagingFileName(finalName: String): String = "$finalName.part"

    /**
     * 现在允许删掉同名旧文件吗？
     *
     * **只有新文件已经完整写出来之后**才允许。提前删是第十八轮审查里那条
     * "重下一次反而把原来的文件弄没了"的根因。
     */
    fun canDeleteExistingFile(newFileWritten: Boolean): Boolean = newFileWritten

    /**
     * 到 MediaStore 里查同名文件时，`RELATIVE_PATH` 要试哪几种写法。
     *
     * ⚠️ **这是个真机才暴露的坑**（第二十六轮）：调用方传进来的是
     * `"Download/BiliDownloader"`（不带结尾斜杠），而 MediaStore **存的是带斜杠的**
     * `"Download/BiliDownloader/"` —— 直接 `RELATIVE_PATH=?` 等值比较**永远匹配不到**。
     * 后果是"删除同名旧文件"这一步**从旧代码起就从未生效过**：
     * 重新下载同一集时旧文件不会被删，MediaStore 反而把新文件自动改名成
     * `xxx (1).mp4` —— 下载目录里越攒越多（每次重下多一份 100MB 级副本）。
     *
     * 两种写法都查一遍，这样无论 MediaStore 存的是哪种都能命中。
     */
    fun relativePathCandidates(relativePath: String): List<String> =
        listOf(relativePath, relativePath.trimEnd('/') + "/").distinct()
}
