package com.imcys.bilibilias.data.download.record

/**
 * 「记录里的 `savePath` 现在还在不在」的**统一判据**（纯规则，⑥）。
 *
 * ## 为什么必须统一
 * 这台 ROM 上"文件是否存在"有两套互相矛盾的判据散在各处（交接文档 §14.2 第 3 条）：
 * - `File.exists()`：对 **MediaProvider 拥有**的文件**恒为 false**（app 是 uid 10851、
 *   文件属主是 media module 的 10271，scoped storage 直接不给 stat）；
 * - `openFileDescriptor`：拿不到权限时抛 `FileNotFoundException` —— 与"文件真的没了"**同一种异常**。
 *
 * 于是出现过"文件明明在、却被标成「文件已丢失」"的误报（2026-10-02 真机），
 * 也差点出现"文件其实没了、却因为按名字查到行而当作还在"。
 * 判据只有放在一处、并且**把两种用途分开**才说得清：
 *
 * | 用途 | 判据 | 误判方向 |
 * |---|---|---|
 * | 「文件已丢失」标签（[Signals.existsLenient]） | **宽**（但分形态，见下） | 宁可多显示一条"正常"记录，也不误标丢失 |
 * | 「跳过已下载」复用（[Signals.existsStrict]） | **严**：必须真能打开 / 真在磁盘上 | 宁可多下一次，也不复用一条打不开的记录 |
 *
 * 宽判据**分形态**，因为同是"有行但打不开"，两种 `savePath` 的含义相反：
 * - `content://`（行是本 app 自己的、交付时插入的）：**能开就应该开得开** → 打不开（`FNF`）说明
 *   底层文件真没了 → **宽判据也说"没了"**。这是 2026-10-02 真机验证过的语义（那次修的是
 *   `file://` 的误报），**别改回去**；
 * - `file://`（媒体库拥有、app 只有 EACCES）：`File.exists()` 恒 false、打开也抛同一个 `FNF`，
 *   唯一能问的就是"媒体库里还有没有这一行" → **有行就算还在**（宁可多显示一条正常记录）。
 */
object SavePathProbeRules {

    /** `savePath` 的形态 */
    enum class Shape {
        /** 空/空白：根本没有媒体文件（只勾了封面/弹幕/字幕那种记录） */
        BLANK,

        /** `content://media/...`：正常的交付结果（`_ID` 就是行主键） */
        CONTENT_URI,

        /** 绝对路径（Android 10 以下，或历史记录）：最后一段是**文件名** */
        FILE_PATH,
    }

    fun shapeOf(savePath: String): Shape = when {
        savePath.isBlank() -> Shape.BLANK
        savePath.startsWith("content://") -> Shape.CONTENT_URI
        else -> Shape.FILE_PATH
    }

    /**
     * 文件路径的"最后一段"（= 文件名），用来**按名字问媒体库**。
     *
     * ⚠️ 只对 [Shape.FILE_PATH] 有意义：`content://media/external/downloads/125297`
     * 的最后一段是 `_ID`（125297），**不是**显示名 —— 拿它去按名字查必然查不到。
     * 以 `/` 结尾（目录）时返回空串。
     */
    fun fileNameOf(filePath: String): String {
        val withoutQuery = filePath.substringBefore('?').substringBefore('#')
        // 以 `/` 结尾 = 目录：目录没有"文件名"，返回空串（否则会把目录名当文件名去查媒体库）
        if (withoutQuery.endsWith("/")) return ""
        val trimmed = withoutQuery.trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return trimmed.substringAfterLast('/')
    }

    /**
     * 按名字问媒体库时要试哪些**相对公共下载目录根**的路径。
     *
     * 新目录（`BDT`）和改名前的旧目录（`BiliDownloader`）都要试：目录改名时**不做搬移**，
     * 老记录的 `savePath` 可能指向旧目录（见 `DownloadDir.ALL_NAMES`）。
     */
    fun probeRelativePaths(downloadDirNames: List<String>): List<String> =
        downloadDirNames
            // 先 trim 空白再 trim 斜杠：否则 `"  "` 会变成 `"Download/  "` 这种东西
            .map { it.trim().trim('/') }
            .filter { it.isNotEmpty() }
            .map { "${DownloadRecordReuseRules.DOWNLOADS_DIR_NAME}/$it" }
            .distinct()

    /**
     * 一次探测能拿到的四个信号（都由 Android 侧采集，这里只做判定）。
     *
     * @param mediaRowFound 媒体库里还有这一行（**这台 ROM 上最可靠的正信号**）
     * @param opened 真的能打开（`openFileDescriptor` 成功）
     * @param fileExists `File(...).exists()` 为真（对媒体库文件不可靠，但对非媒体文件有效）
     * @param unknownError 抛了"未知异常"（非 `FileNotFoundException`）：**按存在处理**，失败方向安全
     */
    data class Signals(
        val shape: Shape = Shape.BLANK,
        val mediaRowFound: Boolean = false,
        val opened: Boolean = false,
        val fileExists: Boolean = false,
        val unknownError: Boolean = false,
    ) {
        /**
         * 宽判据：用于「文件已丢失」标签（见类注释的表格）。
         *
         * ⚠️ `mediaRowFound` **只对 [Shape.FILE_PATH] 算正信号**：
         * `content://` 的行是本 app 自己的，能开就该开得开 → 打不开就说明文件真没了，
         * 光有行不足以判"在"。
         */
        val existsLenient: Boolean
            get() = when (shape) {
                Shape.BLANK -> false
                Shape.CONTENT_URI -> opened || unknownError
                Shape.FILE_PATH -> mediaRowFound || opened || fileExists || unknownError
            }

        /** 严判据：用于「跳过已下载」复用（见类注释的表格）—— 必须真能打开 / 真在磁盘上 */
        val existsStrict: Boolean
            get() = opened || fileExists
    }
}
