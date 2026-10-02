package com.imcys.bilibilias.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * `MIGRATION_5_6` 里那几条 SQL 的回归测试（④ 阶段 2）。
 *
 * ## 为什么要有它
 * 这个迁移有两件"只有真实升级才会跑到、跑错就是用户数据消失/打不开 app"的事：
 *
 * 1. **删重复行**：老版本"复用"只看 `(nodeId, platformId)`，更早的版本还会在"已完成"时再插一条
 *    —— 真机 DB 里真的积过重复（第二十四轮 3 条）。先把多余的删掉再建唯一索引，
 *    否则 **`CREATE UNIQUE INDEX` 直接失败 → 用户升级后打不开 app**。
 *    而 4→5 那次真机事故证明：**删数据的 SQL 写错形状，空表时会把整表删光**（`NOT IN (空集)` 恒真）。
 *    这里就用 JVM 上的真 SQLite 把"空表不许删任何行"钉死。
 * 2. **唯一索引本身**：它是"一条记录 = 一份产物"最后的保险，必须真的拦得住重复插入，
 *    同时**不能**把"同一集的不同产物"（仅音频 / 仅视频 / 音视频、不同封装）当成重复 ——
 *    那正是 ④ 阶段 1 要修的东西。
 */
class Migration56SqlTest {

    private fun connect(): Connection =
        DriverManager.getConnection("jdbc:sqlite::memory:").apply { createSchema(this) }

    /**
     * 只建迁移 SQL 会碰到的列（够用即可）。
     *
     * ⚠️ 刻意**不建**唯一索引：这里模拟的是 v5 的库（那时还没有它），索引由迁移负责创建；
     * 否则"插两条重复产物"这一步会被索引当场拒掉，就测不到迁移的治理逻辑了。
     */
    private fun createSchema(c: Connection) {
        c.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE download_segment (
                    segment_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    node_id INTEGER NOT NULL,
                    platform_id TEXT NOT NULL,
                    download_mode TEXT NOT NULL,
                    media_container TEXT NOT NULL,
                    save_path TEXT
                )
                """.trimIndent()
            )
        }
    }

    private fun insert(
        c: Connection,
        nodeId: Long,
        platformId: String,
        downloadMode: String,
        mediaContainer: String,
        savePath: String,
    ) {
        c.prepareStatement(
            "INSERT INTO download_segment (node_id, platform_id, download_mode, media_container, save_path) " +
                "VALUES (?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setLong(1, nodeId)
            ps.setString(2, platformId)
            ps.setString(3, downloadMode)
            ps.setString(4, mediaContainer)
            ps.setString(5, savePath)
            ps.execute()
        }
    }

    private fun migrate(c: Connection) {
        Migration56Sql.statements.forEach { sql -> c.createStatement().use { it.execute(sql) } }
    }

    private fun count(c: Connection): Int =
        c.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM download_segment").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private fun savePaths(c: Connection): List<String> =
        c.createStatement().use { st ->
            st.executeQuery("SELECT save_path FROM download_segment ORDER BY segment_id").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    // ---------------------------------------------------------------- 空表安全

    @Test
    fun `空表时一行都不能删（4→5 那次整表被删的坑）`() {
        val c = connect()
        migrate(c)
        assertEquals(0, count(c))
    }

    // ---------------------------------------------------------------- 重复行治理

    @Test
    fun `同一份产物的重复行只留 segment_id 最大的那条`() {
        val c = connect()
        // 模拟历史 bug：同一 (node, platform, mode, container) 插了三条（第二十四轮真机 3 条）
        insert(c, 1L, "BV1", "VIDEO_ONLY", "mp4", "file:///old-1")
        insert(c, 1L, "BV1", "VIDEO_ONLY", "mp4", "file:///old-2")
        insert(c, 1L, "BV1", "VIDEO_ONLY", "mp4", "file:///newest")

        migrate(c)

        assertEquals(1, count(c))
        assertEquals(listOf("file:///newest"), savePaths(c))
    }

    @Test
    fun `不同产物不算重复（阶段 1 的核心承诺）`() {
        val c = connect()
        insert(c, 1L, "BV1", "AUDIO_ONLY", "m4a", "file:///audio")
        insert(c, 1L, "BV1", "VIDEO_ONLY", "mp4", "file:///video")
        insert(c, 1L, "BV1", "AUDIO_VIDEO", "mp4", "file:///both")

        migrate(c)

        assertEquals(3, count(c))
        assertEquals(listOf("file:///audio", "file:///video", "file:///both"), savePaths(c))
    }

    @Test
    fun `不同节点的同产物各自保留`() {
        val c = connect()
        insert(c, 1L, "BV1", "VIDEO_ONLY", "mp4", "file:///node-1")
        insert(c, 2L, "BV1", "VIDEO_ONLY", "mp4", "file:///node-2")

        migrate(c)

        assertEquals(2, count(c))
    }

    // ---------------------------------------------------------------- 索引语义

    @Test
    fun `迁移之后唯一索引真的拦得住重复插入`() {
        val c = connect()
        insert(c, 1L, "BV1", "VIDEO_ONLY", "mp4", "file:///a")

        migrate(c)

        var rejected = false
        try {
            insert(c, 1L, "BV1", "VIDEO_ONLY", "mp4", "file:///b")
        } catch (_: SQLException) {
            rejected = true
        }
        assertTrue("唯一索引没有拦住同一份产物的重复插入", rejected)
        // 而且原来那条不能被顶掉（这就是 insertSegment 从 REPLACE 改 IGNORE 的原因）
        assertEquals(listOf("file:///a"), savePaths(c))
    }

    @Test
    fun `迁移可以重复执行（幂等）`() {
        val c = connect()
        insert(c, 1L, "BV1", "VIDEO_ONLY", "mp4", "file:///old")
        insert(c, 1L, "BV1", "VIDEO_ONLY", "mp4", "file:///new")

        migrate(c)
        migrate(c)

        assertEquals(1, count(c))
        assertEquals(listOf("file:///new"), savePaths(c))
    }

    @Test
    fun `索引名与实体上的声明一致（Room 升级校验用）`() {
        val c = connect()
        migrate(c)
        val names = c.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index'").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
        assertTrue(
            "迁移建的索引名不在库里：$names",
            names.contains(Migration56Sql.INDEX_NAME)
        )
    }
}
