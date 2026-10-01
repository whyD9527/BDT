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
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val keepNewestPerGroup =
            "SELECT MAX(task_id) FROM download_task GROUP BY platform_id, type"
        db.execSQL("DELETE FROM download_segment WHERE task_id NOT IN ($keepNewestPerGroup)")
        db.execSQL("DELETE FROM download_task_node WHERE task_id NOT IN ($keepNewestPerGroup)")
        db.execSQL("DELETE FROM download_task WHERE task_id NOT IN ($keepNewestPerGroup)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_download_task_platform_id_type " +
                "ON download_task(platform_id, type)"
        )
    }
}
