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
        val dot = expected.lastIndexOf('.')
        val stem = if (dot > 0) expected.substring(0, dot) else expected
        val ext = if (dot > 0) expected.substring(dot) else ""
        val pattern = Regex(
            Regex.escape(stem) + " \\((\\d+)\\)" + Regex.escape(ext)
        )
        return pattern.matches(name)
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
     * 回读到的 `DISPLAY_NAME` 是不是我们要的正式名。
     *
     * 真机上它读到的是暂存名 —— 调用方必须**据此判定改名失败**，
     * 而不是像旧代码那样只打一条"观测日志"就当成功了。
     */
    fun displayNameMatches(expectedName: String, storedName: String?): Boolean =
        storedName == expectedName
}
