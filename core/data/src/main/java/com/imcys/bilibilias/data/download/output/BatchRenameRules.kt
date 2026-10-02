package com.imcys.bilibilias.data.download.output

/**
 * 批量重命名的**纯规则**：给 N 个选中项分配互不相同的「新基名」。
 *
 * ## 为什么必须由我们自己分配后缀
 * 这台 ROM（小米 / Android 16）上 `update(DISPLAY_NAME)` **撞名不会报错**，
 * MediaProvider 直接把新名字改成 `xxx (1).mp4`（见交接文档 §14.2 第 1、2 条）。
 * 于是"把选中的 5 个文件都改成同一个新名字"的实际结果是：5 个文件被按处理顺序
 * 分别改成 `新名.mp4`、`新名 (1).mp4`… —— 序号是 MediaProvider 给的，
 * **与用户选中的顺序、甚至与"用户想要哪个文件叫哪个名字"都对不上**，而且不可控。
 *
 * 所以这里**主动**分配后缀：
 * - 第 1 个用清理后的原名；
 * - 第 i 个（i ≥ 1）用 `新名 (i)`（与 MediaStore 自己的后缀形状一致，观感自然）。
 *
 * 真正落到文件上的名字仍由 `FileOutputManager.renameDownloadFile` **回读**确认 ——
 * 万一还是撞上了别的文件（比如目录里本来就有 `新名 (1).mp4`），回读出来的才是真相。
 *
 * ## 名字清理
 * `/` 必须清掉：MediaStore 拒绝含 `/` 的 `DISPLAY_NAME`，legacy 分支则直接
 * `FileNotFoundException`，而这两条都会让整次操作失败（交接文档 H8 踩过）。
 * 控制字符一并去掉，结尾的点和空格也去掉（部分文件系统/SMB 会吞掉它们）。
 */
object BatchRenameRules {

    /** 清理时会处理掉的、本身没有"名字信息"的字符 */
    private val PATH_OR_DOT = charArrayOf('/', '\\', '.')

    /**
     * 把用户输入清理成可用的**基名**（不含扩展名、不含路径分隔符）。
     *
     * 只做"纯字符串"层面的清理，不判断重名（重名由 MediaStore 回读负责）。
     */
    fun sanitizeBaseName(raw: String): String =
        raw.trim()
            .replace('/', '_')
            .replace('\\', '_')
            .filterNot { it.isISOControl() }
            .trim()
            .trimEnd('.', ' ')

    /**
     * 清理后的基名能不能用。
     *
     * 三种情况都算不能用（UI 据此禁用确认按钮）：
     * 1. 清理后为空（空串、全空白、只有控制字符、只有结尾的点）；
     * 2. 清理后包含 `..`（路径穿越的味道）；
     * 3. **原输入只由 `/ \ .`、空白、控制字符组成**（如 `"/"`）——
     *    它清理出来是 `"_"`，虽然非空但没有任何"名字信息"，不该被当作用户想用的名字。
     */
    fun isUsableBaseName(raw: String): Boolean {
        val base = sanitizeBaseName(raw)
        if (base.isEmpty() || base.contains("..")) return false
        return raw.trim().any { it !in PATH_OR_DOT && !it.isWhitespace() && !it.isISOControl() }
    }

    /**
     * 给 [count] 个选中项分配**基名**（不含扩展名）。
     *
     * @return 分配好的基名列表（顺序与调用方传入的选中项一致）；
     *   `count <= 0` 或名字不可用（[isUsableBaseName]）时返回 **null** ——
     *   调用方此时**不要动任何文件**，no-op 永远比"把文件改成半个名字"安全。
     */
    fun assignBaseNames(rawBaseName: String, count: Int): List<String>? {
        if (count <= 0) return null
        if (!isUsableBaseName(rawBaseName)) return null
        val base = sanitizeBaseName(rawBaseName)
        return List(count) { index -> if (index == 0) base else "$base ($index)" }
    }
}
