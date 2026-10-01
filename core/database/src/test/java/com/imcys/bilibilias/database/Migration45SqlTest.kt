package com.imcys.bilibilias.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * `MIGRATION_4_5` 里那段 SQL 的回归测试。
 *
 * ## 为什么要有它（2026-10-01 真机踩的坑）
 * 迁移第一版写的是：
 * ```sql
 * DELETE FROM download_segment WHERE task_id NOT IN (SELECT MAX(task_id) FROM download_task ...)
 * ```
 * 语义上"删掉较旧任务名下的子行"，看起来没毛病。但**当 `download_task` 是空表时，子查询返回空集，
 * 而 SQL 里 `x NOT IN (空集)` 恒为真** → 这条语句把 `download_segment` **整表删光**。
 * 真机现象：升级后「已完成下载」变空（文件还在磁盘上，但应用再也找不到它）。
 *
 * 这类 bug 的可怕之处在于：**只有真实升级才会跑到**，单测/编译/打包全都发现不了；
 * 而后果是用户数据凭空消失。所以这里用 JVM 上的真 SQLite 把它钉死。
 */
class Migration45SqlTest {

    private fun connect(): Connection =
        DriverManager.getConnection("jdbc:sqlite::memory:").apply { createSchema(this) }

    /** 只建迁移 SQL 会碰到的那几列，够用即可 */
    private fun createSchema(c: Connection) {
        c.createStatement().use { st ->
            st.execute("CREATE TABLE download_task (task_id INTEGER PRIMARY KEY AUTOINCREMENT, platform_id TEXT, type TEXT)")
            st.execute("CREATE TABLE download_task_node (node_id INTEGER PRIMARY KEY AUTOINCREMENT, task_id INTEGER)")
            st.execute("CREATE TABLE download_segment (segment_id INTEGER PRIMARY KEY AUTOINCREMENT, task_id INTEGER)")
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS index_download_task_platform_id_type ON download_task(platform_id, type)")
        }
    }

    private fun migrate(c: Connection) {
        Migration45Sql.statements.forEach { sql -> c.createStatement().use { it.execute(sql) } }
    }

    private fun count(c: Connection, table: String): Int =
        c.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getInt(1) }
        }

    @Test
    fun `任务表为空时不能删掉任何子行（就是真机上丢掉记录的那个 bug）`() {
        val c = connect()
        // 关键场景：子表有数据，但 download_task 是空的
        // （任务行先被删掉、子行因为外键当时没开而残留的情形）
        c.createStatement().use {
            it.execute("INSERT INTO download_segment (task_id) VALUES (7)")
            it.execute("INSERT INTO download_task_node (task_id) VALUES (7)")
        }
        assertEquals(1, count(c, "download_segment"))

        migrate(c)

        assertEquals("任务表为空时，download_segment 一行都不该少", 1, count(c, "download_segment"))
        assertEquals("任务表为空时，download_task_node 一行都不该少", 1, count(c, "download_task_node"))
    }

    @Test
    fun `同 platform_id 与 type 的重复任务只保留最新的那条`() {
        val c = connect()
        c.createStatement().use {
            it.execute("INSERT INTO download_task (task_id, platform_id, type) VALUES (1, 'BV1', 'VIDEO')")
            it.execute("INSERT INTO download_task (task_id, platform_id, type) VALUES (2, 'BV1', 'VIDEO')")
            it.execute("INSERT INTO download_task (task_id, platform_id, type) VALUES (3, 'BV2', 'VIDEO')")
            // 旧任务 1 的子行
            it.execute("INSERT INTO download_segment (task_id) VALUES (1)")
            it.execute("INSERT INTO download_task_node (task_id) VALUES (1)")
            // 新任务 2 的子行必须留下
            it.execute("INSERT INTO download_segment (task_id) VALUES (2)")
            it.execute("INSERT INTO download_task_node (task_id) VALUES (2)")
            // 另一个 platform 的任务 3 不受影响
            it.execute("INSERT INTO download_segment (task_id) VALUES (3)")
        }

        migrate(c)

        assertEquals("只剩 2 与 3 两个任务", 2, count(c, "download_task"))
        assertEquals("任务 1 的 segment 被删、2 与 3 保留", 2, count(c, "download_segment"))
        assertEquals("任务 1 的 node 被删", 1, count(c, "download_task_node"))
        c.createStatement().use { st ->
            st.executeQuery("SELECT task_id FROM download_segment ORDER BY task_id").use { rs ->
                val ids = mutableListOf<Int>()
                while (rs.next()) ids.add(rs.getInt(1))
                assertEquals(listOf(2, 3), ids)
            }
        }
    }

    @Test
    fun `空库上执行迁移不出错且会建好唯一索引`() {
        val c = connect()
        c.createStatement().use { it.execute("DROP INDEX IF EXISTS index_download_task_platform_id_type") }

        migrate(c)

        c.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND name='index_download_task_platform_id_type'")
                .use { rs -> assertTrue("唯一索引应当被创建", rs.next()) }
        }
    }

    @Test
    fun `索引确实是唯一的（重复插入会被拒）`() {
        val c = connect()
        c.createStatement().use { it.execute("INSERT INTO download_task (platform_id, type) VALUES ('BV1', 'VIDEO')") }
        var rejected = false
        try {
            c.createStatement().use { it.execute("INSERT INTO download_task (platform_id, type) VALUES ('BV1', 'VIDEO')") }
        } catch (e: Exception) {
            rejected = true
        }
        assertTrue("同 (platform_id, type) 的第二条插入必须被唯一索引拒绝", rejected)
    }
}
