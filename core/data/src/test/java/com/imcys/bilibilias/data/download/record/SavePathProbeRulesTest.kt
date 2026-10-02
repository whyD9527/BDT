package com.imcys.bilibilias.data.download.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「savePath 还在不在」统一判据的测试（⑥）。
 *
 * 守的是这台 ROM 上最难分清的一对：**"文件真没了" 与 "有权限问题/看不了"** ——
 * 两者都可能表现为 `FileNotFoundException` / `File.exists()==false`。
 * 这里把"宽（别误标丢失）"与"严（别复用打不开的记录）"两种语义钉死。
 */
class SavePathProbeRulesTest {

    @Test
    fun `形态判定：空 content 与文件路径`() {
        assertEquals(SavePathProbeRules.Shape.BLANK, SavePathProbeRules.shapeOf(""))
        assertEquals(SavePathProbeRules.Shape.BLANK, SavePathProbeRules.shapeOf("   "))
        assertEquals(
            SavePathProbeRules.Shape.CONTENT_URI,
            SavePathProbeRules.shapeOf("content://media/external/downloads/125297"),
        )
        assertEquals(
            SavePathProbeRules.Shape.FILE_PATH,
            SavePathProbeRules.shapeOf("/storage/emulated/0/Download/BDT/某视频.mp4"),
        )
        assertEquals(
            SavePathProbeRules.Shape.FILE_PATH,
            SavePathProbeRules.shapeOf("file:///storage/emulated/0/Download/BDT/某视频.mp4"),
        )
    }

    @Test
    fun `从文件路径取文件名`() {
        assertEquals("某视频.mp4", SavePathProbeRules.fileNameOf("/storage/emulated/0/Download/BDT/某视频.mp4"))
        assertEquals("某视频.mp4", SavePathProbeRules.fileNameOf("file:///sdcard/Download/BDT/某视频.mp4"))
        // 带 query/fragment 的也要能取（有些调用方塞的是 Uri 字符串）
        assertEquals("a.mp4", SavePathProbeRules.fileNameOf("file:///sdcard/a.mp4?x=1"))
        // 目录（以 / 结尾）没有文件名
        assertEquals("", SavePathProbeRules.fileNameOf("/storage/emulated/0/Download/BDT/"))
    }

    @Test
    fun `按名字探测要同时覆盖新旧下载目录且去重`() {
        assertEquals(
            listOf("Download/BDT", "Download/BiliDownloader"),
            SavePathProbeRules.probeRelativePaths(listOf("BDT", "BiliDownloader")),
        )
        // 带斜杠/空白/重复的输入要被归一
        assertEquals(
            listOf("Download/BDT"),
            SavePathProbeRules.probeRelativePaths(listOf("/BDT/", "BDT", "  ")),
        )
        assertEquals(emptyList<String>(), SavePathProbeRules.probeRelativePaths(listOf("", "   ")))
    }

    @Test
    fun `四个信号全为假才算真的没了`() {
        val blank = SavePathProbeRules.Signals()
        assertFalse("空 savePath：宽判据也是假", blank.existsLenient)
        assertFalse("空 savePath：严判据也是假", blank.existsStrict)

        val gone = SavePathProbeRules.Signals(shape = SavePathProbeRules.Shape.FILE_PATH)
        assertFalse("file 路径全假：宽判据是假", gone.existsLenient)
        assertFalse("file 路径全假：严判据是假", gone.existsStrict)
    }

    @Test
    fun `content 的行还在但打不开，要判成缺失（2026-10-02 验证过的语义，别改回去）`() {
        // content:// 的行是本 app 自己交付时插的：能开就应该开得开。
        // 打不开（FNF）说明底层文件真没了 —— 此时"行还在"**不足以**判定"文件还在"。
        val rowButUnopenable = SavePathProbeRules.Signals(
            shape = SavePathProbeRules.Shape.CONTENT_URI,
            mediaRowFound = true,
        )
        assertFalse("content：行在但打不开 → 标「文件已丢失」", rowButUnopenable.existsLenient)
        assertFalse("当然也不能复用", rowButUnopenable.existsStrict)
    }

    @Test
    fun `file 路径的媒体库行是唯一正信号（宽判据），但不能直接复用（严判据）`() {
        // 真机上的真实组合：媒体库拥有该文件 → File.exists() 恒 false、打开 EACCES 抛 FNF，
        // 只有"按名字问媒体库"能证明它还在。宽判据据此**不标丢失**；严判据据此**不复用**。
        val rowOnly = SavePathProbeRules.Signals(
            shape = SavePathProbeRules.Shape.FILE_PATH,
            mediaRowFound = true,
        )
        assertTrue("file：行在 → 不标丢失", rowOnly.existsLenient)
        assertFalse("file：行在但打不开 → 不当作可复用", rowOnly.existsStrict)
    }

    @Test
    fun `能打开或以磁盘为准都算存在（两种判据都通过）`() {
        val opened = SavePathProbeRules.Signals(shape = SavePathProbeRules.Shape.CONTENT_URI, opened = true)
        assertTrue(opened.existsLenient)
        assertTrue(opened.existsStrict)

        val onDisk = SavePathProbeRules.Signals(shape = SavePathProbeRules.Shape.FILE_PATH, fileExists = true)
        assertTrue(onDisk.existsLenient)
        assertTrue(onDisk.existsStrict)
    }

    @Test
    fun `未知异常按存在处理（失败方向安全）`() {
        // 权限异常/SecurityException 之类分不清"没了"与"没权限"→ 当作还在，绝不误标丢失
        val unknown = SavePathProbeRules.Signals(
            shape = SavePathProbeRules.Shape.CONTENT_URI,
            unknownError = true,
        )
        assertTrue(unknown.existsLenient)
        // 但"未知异常"不是"能打开"，所以严判据仍然拒绝（该重下就重下）
        assertFalse(unknown.existsStrict)
    }

    @Test
    fun `信号可以叠加，任一为正即宽判据为真`() {
        assertTrue(
            SavePathProbeRules.Signals(
                shape = SavePathProbeRules.Shape.FILE_PATH,
                mediaRowFound = true,
                fileExists = true,
            ).existsLenient,
        )
        assertTrue(
            SavePathProbeRules.Signals(
                shape = SavePathProbeRules.Shape.CONTENT_URI,
                opened = true,
                unknownError = true,
            ).existsStrict,
        )
    }
}
