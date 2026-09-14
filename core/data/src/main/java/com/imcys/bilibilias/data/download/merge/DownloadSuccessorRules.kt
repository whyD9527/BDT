package com.imcys.bilibilias.data.download.merge

/**
 * 「合并 → 落盘」这个收尾阶段的**纯规则**（不碰文件、不碰 DB、不碰 Android）。
 *
 * ## 为什么抽出来
 * 这两条规则原先都只以"代码的行序"存在，而**行序是没有任何测试能盯住的**
 * （调用方在 `:app`，本机跑不了单测）。于是长出了第十八轮审查里的两条：
 *
 * 1. **只勾封面/弹幕/字幕（不勾媒体）也走合并**：没有媒体子任务 → 取
 *    `downloadSubTasks.first()` 直接抛异常 → 任务必然 ERROR，附加内容明明已经下好了；
 * 2. **合并成功后先删源文件、再移动**：移动那一步在 `try` 之外，失败时源文件已经删了、
 *    成品孤留在私有目录 —— 用户重试只能把整集重下。
 *
 * 把"删什么"做成**必须先把"移动是否成功"交进来**的纯函数，是为了让第 2 条在结构上
 * 无法复发：调用方拿不到"移动成功"这个事实，就删不掉源文件。
 */
object DownloadSuccessorRules {

    /**
     * 是否需要走 ffmpeg 合并。
     *
     * 两个条件缺一不可：
     * - `downloadMedia == false`：用户只勾了封面/弹幕/字幕，**没有媒体文件要合并**，
     *   这时合并必然失败（没有输入），应当按"附加内容已完成"收尾；
     * - `subTaskCount == 0`：解析没给出任何媒体子任务，同样没有东西可合并。
     */
    fun needsMerge(downloadMedia: Boolean, subTaskCount: Int): Boolean =
        downloadMedia && subTaskCount > 0

    /**
     * 合并产物「移入下载目录」这一步结束后，允许删除哪些文件。
     *
     * - **移动成功**：源子任务文件与私有目录里的合并产物都不再需要，可以删；
     * - **移动失败**：**只允许删私有目录里的合并产物**。源文件必须原样留着 ——
     *   它们已经完整下载过，留着的话用户重试时能跳过下载、只需再合并一次；
     *   删掉的话就只能整集重下（这正是改造前那条路径的后果）。
     *
     * @return 待删除的绝对路径（去重、去空串，调用方逐个 `deleteIfExists`）
     */
    fun filesToDeleteAfterMove(
        moveSucceeded: Boolean,
        subTaskPaths: List<String>,
        tempOutputPath: String,
    ): List<String> {
        val paths = if (moveSucceeded) subTaskPaths + tempOutputPath else listOf(tempOutputPath)
        return paths.filter { it.isNotBlank() }.distinct()
    }
}
