package com.imcys.bilibilias.data.download.record

/**
 * 「下载目录文件」的纯规则：**目录里的文件到底有没有下载记录在引用它**。
 *
 * ## 为什么单独抽出来
 * 两个真实现象都出在这里（2026-10-02 真机）：
 * 1. **重装后记录没了、文件还在** —— 数据库空了，目录里全成了"没人管"的文件；
 * 2. **改名后旧文件成了孤儿** —— 记录指向新名，旧名那份再也没人引用。
 *
 * 界面上必须如实标出这两类（并在 `download-trace.log` 留痕），否则用户看到
 * "已下载 0 B、目录里却有 12 GB"只会以为 app 把文件弄丢了。
 *
 * 规则本身不碰 Android API，写成纯函数以便 JVM 单测钉住语义 ——
 * 特别是**显示名必须逐字相等**：`xxx.mp4` 与 `xxx (1).mp4` 是两份文件，不是同一份。
 */
object DownloadDirFilesRules {

    /** 目录文件的统计：总数 / 其中没有任何记录引用的（孤儿）数 */
    data class OrphanSummary(val total: Int, val orphan: Int)

    /**
     * @param dirFileDisplayNames 目录里每个文件的显示名（**每个文件一项**，允许重名）
     * @param referencedNames 下载记录当前引用的显示名集合
     */
    fun summarize(
        dirFileDisplayNames: List<String>,
        referencedNames: Set<String>,
    ): OrphanSummary = OrphanSummary(
        total = dirFileDisplayNames.size,
        orphan = dirFileDisplayNames.count { isOrphan(it, referencedNames) },
    )

    /** 这个文件是否没有任何记录引用它 */
    fun isOrphan(displayName: String, referencedNames: Set<String>): Boolean =
        displayName !in referencedNames
}
