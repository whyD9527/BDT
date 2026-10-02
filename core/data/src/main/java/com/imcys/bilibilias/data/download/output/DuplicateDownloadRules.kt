package com.imcys.bilibilias.data.download.output

/**
 * 「重复下载文件」的分组规则（纯函数、可单测）。
 *
 * ## 为什么需要它（2026-10-01 真机复现）
 * 这台 ROM 的 MediaStore 在"目标名字已被占用"时会自动把新文件改名成 `xxx (1).mp4`、
 * `xxx (2).mp4`…，而 app 既不能直接 `File.delete()`（文件属主是 MediaProvider）、
 * 事后按行删又常常命中 0 —— 结果就是**同一个视频在下载目录里攒出好几份 72MB 的副本**。
 *
 * 自动判定"哪个是旧文件"在这台设备上被 ROM 的实现细节堵死了（详见 `FileOutputManager`
 * 里 moveAside/deleteSameContentRows 的注释），所以退一步：**让用户点一下确认**。
 * 这个文件负责"把目录里的名字分成组"这件纯逻辑：
 *
 * ```
 * ["爱.mp4", "爱 (1).mp4", "爱 (2).mp4"]      → 一组，保留 "爱.mp4"，可删 (1)(2)
 * ["爱 (2).mp4", "爱 (3).mp4"]                → 一组，保留 (2)（序号最小的那份），可删 (3)
 * ["爱.mp4", "别的视频.mp4"]                   → 两组，都不重复（不提示）
 * ```
 *
 * ⚠️ 判据是**文件名**（同 stem + 同扩展名，且后缀是纯数字的 `(N)`），不是文件内容哈希 ——
 * 目录里 stat 都可能被拒（scoped storage），算哈希更不可能；而"同一部视频的副本"
 * 恰恰就是 MediaStore 用 ` (N)` 命名的那些，所以按名字分组足够准，且**只多不少**地
 * 交给用户确认（默认全选可删项，用户可取消勾选）。
 */
object DuplicateDownloadRules {

    /** 一组重复文件：[keepName] 建议保留，[removableNames] 是其余副本 */
    data class DuplicateGroup(
        val keepName: String,
        val removableNames: List<String>,
    ) {
        val allNames: List<String> get() = listOf(keepName) + removableNames
    }

    /**
     * 名字的"族标识"：去掉结尾的 ` (N)` 后剩下的部分。
     * `爱 (2).mp4` → `爱.mp4`；`爱.mp4` → `爱.mp4`；`带 (括号) 的标题.mp4` → 原样（括号里不是纯数字）。
     */
    fun familyKey(name: String): String =
        FinalNameVerifyRules.stripDuplicateSuffix(name)?.first ?: name

    /** 名字里的副本序号（`爱 (2).mp4` → 2）；不是副本名则 null */
    fun copyIndex(name: String): Int? =
        FinalNameVerifyRules.stripDuplicateSuffix(name)?.second

    /**
     * 把一组文件名分成重复组。**只有 ≥2 个成员的族才算重复**，其余忽略。
     *
     * 保留哪一份：优先**没有 `(N)` 后缀**的那个（那才是"正式名"）；
     * 若全都有后缀，保留序号最小的（最早生成的那份，通常最完整）。
     */
    fun groupDuplicates(
        names: Collection<String>,
        /**
         * **App 的记录（`savePath`）当前引用的那些显示名** —— 它们绝不能被当成"可删副本"。
         *
         * ⚠️ 为什么（2026-10-02 真机复现）：真机上出现过"记录引用的是 `… (2).m4a`，
         * 而正式名那份反而是孤儿"（因为交付时 `insert` 先落了 `(2)`、后续改名没生效）。
         * 只按名字挑"正式名"保留 → 照它清理会把那条记录删成「文件已丢失」。
         * 所以排序键第一位改成"记录引用的优先保留"。
         */
        referencedNames: Set<String> = emptySet(),
    ): List<DuplicateGroup> =
        names.filter { it.isNotBlank() }
            .groupBy { familyKey(it) }
            .filter { (_, members) -> members.size >= 2 }
            .map { (_, members) ->
                val keep = members.minWithOrNull(
                    compareBy(
                        // ① 记录实际引用的那份最优先保留（这是 B 的修法）
                        { if (it in referencedNames) 0 else 1 },
                        // ② 没有后缀的排最前
                        { if (copyIndex(it) == null) 0 else 1 },
                        { copyIndex(it) ?: 0 },
                        { it },
                    ),
                ) ?: members.first()
                DuplicateGroup(
                    keepName = keep,
                    removableNames = members.filterNot { it == keep }.sorted(),
                )
            }
            .sortedBy { it.keepName }
}
