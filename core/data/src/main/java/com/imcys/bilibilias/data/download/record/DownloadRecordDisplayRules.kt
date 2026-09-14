package com.imcys.bilibilias.data.download.record

/**
 * 「已完成下载」列表里那条记录**该怎么显示**的纯规则。
 *
 * ## 为什么需要它
 * 第二十一轮真机验证发现：只勾封面/弹幕/字幕（不勾媒体）时，任务会按"已完成"收尾
 * （这是对的，附加产物确实下好了），但列表里显示的却是
 * **「音视频 / 未知画质 / mp4」** —— 那三个字段只是用户当初选的元数据，
 * 与这次真正产出的东西无关。用户看到会以为视频已经下好了，点「打开」才发现文件不存在。
 *
 * 判据用 `savePath` 是否为空：正常下载完成时它一定是 `content://` URI 或文件路径
 * （`NewDownloadManager.handleSuccessor` 成功移动之后才写），而"没有媒体文件"的那条路径
 * 刻意保持为空（见 `DownloadSuccessorRules.needsMerge`）。
 */
object DownloadRecordDisplayRules {

    /** 没有媒体文件时列表上显示的标签 */
    const val EXTRAS_ONLY_TAG = "仅附加内容（无媒体文件）"

    /**
     * 这条下载记录**有没有媒体文件**。
     *
     * 没有 = 用户只勾了封面/弹幕/字幕这类附加内容，那就不存在"打开视频"这回事。
     */
    fun hasMediaFile(savePath: String): Boolean = savePath.isNotBlank()

    /**
     * 完成列表里那行标签。
     *
     * - 有媒体文件：模式 / 画质 / 封装格式（保持原样）；
     * - **没有媒体文件：只显示一个标签**，绝不显示「音视频 / 画质 / mp4」——
     *   那是"看着像下好了"的假信息。
     */
    fun tags(
        modeTitle: String,
        qualityTitle: String?,
        extension: String,
        savePath: String,
    ): List<String> = if (hasMediaFile(savePath)) {
        listOf(modeTitle, qualityTitle ?: "未知画质", extension)
    } else {
        listOf(EXTRAS_ONLY_TAG)
    }
}
