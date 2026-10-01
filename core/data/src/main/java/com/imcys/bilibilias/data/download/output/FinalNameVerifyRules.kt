package com.imcys.bilibilias.data.download.output

/**
 * 「成品到底有没有以**正式名**落地」的纯规则（可单测）。
 *
 * ## 为什么需要它（2026-09-15 真机复现）
 * `FileOutputManager` 往下载目录交付文件时要经过三步：**先以暂存名（`<正式名>.part`）写完整、
 * 再删同名旧文件、最后把暂存名改成正式名**。真机上重下同一集时观察到：
 *
 * ```
 * 15:03  xxx.mp3          ← 第一次下载
 * 15:08  xxx (1).mp3      ← 重下：旧文件没删掉，MediaStore 自动加了 (1)
 * 15:14  xxx (2).mp3      ← 再重下：又多一份
 * W 改名后 MediaStore 里的名字不是预期值: 期望=xxx.mp3 实际=xxx.mp3.part.mp3
 * ```
 *
 * 也就是说**改名那一步静默失败了**（回读到的还是带 `.part` 的名字），而"删同名旧文件"那一步
 * 也没把旧的删掉 —— 两个失败叠在一起，用户每次重下就多攒一份整集大小的副本。
 *
 * 这条规则把"**把目录里实际看到的名字**与**我们要的正式名/暂存名**对照"
 * 这件事变成可测的判定，调用方只负责把目录列出来。这样修完之后，
 * 同样的回归可以用单测钉住，而不必每次都靠"重下两次看目录"。
 */
object FinalNameVerifyRules {

    /** 交付结果 */
    enum class Verdict {
        /** 正式名已在目录里，且没有暂存名残留 —— 这正是我们要的终态 */
        OK,

        /** **只有暂存名**：改名那一步失败了。文件本身是完整的，但名字不对（旧实现就是卡在这里） */
        KEPT_STAGING_NAME,

        /**
         * 目录里出现了 `xxx (1).mp3` 这类**同名副本**：说明旧文件没删掉，
         * MediaStore 只能给新文件另起一个名字。
         */
        DUPLICATE_SUFFIX,

        /** 既没有正式名也没有暂存名 —— 交付彻底失败（调用方必须报错，不能当成功） */
        MISSING,
    }

    /**
     * `xxx.mp3` → 副本名的判定：`xxx (1).mp3`、`xxx (2).mp3`…
     *
     * 用正则而不是手工切下标：手工版本第一次写就错了（把 `"abc (1)".substring` 的区间算歪，
     * 结果多数字符串判不出来）—— 这类"纯字符串边界"逻辑正是该配单测的。
     */
    fun isDuplicateNameOf(name: String, expected: String): Boolean {
        val (stem, ext) = splitName(expected)
        return duplicateCopyIndex(name, stem, ext) != null
    }

    /**
     * 从【名字】里取出副本序号：`爱 (2).mp4`（相对 `stem`=爱、`ext`=.mp4）→ `2`；
     * 不是 `stem (N)ext` 这个形状则返回 null。
     *
     * 抽出来是给 [DuplicateDownloadRules] 复用的：它要按名字把"同一部视频的多份副本"
     * 分到一组（`爱.mp4` / `爱 (1).mp4` / `爱 (2).mp4`），判据与这里完全一样，
     * 两边各写一份正则迟早会走偏。
     */
    fun duplicateCopyIndex(name: String, stem: String, ext: String): Int? {
        val pattern = Regex(
            Regex.escape(stem) + " \\((\\d+)\\)" + Regex.escape(ext)
        )
        return pattern.matchEntire(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /** 把 `xxx.mp4` 拆成 (`xxx`, `.mp4`)；没有扩展名时 ext 为空串 */
    fun splitName(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    }

    /**
     * 按"目录里实际存在的名字集合"判定交付结果。
     *
     * @param expectedName 正式名（如 `xxx.mp3`）
     * @param stagingName 暂存名（如 `xxx.mp3.part`）
     * @param existingNames 目录里当前实际存在的同名相关文件名（含暂存名与副本名）
     */
    fun verify(
        expectedName: String,
        stagingName: String,
        existingNames: Collection<String>,
    ): Verdict {
        val hasExpected = existingNames.contains(expectedName)
        val hasStaging = existingNames.contains(stagingName)
        val hasDuplicate = existingNames.any { isDuplicateNameOf(it, expectedName) }

        return when {
            hasExpected && !hasStaging -> Verdict.OK
            hasStaging -> Verdict.KEPT_STAGING_NAME
            hasDuplicate -> Verdict.DUPLICATE_SUFFIX
            else -> Verdict.MISSING
        }
    }

    /**
     * 与 [expectedName] 指向"同一份内容"的文件名：正式名 + MediaStore 自动加的 `xxx (1).mp3` 副本。
     *
     * ⚠️ 真机取证（2026-09-15）：重下同一集时旧文件既没被删、改名也没生效，
     * 而**按名字去 MediaStore 查是"命中=0"** —— 也就是说那条链路在这台设备上整体不可靠。
     * 既然文件就在我们能直接枚举的目录里，就**按目录枚举来判断"哪些是同一份内容"**，
     * 不再依赖 MediaStore 的等值匹配（这正是 H8/RELATIVE_PATH 那类坑的同源教训：
     * 匹配条件写错时，删除会**静默变成 no-op**）。
     *
     * @param keepName 本次**刚交付的那一份**在磁盘上的名字（通常传媒体库回读到的 DISPLAY_NAME）。
     *   它绝不是"旧文件"，必须排除，否则刚写好的成品会被自己删掉 ——
     *   而判定又只看媒体库回读（名字是对的）→ 报成功、磁盘上却没有文件（2026-09-15 复审）。
     */
    fun siblingCopies(
        expectedName: String,
        names: Collection<String>,
        keepName: String? = null,
    ): List<String> =
        names.filter { it == expectedName || isDuplicateNameOf(it, expectedName) }
            .filterNot { keepName != null && it == keepName }

    /**
     * 回读到的 `DISPLAY_NAME` 是不是我们要的正式名。
     *
     * 真机上它读到的是暂存名 —— 调用方必须**据此判定改名失败**，
     * 而不是像旧代码那样只打一条"观测日志"就当成功了。
     */
    fun displayNameMatches(expectedName: String, storedName: String?): Boolean =
        storedName == expectedName

    /**
     * 这个名字（不管来自目录枚举还是 MediaStore 行）是不是与 [expectedName] **同一份内容**：
     * 正式名本身，或者 MediaStore 自动加的 `xxx (1).mp3` 副本名。
     *
     * 供"按行删旧文件"用 —— 只删同名/副本名，别的文件一个都不许碰。
     */
    fun isSameContentName(name: String, expectedName: String): Boolean =
        name == expectedName || isDuplicateNameOf(name, expectedName)

    /**
     * 生成 SQL `LIKE` 模式，用来一次查出 `xxx.mp3` 以及 `xxx (1).mp3`、`xxx (2).mp3`…
     *
     * ⚠️ 两个必须做对的地方（这是 2026-10-01 真机复现后的修法）：
     * 1. **转义**：文件名里 `_` 极常见（`第1话_A_1080P.mp4`），而 `_` 在 SQL LIKE 里是
     *    "任意一个字符"的通配符 —— 不转义会命中别人的文件；`%` 与转义符本身同理。
     *    调用方的 selection 必须带 `ESCAPE '\'`。
     * 2. 副本名的形状是 **`stem` + " (" + 数字 + ")" + 扩展名**，所以模式是
     *    `stem` + `" (%)"` + `ext`（而不是 `expected + "(%)"`）。
     *
     * 纯函数，配了单测（`FinalNameVerifyRulesTest`），因为这正是"静默 no-op"的高发区：
     * 模式写错时查询命中 0，删除看起来毫无异常。
     */
    fun duplicateNameLikePattern(expectedName: String): String {
        val dot = expectedName.lastIndexOf('.')
        val stem = if (dot > 0) expectedName.substring(0, dot) else expectedName
        val ext = if (dot > 0) expectedName.substring(dot) else ""
        return escapeLike(stem) + " (%)" + escapeLike(ext)
    }

    /** 转义 SQL `LIKE` 的通配符（配合 `ESCAPE '\'` 使用） */
    private fun escapeLike(text: String): String =
        text.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
}
