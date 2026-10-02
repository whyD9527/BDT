package com.imcys.bilibilias.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.imcys.bilibilias.database.entity.download.DownloadSegment
import com.imcys.bilibilias.database.entity.download.DownloadTask
import com.imcys.bilibilias.database.entity.download.DownloadTaskNode
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface DownloadTaskDao {


    /**
     *  根据 platformId 查单个任务
     */
    @Query("SELECT * FROM download_task WHERE platform_id = :platformId")
    suspend fun getTaskByPlatformId(platformId: String): DownloadTask?


    @Query("SELECT * FROM download_task WHERE task_id = :taskId")
    suspend fun getTaskById( taskId: Long): DownloadTask?

    @Query("""
        SELECT * 
        FROM download_task 
        WHERE task_id = (
            SELECT task_id 
            FROM download_task_node 
            WHERE node_id = :nodeId
        )
    """)
    suspend fun getTaskByNodeId(nodeId: Long): DownloadTask?

    /**
     *  根据 platformId 查单个节点
     */
    @Query("SELECT * FROM download_task_node WHERE task_id = :taskId AND platform_id = :platformId")
    suspend fun getTaskNodeByTaskIdAndPlatformId(
        taskId: Long,
        platformId: String
    ): DownloadTaskNode?

    @Query("SELECT * FROM download_task_node WHERE node_id = :nodeId")
    suspend fun getTaskNodeByNodeId(
        nodeId: Long,
    ): DownloadTaskNode?
    /**
     * ⚠️ **别再用它做"这条下载是不是已经存在"的判断**（④ 阶段 1，2026-10-02）。
     *
     * 这个键 `(nodeId, platformId)` **不含产物形态**：同一集先下音频、再下视频会命中同一条，
     * 于是覆盖掉它的 `download_mode`/`media_container`，先下那份文件变成没有记录的孤儿。
     * 而且现在同一个 node 合法地**可以有多条记录**，这个方法只会返回其中一条（顺序还不确定）。
     *
     * 要判断"同一份产物"请用 [getSegmentByProduct]。
     */
    @Deprecated("④ 阶段 1 起改用 getSegmentByProduct（同一个 node 现在允许多份产物）")
    @Query("SELECT * FROM download_segment WHERE node_id = :nodeId AND platform_id = :platformId")
    suspend fun getSegmentByNodeIdAndPlatformId(nodeId: Long, platformId: String): DownloadSegment?


    @Query("SELECT * FROM download_segment WHERE segment_id = :segmentId")
    suspend fun getSegmentBySegmentId(segmentId: Long): DownloadSegment?

    /**
     * 按**产物身份**查一条 segment（④ 阶段 1，2026-10-02）。
     *
     * 与 [getSegmentByNodeIdAndPlatformId] 的区别：那个只看"哪个节点 + 哪个平台"，
     * 于是"同一集先下音频、再下视频"会命中同一条记录并**覆盖**它的 `download_mode`/`media_container`
     * —— 先下那份文件留在磁盘上却没有记录（「存储管理 → 下载目录文件」里会显示成孤儿）。
     * 这里把**产物形态**（下载模式 + 封装格式）也纳入键：同一集的不同产物 = 不同记录。
     *
     * ⚠️ **音质刻意不进键**（本表也还没有 `quality_key` 列）：默认命名规则不含音质，
     * 两条记录会指向同一个文件 → 旧记录变成「文件已丢失」的僵尸记录。取证与理由见
     * `SegmentIdentityRules.ProductForm` 的注释。
     *
     * ⚠️ 两个参数都刻意用 **String**（传"存进 DB 的那个值"）：
     * `MediaContainer` 是 **sealed interface**，Room 不把它当成"能转成列的类型"用 ——
     * 写成 `mediaContainer: MediaContainer` 会直接被 KSP 拒掉
     * （`Query function parameters should either be a type that can be converted into a database column...`，
     * 2026-10-02 CI 报过）。所以调用方传 `downloadMode.name` 与 `container.extension`。
     */
    @Query(
        "SELECT * FROM download_segment WHERE node_id = :nodeId AND platform_id = :platformId " +
            "AND download_mode = :downloadMode AND media_container = :mediaContainer"
    )
    suspend fun getSegmentByProduct(
        nodeId: Long,
        platformId: String,
        /** 存进 DB 的值 = 枚举名（`AUDIO_VIDEO`/`VIDEO_ONLY`/`AUDIO_ONLY`） */
        downloadMode: String,
        /** 存进 DB 的值 = **扩展名**（`mp4`/`m4a`/`mp3`/`mkv`）—— `MediaContainerConverter` 存的是它 */
        mediaContainer: String,
    ): DownloadSegment?


    @Query("SELECT * FROM download_segment ORDER BY segment_id DESC")
    fun getSegmentAll(): Flow<List<DownloadSegment>>


    /**
     * 插入顶层任务
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DownloadTask): Long

    /**
     * 插入节点
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: DownloadTaskNode): Long

    /**
     * 插入下载片段。
     *
     * ⚠️ 用 **IGNORE** 而不是 REPLACE（④ 阶段 2，2026-10-02）：表上现在有「产物身份」唯一索引，
     * 而 REPLACE 的语义是**先删掉冲突的那一行、再插新的** —— 于是一次意外的重复插入会连
     * 那条记录的 `save_path`/`file_size` 一起删掉（磁盘上的文件还在，记录却没了）。
     * 返回 **`-1`** 表示"被唯一索引忽略了"，调用方（`createSegment`）会回查那条已有记录并复用它。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSegment(segment: DownloadSegment): Long


    /**
     * 更新任务并刷新 updateTime
     */
    @Update
    suspend fun updateTaskRaw(task: DownloadTask)

    suspend fun updateTask(task: DownloadTask) {
        updateTaskRaw(task.copy(updateTime = Date()))
    }

    /**
     * 更新节点并刷新 updateTime
     */
    @Update
    suspend fun updateNodeRaw(node: DownloadTaskNode)

    suspend fun updateNode(node: DownloadTaskNode) {
        updateNodeRaw(node.copy(updateTime = Date()))
    }

    /**
     * 更新片段并刷新 updateTime
     */
    @Update
    suspend fun updateSegmentRaw(segment: DownloadSegment)

    suspend fun updateSegment(segment: DownloadSegment) {
        updateSegmentRaw(segment.copy(updateTime = Date()))
    }

    /**
     * 只改**标题**一列（B2 批量重命名：让记录标题跟着文件名走）。
     *
     * ⚠️ 刻意不用 [updateSegmentRaw]（`@Update` 整行覆盖 + 刷新 `updateTime`）：
     * 1. 整行覆盖会把界面上的**旧快照**写回去，可能盖掉并发写入的 `savePath`/状态等字段；
     * 2. 刷新 `updateTime` 会让"按时间排序"的列表因为"改了个名字"就把这一条跳到最前面。
     */
    @Query("UPDATE download_segment SET title = :title WHERE segment_id = :segmentId")
    suspend fun updateSegmentTitle(segmentId: Long, title: String)

    @Query("DELETE FROM download_segment WHERE segment_id = :segmentId")
    suspend fun deleteSegmentById(segmentId: Long)


    /**
     * 按 taskId 删除整棵树（级联删除节点和片段）
     */
    @Query("DELETE FROM download_task WHERE task_id = :taskId")
    suspend fun deleteTask(taskId: Long)

    /**
     * 按 nodeId 删除节点及其所有子节点和片段
     */
    @Query("DELETE FROM download_task_node WHERE node_id = :nodeId")
    suspend fun deleteNode(nodeId: Long)

    /**
     * 按 segmentId 删除单个片段
     */
    @Query("DELETE FROM download_segment WHERE segment_id = :segmentId")
    suspend fun deleteSegment(segmentId: Long)

}