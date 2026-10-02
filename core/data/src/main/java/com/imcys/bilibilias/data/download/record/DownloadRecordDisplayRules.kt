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
 *
 * ## 为什么不返回文案（2026-10-02）
 * 本模块是**纯 Kotlin 模块**（`:core:data`，没有 Android 资源），以前这里直接写死中文
 * （`const val EXTRAS_ONLY_TAG = "仅附加内容（无媒体文件）"`），于是这些文案**无法本地化**。
 * 现在只返回**稳定的码**（[Tag] / [UnopenableReason]），
 * UI 层（`DownloadTaskCard` / `DownloadScreen`）再映射到 `strings.xml`。
 * 判定语义一点没变，变的只是"谁来给文案"。
 */
object DownloadRecordDisplayRules {

    /**
     * 标签里的一个槽位。
     *
     * 只有"应用生成的"标签需要翻译；模式名 / 画质名 / 扩展名来自记录本身
     * （本来就是用户数据），原样显示即可，用 [Text] 包一下。
     */
    sealed interface Tag {
        /** 没有媒体文件时列表上显示的标签：纯附加内容 */
        data object ExtrasOnly : Tag

        /**
         * 记录里写了媒体路径、但**文件已经不在了**（被文件管理器/清理软件删掉、或移到别处）。
         *
         * ⚠️ 2026-10-01 真机反馈：用户用第三方文件管理器删掉视频后，「已完成下载」里那条记录**照旧显示**
         * （列表来自数据库，不扫盘），点开才会失败，而且提示词还是"只有附加内容"。
         * 这里只负责**显示层**：标出"文件已丢失"，并让打不开时的文案说实话。
         * **绝不做"检测不到文件就自动删记录"** —— 权限、外置存储挂载、MediaStore 抖动都会误判，
         * 一次误判就是用户的记录被自动清空，比"多显示一条"严重得多。
         */
        data object MissingFile : Tag

        /** 记录里没有画质信息时的占位标签（"未知画质"） */
        data object UnknownQuality : Tag

        /** 记录自带的数据（模式 / 画质 / 封装格式）—— **不需要翻译**，UI 直接显示 [text] */
        data class Text(val text: String) : Tag
    }

    /**
     * 点开这条记录但打不开的原因；UI 据此选一条文案。
     *
     * 以前无论哪种情况都提示"只有附加内容（弹幕/封面/字幕）"——文件被外部删掉时这句是**错的**，
     * 会让人以为记录本身有问题、去反复排查应用。
     */
    enum class UnopenableReason {
        /** 只下了弹幕/封面/字幕这类附加内容，根本不存在"打开视频"这回事 */
        EXTRAS_ONLY,

        /** 记录里有媒体路径，但文件确实不在了 */
        MISSING_FILE,

        /** 其它打不开的原因（权限、URI 失效、解码失败…） */
        UNKNOWN,
    }

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
     * - 文件丢了：先说"丢了"，再保留"原本是什么"的信息（用户才知道要不要重新下）。
     */
    fun tags(
        modeTitle: String,
        qualityTitle: String?,
        extension: String,
        savePath: String,
        fileMissing: Boolean = false,
    ): List<Tag> = when {
        !hasMediaFile(savePath) -> listOf(Tag.ExtrasOnly)
        fileMissing -> listOf(
            Tag.MissingFile,
            Tag.Text(modeTitle),
            qualityTitle?.let { Tag.Text(it) } ?: Tag.UnknownQuality,
            Tag.Text(extension),
        )

        else -> listOf(
            Tag.Text(modeTitle),
            qualityTitle?.let { Tag.Text(it) } ?: Tag.UnknownQuality,
            Tag.Text(extension),
        )
    }

    /**
     * 打不开时该说哪一类原因。文案在 UI 层（`strings.xml`），这里只给码。
     */
    fun unopenableReason(savePath: String, fileMissing: Boolean): UnopenableReason = when {
        !hasMediaFile(savePath) -> UnopenableReason.EXTRAS_ONLY
        fileMissing -> UnopenableReason.MISSING_FILE
        else -> UnopenableReason.UNKNOWN
    }
}
