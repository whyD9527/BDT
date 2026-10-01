package com.imcys.bilibilias.download

import android.os.Environment
import java.io.File

/**
 * 下载目录的**唯一来源**（2026-10-01 改名：`BiliDownloader` → `BDT`）。
 *
 * ## 为什么要单独立一个入口
 * 目录名以前散落在 **12 处**（交付、清理、占用统计、首页提示、存储页入口、封面/二维码目录…），
 * 任何一处忘了改就会出现"两处不一致"——而这类不一致今天**已经真机咬过一次**：
 * `Download/` 前缀被拼了两次 → 目录不存在 → 清理旧文件静默 no-op（见交接文档第十四节）。
 * 所以统一从这里取，改一次就够。
 *
 * ## 旧目录（不搬移）
 * 老版本的 `Download/BiliDownloader` **不做迁移**：用户已有的文件留在原地，
 * 数据库记录里存的是绝对路径，照样能打开/播放。
 * 但**占用统计要把旧目录也算进去**（[ALL_NAMES]），否则升级后用户会看到"已下载 0 B"，
 * 以为文件被删了。
 */
object DownloadDir {

    /** 公共下载目录下的子目录名 */
    const val NAME = "BDT"

    /** 老版本的目录名（只用于统计与兜底兼容，不再往里写新文件） */
    const val LEGACY_NAME = "BiliDownloader"

    /** 需要统计体积的目录名（新 + 旧） */
    val ALL_NAMES = listOf(NAME, LEGACY_NAME)

    /** MediaStore 用的相对路径前缀（交付、清理都从这里取） */
    const val RELATIVE_PATH = "Download/$NAME"

    /** 下载根目录（`/storage/emulated/0/Download`） */
    fun downloadsRoot(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** 下载子目录（`…/Download/BDT`） */
    fun dir(name: String = NAME): File = File(downloadsRoot(), name)
}
