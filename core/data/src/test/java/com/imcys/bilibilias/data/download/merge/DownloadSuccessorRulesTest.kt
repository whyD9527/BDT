package com.imcys.bilibilias.data.download.merge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「合并 → 落盘」收尾阶段的规则测试。
 *
 * 守的是第十八轮审查里两条**只在运行时才暴露**的问题：
 * ① 只勾封面/弹幕/字幕（不勾媒体）时仍然去合并 → 取 `downloadSubTasks.first()` 抛异常 → 必然 ERROR；
 * ② 合并成功后先删源文件、再移动 → 移动失败等于"源文件没了 + 成品孤留"，用户只能整集重下。
 *
 * 这两条原先只以"代码的行序"存在，而行序没有任何测试盯得住 —— 所以规则必须落到这里。
 */
class DownloadSuccessorRulesTest {

    // ------------------------------------------------------------ 要不要合并

    @Test
    fun `勾了媒体且有子任务时才合并`() {
        assertTrue(DownloadSuccessorRules.needsMerge(downloadMedia = true, subTaskCount = 2))
        assertTrue(DownloadSuccessorRules.needsMerge(downloadMedia = true, subTaskCount = 1))
    }

    @Test
    fun `没勾媒体就不合并（只勾封面弹幕字幕时合并必然失败）`() {
        // 关键用例：附加内容（封面/弹幕/字幕）已经在前置阶段写好了，
        // 这时**没有**任何媒体子任务，走合并就是"取 first() 抛异常"。
        assertFalse(DownloadSuccessorRules.needsMerge(downloadMedia = false, subTaskCount = 0))
        // 即便因为某种原因还留着子任务，用户没勾媒体也不该合并
        assertFalse(DownloadSuccessorRules.needsMerge(downloadMedia = false, subTaskCount = 2))
    }

    @Test
    fun `勾了媒体但没有子任务也不合并（解析没给出媒体流）`() {
        assertFalse(DownloadSuccessorRules.needsMerge(downloadMedia = true, subTaskCount = 0))
    }

    // ------------------------------------------------------------ 移动之后删什么

    private val videoPath = "/data/user/0/app/files/video/123_VIDEO.m4s"
    private val audioPath = "/data/user/0/app/files/audio/123_AUDIO.m4s"
    private val tempOutput = "/data/user/0/app/files/video/123_1700000000000.mp4"

    @Test
    fun `移动成功才可以删源文件与临时产物`() {
        val toDelete = DownloadSuccessorRules.filesToDeleteAfterMove(
            moveSucceeded = true,
            subTaskPaths = listOf(videoPath, audioPath),
            tempOutputPath = tempOutput,
        )
        assertEquals(listOf(videoPath, audioPath, tempOutput), toDelete)
    }

    @Test
    fun `移动失败绝不能删源文件（否则重试只能整集重下）`() {
        val toDelete = DownloadSuccessorRules.filesToDeleteAfterMove(
            moveSucceeded = false,
            subTaskPaths = listOf(videoPath, audioPath),
            tempOutputPath = tempOutput,
        )
        // 这条断言就是这次修复本身：只允许清理私有目录里的合并产物
        assertEquals(listOf(tempOutput), toDelete)
        assertFalse("源视频文件必须留着", toDelete.contains(videoPath))
        assertFalse("源音频文件必须留着", toDelete.contains(audioPath))
    }

    @Test
    fun `空路径与重复路径不进删除列表`() {
        val toDelete = DownloadSuccessorRules.filesToDeleteAfterMove(
            moveSucceeded = true,
            subTaskPaths = listOf("", videoPath, videoPath),
            tempOutputPath = "",
        )
        assertEquals(listOf(videoPath), toDelete)
    }
}
