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
     * 记录里写了媒体路径、但**文件已经不在了**（被文件管理器/清理软件删掉、或移到别处）。
     *
     * ⚠️ 2026-10-01 真机反馈：用户用第三方文件管理器删掉视频后，「已完成下载」里那条记录**照旧显示**
     * （列表来自数据库，不扫盘），点开才会失败，而且提示词还是"只有附加内容"。
     * 这里只负责**显示层**：标出"文件已丢失"，并让打不开时的文案说实话。
     * **绝不做"检测不到文件就自动删记录"** —— 权限、外置存储挂载、MediaStore 抖动都会误判，
     * 一次误判就是用户的记录被自动清空，比"多显示一条"严重得多。
     */
    const val MISSING_FILE_TAG = "文件已丢失"

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
        fileMissing: Boolean = false,
    ): List<String> = when {
        !hasMediaFile(savePath) -> listOf(EXTRAS_ONLY_TAG)
        // 文件丢了：先说"丢了"，再保留"原本是什么"的信息（用户才知道要不要重新下）
        fileMissing -> listOf(MISSING_FILE_TAG, modeTitle, qualityTitle ?: "未知画质", extension)
        else -> listOf(modeTitle, qualityTitle ?: "未知画质", extension)
    }

    /**
     * 点开这条记录但打不开时，该对用户说什么。
     *
     * 以前无论哪种情况都提示"只有附加内容（弹幕/封面/字幕）"——文件被外部删掉时这句是**错的**，
     * 会让人以为记录本身有问题、去反复排查应用。
     */
    fun unopenableMessage(savePath: String, fileMissing: Boolean): String = when {
        !hasMediaFile(savePath) -> "这条记录只有附加内容（弹幕/封面/字幕），没有可打开的文件"
        fileMissing -> "文件已被删除或移动（可以用右侧的删除按钮清掉这条记录，再重新下载）"
        else -> "文件打不开，可能已被删除或没有访问权限"
    }
}
