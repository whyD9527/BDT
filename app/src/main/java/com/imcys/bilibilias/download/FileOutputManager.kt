package com.imcys.bilibilias.download

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import com.imcys.bilibilias.data.download.record.DownloadRecordReuseRules
import com.imcys.bilibilias.data.download.output.FinalNameVerifyRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 文件输出管理器
 * 负责处理所有文件输出操作和MediaStore注册
 */
class FileOutputManager(
    private val context: Application
) {
    /**
     * 创建字幕输出流
     */
    fun createSubtitleOutputStream(fileName: String, type: SubtitleType): OutputStream {
        val mime = when (type) {
            SubtitleType.ASS -> "text/x-ass"
            SubtitleType.SRT -> "application/x-subrip"
        }
        return createDownloadOutputStream(fileName, mime, "BiliDownloader")
    }

    /**
     * 创建弹幕输出流
     */
    suspend fun createDanmakuOutputStream(fileName: String): OutputStream =
        withContext(Dispatchers.IO) {
            createDownloadOutputStream(fileName, "application/xml", "BiliDownloader/Danmaku")
        }

    /**
     * 下载图片到相册
     */
    suspend fun downloadImageToAlbum(
        imageBytes: ByteArray,
        fileName: String,
        saveDirName: String
    ) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveImageMediaStore(imageBytes, fileName, saveDirName)
        } else {
            saveImageLegacy(imageBytes, fileName, saveDirName)
        }
    }

    /**
     * 将文件移动到下载目录并注册到媒体库
     */
    suspend fun moveToDownloadAndRegister(
        file: File,
        fileName: String,
        mimeType: String
    ): String? = withContext(Dispatchers.IO) {
        val parts = fileName.split("/")
        val actualFileName = parts.last()
        val folderPath = if (parts.size > 1) parts.dropLast(1).joinToString("/") else ""
        val relativePath = if (folderPath.isNotEmpty())
            "${Environment.DIRECTORY_DOWNLOADS}/BiliDownloader/$folderPath"
        else
            "${Environment.DIRECTORY_DOWNLOADS}/BiliDownloader"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            moveToDownloadMediaStore(file, actualFileName, relativePath, mimeType)
        } else {
            moveToDownloadLegacy(file, actualFileName, folderPath)
        }
    }

    // Private helper methods

    private fun createDownloadOutputStream(
        fileName: String,
        mimeType: String,
        relativePath: String
    ): OutputStream {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/$relativePath")
                }
            ) ?: throw Exception("MediaStore insert failed")
            context.contentResolver.openOutputStream(uri)
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                relativePath
            ).apply { mkdirs() }
            FileOutputStream(File(dir, fileName))
        }.let { checkNotNull(it) { "OutputStream == null" } }
    }

    private fun saveImageMediaStore(imageBytes: ByteArray, fileName: String, saveDirName: String) {
        val resolver = context.contentResolver
        val relativeRoot = Environment.DIRECTORY_PICTURES
        val relativePath = "$relativeRoot/$saveDirName"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/${fileName.substringAfterLast('.')}")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            try {
                resolver.openOutputStream(it)?.use { out ->
                    out.write(imageBytes)
                    out.flush()
                }
            } catch (e: Exception) {
                runCatching { resolver.delete(it, null, null) }
                throw e
            } finally {
                val update = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                runCatching { resolver.update(it, update, null, null) }
            }
        }
    }

    private fun saveImageLegacy(imageBytes: ByteArray, fileName: String, saveDirName: String) {
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            .absolutePath + "/$saveDirName"
        val albumDir = File(baseDir).apply { if (!exists()) mkdirs() }
        val outFile = File(albumDir, fileName)

        FileOutputStream(outFile).use { out ->
            out.write(imageBytes)
            out.flush()
        }

        MediaScannerConnection.scanFile(
            context,
            arrayOf(outFile.absolutePath),
            arrayOf("image/${fileName.substringAfterLast('.')}"),
            null
        )
    }

    private fun moveToDownloadMediaStore(
        file: File,
        fileName: String,
        relativePath: String,
        mimeType: String
    ): String? {
        val resolver = context.contentResolver

        // ⚠️ 顺序是这个函数的关键：**先把新文件完整写出来，才允许动旧文件**。
        // 原先一进来就把同名旧文件删掉，再去 insert/copy —— 中途任何一步失败
        // （空间不足、MediaStore 拒绝、读写出错）都会让用户**原有的那个文件没了、新的也没有**。
        // 现在先用一个临时名字建行、写完，成功之后才删旧文件并改名。
        val stagingName = DownloadRecordReuseRules.stagingFileName(fileName)
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, stagingName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            }
        ) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                file.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: error("openOutputStream 返回 null")
        } catch (e: Exception) {
            // 新文件没写成：把这一行删掉，**旧文件原样保留**（这正是这次修复的意义）
            runCatching { resolver.delete(uri, null, null) }
            Log.e(TAG, "写入下载目录失败，已回滚（旧文件未动）: $fileName", e)
            return null
        }

        // ⚠️ 顺序（2026-09-15 真机复现后重写）：**先把暂存名改成正式名，再去删同名旧文件**。
        //
        // 原来反着来（先 deleteExistingSameName、再 rename），真机上观察到两步都没生效：
        //   15:03  xxx.mp3   → 15:08  xxx (1).mp3  → 15:14  xxx (2).mp3
        //   W 改名后 MediaStore 里的名字不是预期值: 期望=xxx.mp3 实际=xxx.mp3.part.mp3
        // 也就是**改名静默失败**（回读还是 `.part`）、**旧文件也没被删掉**，两个失败叠在一起，
        // 用户每重下一次就多攒一份整集大小的副本。旧代码只把这两件事各打一条日志就当成功了。
        //
        // 现在：改名 → 删旧 → **回读校验**，不对就重试一轮删旧文件；最终仍不对就
        // **删掉这次的新文件并返回 null**（宁可让上层报"移动失败"，也不留副本、不报假成功）。
        renameStaging(resolver, uri, fileName, stagingName)

        // ① 先按**目录枚举**把同一份内容的旧文件删掉（真机上 MediaStore 那条"按名字查"命中=0，
        //    只有这条路真的能删掉，详见 deleteSiblingCopies 的注释）
        directoryOf(uri)?.let { dir -> deleteSiblingCopies(fileName, dir) }

        // ② MediaStore 那条也照旧走一遍（能命中就命中，命中不了也不影响 ①）
        deleteExistingSameName(resolver, fileName, relativePath)

        if (!verifyFinalName(resolver, uri, stagingName, fileName)) {
            // 重试一轮：目录枚举再删一次（新插入的那一行可能刚被 MediaStore 标了名）
            Log.w(TAG, "第一次交付后名字不符，重试删除同名旧文件: 期望=$fileName")
            directoryOf(uri)?.let { dir -> deleteSiblingCopies(fileName, dir) }
            deleteExistingSameName(resolver, fileName, relativePath)
        }

        val verdict = finalNameVerdict(resolver, uri, fileName, stagingName)
        if (verdict != FinalNameVerifyRules.Verdict.OK) {
            Log.e(
                TAG,
                "交付失败（$verdict）：期望=$fileName 回读=${storedDisplayName(resolver, uri)}",
            )
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        // 磁盘上已经是正式名了，但**媒体库那一行**可能还记着暂存名
        // （真机取证：改名返回 0 行、回读仍是 `xxx.mp3.part.mp3`）。
        // 用户在图库/文件里看到的就是这个名字，所以必须把它也修好：
        // 先试一次 update；还是不认就删掉这一行、让媒体扫描按磁盘上的真实名字重建。
        val fixedUri = ensureStoredName(resolver, uri, fileName, directoryOf(uri))
        file.delete()
        return fixedUri.toString()
    }

    /**
     * 保证媒体库里那一行的 DISPLAY_NAME 与磁盘上的正式名一致。
     *
     * 返回可用的 uri（修不好时返回原 uri —— 文件本身是好的，不能让整次下载白费）。
     */
    private fun ensureStoredName(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        fileName: String,
        dir: File?,
    ): android.net.Uri {
        if (FinalNameVerifyRules.displayNameMatches(fileName, storedDisplayName(resolver, uri))) {
            return uri
        }
        // 1) 再试一次改名
        renameStaging(resolver, uri, fileName, storedDisplayName(resolver, uri) ?: "?")
        if (FinalNameVerifyRules.displayNameMatches(fileName, storedDisplayName(resolver, uri))) {
            return uri
        }
        // 2) 改名这条路在这台设备上不可靠 → 删掉这一行，扫一次目录让它重建
        Log.w(TAG, "改名仍不生效，改为删行+重新扫描: 期望=$fileName")
        runCatching { resolver.delete(uri, null, null) }
        val target = dir?.let { File(it, fileName) }
        if (target == null || !target.exists()) return uri
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        }
        // 3) 按新扫描出来的行回读一次，拿到正确的 uri
        return runCatching {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(fileName),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        c.getLong(0),
                    )
                } else {
                    uri
                }
            } ?: uri
        }.getOrDefault(uri)
    }

    /** 把刚插入的暂存名改成正式名（失败只记日志，由后面的回读校验统一判定） */
    private fun renameStaging(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        fileName: String,
        stagingName: String,
    ) {
        val rows = runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, fileName) },
                null,
                null,
            )
        }.getOrDefault(0)
        if (rows <= 0) {
            Log.w(TAG, "MediaStore 改名未生效（影响 $rows 行）: $stagingName → $fileName")
        }
    }

    /** 交付结果判定：先看回读到的 DISPLAY_NAME，再用目录里实际存在的名字兜一层 */
    private fun finalNameVerdict(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        fileName: String,
        stagingName: String,
    ): FinalNameVerifyRules.Verdict {
        val stored = storedDisplayName(resolver, uri)
        if (FinalNameVerifyRules.displayNameMatches(fileName, stored)) {
            return FinalNameVerifyRules.Verdict.OK
        }
        val dir = directoryOf(uri) ?: return FinalNameVerifyRules.Verdict.MISSING
        val names = dir.listFiles()?.map { it.name } ?: emptyList()
        return FinalNameVerifyRules.verify(fileName, stagingName, names)
    }

    private fun verifyFinalName(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        stagingName: String,
        fileName: String,
    ): Boolean = finalNameVerdict(resolver, uri, fileName, stagingName) ==
        FinalNameVerifyRules.Verdict.OK

    /**
     * 按**目录枚举**删掉与 [fileName] 指向同一份内容的旧文件。
     *
     * 为什么不再走 MediaStore 的"按名字删"：2026-09-15 真机取证显示，
     * 那条路在这台设备上**命中=0**（`删除同名旧文件: … 命中=0 实际删除=0`），
     * 于是旧文件永远留着、新文件被 MediaStore 自动改名成 `xxx (1).mp3` / `(2)` / `(3)` —— 越攒越多。
     * 而文件本来就在我们能直接枚举的目录里，所以**直接用 File 删**，
     * 删完再用 `MediaScannerConnection` 让媒体库跟上（不让磁盘与媒体库分叉）。
     *
     * 返回真正删掉的个数。
     */
    private fun deleteSiblingCopies(expectedName: String, dir: File): Int {
        val names = dir.listFiles()?.map { it.name } ?: return 0
        val targets = FinalNameVerifyRules.siblingCopies(expectedName, names)
        var deleted = 0
        targets.forEach { name ->
            if (File(dir, name).delete()) deleted++
        }
        if (deleted > 0) {
            Log.d(TAG, "按目录删除同名旧文件: $expectedName 删除=$deleted 个")
            // 让媒体库跟上（删完不通知的话，图库里会留一条指向不存在文件的记录）
            runCatching {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(File(dir, expectedName).absolutePath),
                    null,
                    null,
                )
            }
        }
        return deleted
    }

    /** 从某个 content uri 取出它对应的真实路径所在目录 */
    private fun directoryOf(uri: android.net.Uri): File? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(MediaStore.Downloads.DATA), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()?.let { File(it).parentFile }

    /** 读回某个 MediaStore 行当前的 DISPLAY_NAME（仅用于观测/兜底日志） */
    private fun storedDisplayName(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
    ): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    /** 删掉下载目录里与 [fileName] 同名的旧文件（调用前必须确认新文件已经写好） */
    private fun deleteExistingSameName(
        resolver: android.content.ContentResolver,
        fileName: String,
        relativePath: String,
    ) {
        // ⚠️ RELATIVE_PATH 要**两种写法都试**：调用方给的是 "Download/BiliDownloader"，
        // 而 MediaStore 存的是带结尾斜杠的 "Download/BiliDownloader/" ——
        // 只按一种等值查会永远匹配不到，这一步就等于没写（旧代码就是这样，
        // 真机表现为重下时旧文件不删、新文件被自动改名成 "xxx (1).mp4"）。
        val pathForms = DownloadRecordReuseRules.relativePathCandidates(relativePath)
        val selection =
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} IN (?,?)"
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            selection,
            arrayOf(fileName, pathForms[0], pathForms[1]),
            null,
        )?.use { cursor ->
            // 先把 id 全收出来再删：一边遍历游标一边删会让游标失效。
            // **全部**删掉而不是只删第一条 —— 旧版本重复下载会插出多条同名记录，
            // 只删一条会留下"同名但没记录"的残留文件。
            val ids = mutableListOf<Long>()
            while (cursor.moveToNext()) {
                ids += cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
            }
            var deleted = 0
            ids.forEach { id ->
                runCatching {
                    resolver.delete(
                        ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                        null,
                        null,
                    )
                }.onSuccess { deleted += it }
            }
            // ⚠️ 这条日志是必须的：删除**静默 no-op** 正是这次踩的坑
            //（查询条件匹配不上时它什么都不删、也不报错，看起来毫无异常）。
            Log.d(TAG, "删除同名旧文件: $fileName 命中=${ids.size} 实际删除=$deleted 路径=${pathForms.joinToString()}")
        }
    }

    private fun moveToDownloadLegacy(
        file: File,
        fileName: String,
        folderPath: String
    ): String? {
        val downloadsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        var targetDir = File(downloadsDir, "BiliDownloader")
        if (!targetDir.exists()) targetDir.mkdirs()

        if (folderPath.isNotEmpty()) {
            folderPath.split("/").forEach { part ->
                targetDir = File(targetDir, part)
                if (!targetDir.exists()) targetDir.mkdirs()
            }
        }

        val targetFile = File(targetDir, fileName)
        // 与 MediaStore 那条路径同一个道理：**先写临时文件、写成功了才动旧文件**。
        // 直接往 targetFile 写会在"打开流"的瞬间就把旧文件截断清空，中途失败就把它毁了。
        val stagingFile = File(targetDir, DownloadRecordReuseRules.stagingFileName(fileName))
        return try {
            file.inputStream().use { inputStream ->
                stagingFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            // 新文件已经完整写出来，这时删旧文件才是安全的
            if (DownloadRecordReuseRules.canDeleteExistingFile(newFileWritten = true) &&
                targetFile.exists()
            ) {
                targetFile.delete()
            }
            if (!stagingFile.renameTo(targetFile)) {
                Log.e(TAG, "改名失败，放弃本次移动（旧文件已保留）: $fileName")
                stagingFile.delete()
                return null
            }
            file.delete()
            targetFile.absolutePath
        } catch (e: Exception) {
            // 新文件没写成：删掉半截的临时文件，**旧文件原样保留**
            Log.e(TAG, "写入下载目录失败，已回滚（旧文件未动）: $fileName", e)
            stagingFile.delete()
            null
        }
    }

    enum class SubtitleType {
        ASS, SRT
    }

    companion object {
        private const val TAG = "ASFileOutput"
    }
}
