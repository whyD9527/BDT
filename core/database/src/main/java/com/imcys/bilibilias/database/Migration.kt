package com.imcys.bilibilias.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE download_segment ADD COLUMN platform_unique_id TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE download_segment ADD COLUMN naming_convention_info TEXT")
    }
}
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE download_segment ADD COLUMN media_container TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE download_segment ADD COLUMN quality_description TEXT")
        db.execSQL(
            """
                UPDATE download_segment
              SET media_container = CASE download_mode
                                      WHEN 'AUDIO_VIDEO' THEN 'mp4'
                                      WHEN 'AUDIO_ONLY'  THEN 'mp3'
                                      WHEN 'VIDEO_ONLY'  THEN 'mp4'
                                      ELSE 'mp4'
                                    END
            """.trimIndent()
        )
    }
}

/**
 * 4 → 5：给 `download_task` 加 **(platform_id, type) 唯一索引**。
 *
 * 为什么要先删数据再加索引：老版本在并发/异常路径下可能已经插出重复任务
 * （同一个 platform_id + type 两条），**直接建唯一索引会让迁移失败 → 用户升级后打不开 app**。
 * 所以先把每组多余的删掉（保留 task_id 最大的那条 = 最新那条），并且**连带删掉它名下的子表行**，
 * 否则 `download_segment` / `download_task_node` 会留下指向已删除任务的孤儿行。
 *
 * ⚠️⚠️ **2026-10-01 真机升级验证时踩到的坑（差点毁数据）**：
 * 第一版写的是
 * ```sql
 * DELETE FROM download_segment WHERE task_id NOT IN (SELECT MAX(task_id) FROM download_task ...)
 * ```
 * 看起来没问题，但只要 `download_task` 是**空表**，子查询就返回**空集**，而 SQL 里
 * **`x NOT IN (空集)` 恒为真** —— 于是这条语句会把 `download_segment` **整表删光**
 * （用户的"已完成下载"记录就这么没了，磁盘上的文件还在，但应用再也找不到它）。
 * 真机现象：升级后「已完成下载」变空。
 *
 * 修法：把删除范围**限定在确实存在的任务行**上（`IN (SELECT task_id FROM download_task ...)`），
 * 任务表为空时三条 DELETE 都是 no-op；顺便也不会再误删"任务行已经不在"的孤儿子行
 * （那是另一回事，交给应用自己清理，迁移不该动）。
 * 这个坑有回归测试钉着：`Migration45SqlTest`（用 JVM 上的 SQLite 真的跑一遍这段 SQL）。
 */
/**
 * `MIGRATION_4_5` 实际执行的 SQL 清单（抽出来是为了**能被测试直接跑一遍**）。
 *
 * 纯字符串、不依赖 Android：`Migration45SqlTest` 用 JVM 上的 SQLite 建表、插数据、执行这些语句，
 * 断言"空任务表时不能删掉任何子行"这类语义 —— 否则这段 SQL 只有真机升级时才会被跑到，
 * 而它一旦写错就是**用户数据凭空消失**（2026-10-01 已经真实发生过一次）。
 */
object Migration45Sql {
    /**
     * 要删掉的"较旧任务"：同 `(platform_id, type)` 一组里 `task_id` 不是最大的那些。
     *
     * ⚠️ 外层必须 `IN (SELECT task_id FROM download_task ...)`：
     * 若写成 `子表.task_id NOT IN (SELECT MAX(task_id) FROM download_task ...)`，
     * **当 download_task 为空表时子查询是空集，而 `x NOT IN (空集)` 恒为真** → 子表被整表删光。
     */
    const val OBSOLETE_TASK_IDS: String =
        "SELECT task_id FROM download_task WHERE task_id NOT IN " +
            "(SELECT MAX(task_id) FROM download_task GROUP BY platform_id, type)"

    val statements: List<String> = listOf(
        "DELETE FROM download_segment WHERE task_id IN ($OBSOLETE_TASK_IDS)",
        "DELETE FROM download_task_node WHERE task_id IN ($OBSOLETE_TASK_IDS)",
        "DELETE FROM download_task WHERE task_id IN ($OBSOLETE_TASK_IDS)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_download_task_platform_id_type " +
            "ON download_task(platform_id, type)",
    )
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Migration45Sql.statements.forEach { db.execSQL(it) }
    }
}
