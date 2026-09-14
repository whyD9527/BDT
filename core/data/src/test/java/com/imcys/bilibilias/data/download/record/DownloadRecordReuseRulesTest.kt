package com.imcys.bilibilias.data.download.record

import com.imcys.bilibilias.database.entity.download.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「重新下载同一集」两条规则的测试。
 *
 * 守的是第十八轮审查里的两条：
 * ① 重复插出第二条同 `(nodeId, platformId)` 的记录（指向一个不存在的文件）；
 * ② 移动成品前先删同名旧文件 → 中途失败就把用户原有的文件弄没了。
 */
class DownloadRecordReuseRulesTest {

    @Test
    fun `已有记录就复用，不要再插第二条`() {
        assertEquals(
            DownloadRecordReuseRules.PersistAction.UPDATE_EXISTING,
            DownloadRecordReuseRules.persistAction(hasExistingSegment = true),
        )
        assertEquals(
            DownloadRecordReuseRules.PersistAction.INSERT_NEW,
            DownloadRecordReuseRules.persistAction(hasExistingSegment = false),
        )
    }

    @Test
    fun `复用时已完成的那条必须重置为待下载`() {
        // 关键用例：不重置的话，任务带着 COMPLETED 进内存列表，
        // 而队列只挑 WAITING → 用户点了下载却永远停在"已完成"、什么都不发生
        assertEquals(
            DownloadState.WAITING,
            DownloadRecordReuseRules.stateWhenReused(DownloadState.COMPLETED),
        )
    }

    @Test
    fun `复用时其它状态保持原样`() {
        listOf(
            DownloadState.PAUSE,
            DownloadState.WAITING,
            DownloadState.ERROR,
            DownloadState.DOWNLOADING,
        ).forEach { state ->
            assertEquals("$state 不该被改写", state, DownloadRecordReuseRules.stateWhenReused(state))
        }
    }

    @Test
    fun `临时名必须与正式名不同（否则等于直接覆盖旧文件）`() {
        val finalName = "「爱」与自动手记人偶.mp4"
        val staging = DownloadRecordReuseRules.stagingFileName(finalName)
        assertNotEquals(finalName, staging)
        assertTrue("临时名要能看出它属于哪个文件", staging.startsWith(finalName))
    }

    @Test
    fun `新文件没写完之前不许删旧文件`() {
        assertFalse(DownloadRecordReuseRules.canDeleteExistingFile(newFileWritten = false))
        assertTrue(DownloadRecordReuseRules.canDeleteExistingFile(newFileWritten = true))
    }

    @Test
    fun `查同名文件时要把带斜杠与不带斜杠两种 RELATIVE_PATH 都试一遍`() {
        // 真机踩到的：调用方给 "Download/BiliDownloader"，MediaStore 存的是
        // "Download/BiliDownloader/" —— 只查一种就永远匹配不到，旧文件永远删不掉
        val candidates = DownloadRecordReuseRules.relativePathCandidates("Download/BiliDownloader")
        assertTrue("必须包含调用方给的原样值", candidates.contains("Download/BiliDownloader"))
        assertTrue("必须包含带结尾斜杠的形式", candidates.contains("Download/BiliDownloader/"))
        assertEquals("不能出现重复候选", candidates.distinct().size, candidates.size)

        // 已经是带斜杠的形式时不该再拼出一个双斜杠
        val already = DownloadRecordReuseRules.relativePathCandidates("Download/BiliDownloader/")
        assertFalse("不能出现双斜杠", already.any { it.endsWith("//") })
        assertTrue(already.contains("Download/BiliDownloader/"))
    }
}
