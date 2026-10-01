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
import java.io.FilterOutputStream
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
     * 下载图片到相册。
     *
     * 返回是否**真的**写成功了 —— 原先返回 Unit，insert 失败时静默 no-op，
     * 而调用方（`AnalysisViewModel.downloadImageToAlbum`）无条件弹"保存成功"（2026-09-15 复审 L15）。
     */
    suspend fun downloadImageToAlbum(
        imageBytes: ByteArray,
        fileName: String,
        saveDirName: String
    ): Boolean = withContext(Dispatchers.IO) {
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

    /**
     * 打开一个"下载目录下的新文件"输出流（弹幕 / 字幕）。
     *
     * ⚠️ `fileName` 里**可能带 `/`**：命名规则模板支持 `{author}/{p_title}` 这类写法（设置页明确宣传可用），
     * 这里必须像媒体那条路一样 split 出子目录，不能把整串当 `DISPLAY_NAME` ——
     * MediaStore 会拒绝含 `/` 的名字、legacy 直接 `FileNotFoundException`，
     * 而这两条都会经 `handlePredecessor` 变成**整集下载失败**（2026-09-15 复审 H8）。
     *
     * ⚠️ 返回的流在 `close()` 时会顺手删掉"同名的旧行/旧文件"（排除本次这一行）：
     * 弹幕/字幕以前只 insert、不删旧，重下同一集会在下载目录里攒出 `xxx (1).xml` / `(1).srt`
     * （2026-09-15 复审 L7）。清理放在**写完并关闭之后**，所以不会像旧媒体路径那样
     * "先删旧、再写新"地把用户已有的文件弄没。
     */
    private fun createDownloadOutputStream(
        fileName: String,
        mimeType: String,
        relativePath: String
    ): OutputStream {
        val parts = fileName.split('/').filter { it.isNotBlank() }
        val actualFileName = parts.lastOrNull()
            ?: throw IllegalArgumentException("文件名为空: '$fileName'")
        val subPath = parts.dropLast(1).joinToString("/")
        val fullRelative = listOf(relativePath, subPath)
            .filter { it.isNotBlank() }
            .joinToString("/")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, actualFileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/$fullRelative")
                }
            ) ?: throw Exception("MediaStore insert failed")
            val raw = context.contentResolver.openOutputStream(uri)
                ?: throw Exception("OutputStream == null")
            val excludeId = runCatching { ContentUris.parseId(uri) }.getOrDefault(-1L)
            // 目标目录：相对路径 = "Download/<fullRelative>"，直接算出来给"按行删同名旧记录"做校验
            val targetDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fullRelative,
            )
            object : FilterOutputStream(raw) {
                override fun close() {
                    super.close()
                    // 新文件已经完整写出来，这时删同名旧记录才是安全的（排除自己这一行）
                    runCatching {
                        deleteSameContentRows(
                            resolver = context.contentResolver,
                            fileName = actualFileName,
                            relativePath = "Download/$fullRelative",
                            targetDir = targetDir,
                            excludeId = excludeId,
                        )
                    }
                }
            }
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fullRelative
            ).apply { mkdirs() }
            FileOutputStream(File(dir, actualFileName))
        }
    }

    /** @return 是否真的写入成功（insert 返回 null / openOutputStream 返回 null 都算失败） */
    private fun saveImageMediaStore(
        imageBytes: ByteArray,
        fileName: String,
        saveDirName: String,
    ): Boolean {
        val resolver = context.contentResolver
        val relativeRoot = Environment.DIRECTORY_PICTURES
        val relativePath = "$relativeRoot/$saveDirName"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/${fileName.substringAfterLast('.')}")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        // ⚠️ 原写法是 `uri?.let { ... }`：insert 失败时**静默 no-op**，而调用方照样弹"保存成功"
        // （2026-09-15 复审 L15）。现在返回 false，让上层如实告诉用户。
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false

        var written = false
        try {
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                runCatching { resolver.delete(uri, null, null) }
                return false
            }
            out.use {
                it.write(imageBytes)
                it.flush()
                written = true
            }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        } finally {
            // 没写成功的那一行不能留成 0 字节图片
            if (!written) runCatching { resolver.delete(uri, null, null) }
            val update = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            runCatching { resolver.update(uri, update, null, null) }
        }
        return written
    }

    /** @return 是否真的写入成功 */
    private fun saveImageLegacy(
        imageBytes: ByteArray,
        fileName: String,
        saveDirName: String,
    ): Boolean {
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
        return true
    }

    private fun moveToDownloadMediaStore(
        file: File,
        fileName: String,
        relativePath: String,
        mimeType: String
    ): String? {
        val resolver = context.contentResolver

        // ⚠️ 顺序（**2026-10-01 真机复现后第三次改写，这次是"挪开-写入-删除"三段式**）：
        //
        // 之前试过、并且被真机打回来的两种顺序：
        //  A. 先删旧文件再写新的 → 中途失败会让用户原有文件没了、新的也没有；
        //  B. 先写新文件再删旧的 → 这台 ROM 上 MediaStore 会把新文件改名成 `xxx (1)/(2).mp4`
        //     （因为正式名被旧文件占着），而"删旧文件"两条路都不生效，副本越攒越多；
        //     更糟的是**回读到/`_data` 报的名字与磁盘真实名字不一致**，
        //     一旦用它当 keepName 去目录直删，就会把刚交付的成品删掉（真机复现过）。
        //
        // 现在：**交付前把同名旧文件整行挪到一边（改成一个带时间戳的临时名，可回滚）**，
        // 于是：
        //  · 正式名在写入前就是空闲的 → 不会再有 `(N)` 后缀、也不会撞名；
        //  · 旧文件此时并没有被删除 → 写入失败就把名字改回去，用户的东西原样还在；
        //  · 我们自己的新文件在"挪开"这一步**还不存在** → 物理上不可能误删自己。
        val targetDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            relativePath,
        )
        val asides = moveAsideSameContentFiles(resolver, fileName, targetDir)

        val stagingName = DownloadRecordReuseRules.stagingFileName(fileName)
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, stagingName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            }
        ) ?: run {
            restoreAsideFiles(resolver, asides)
            return null
        }

        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                file.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: error("openOutputStream 返回 null")
        } catch (e: Exception) {
            // 新文件没写成：把这一行删掉，并**把挪开的旧文件改回原名**（用户的东西不能丢）
            runCatching { resolver.delete(uri, null, null) }
            restoreAsideFiles(resolver, asides)
            Log.e(TAG, "写入下载目录失败，已回滚（旧文件已改回原名）: $fileName", e)
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
        // ⚠️ 2026-09-15 复审又逮到第二个洞：删"同名旧文件"时**必须排除本次刚交付的那一份**。
        // 新版是"先改名再删"，此刻磁盘上的正式名就是刚写好的成品：
        //  · 改名生效时 → `deleteSiblingCopies` 把它自己删掉；而判定只看媒体库回读（名字是对的）
        //    → 报成功、磁盘上却没有文件；
        //  · 按名字查媒体库那条更狠：刚插入的那一行名字也已经改成了正式名 → **连行带文件一起删**。
        // 所以两条删除路径都要把"自己"排除（ownName / ownRowId）。
        renameStaging(resolver, uri, fileName, stagingName)
        val ownRowId = runCatching { ContentUris.parseId(uri) }.getOrDefault(-1L)
        // 本次成品在磁盘上的名字。**回读不到时按"改名已生效"处理**（即把正式名排除掉）：
        // 那最多退化成"旧同名文件没删掉"这种无害 no-op（与旧代码同等），
        // 总比反过来把刚写好的成品删掉强。
        val ownDir = directoryOf(uri)

        // ⚠️⚠️ 2026-10-01 真机复现的教训：**MediaStore 这条路上一律不要做"目录枚举直删"**。
        // 原因：`ownName`（keepName）来自媒体库回读，而改名失败时它还是 `xxx.mp4.part`，
        // 与磁盘上的真实名字对不上；一旦那个文件恰好是 app 自己刚写出来、**属主是 app** 的
        // （新版这次就是），`File.delete()` 会真的成功 —— 于是**刚交付的成品被自己删掉**，
        // 磁盘上只剩那份老的孤儿文件。旧版之所以"看起来无害"，只是因为它删不动
        // MediaProvider 拥有的文件（EACCES）而已，纯属侥幸。
        // 结论：删除只走"按行删"（按 _ID 排除自己，MediaProvider 负责删对文件）。
        deleteSameContentRows(
            resolver = resolver,
            fileName = fileName,
            relativePath = relativePath,
            targetDir = ownDir,
            excludeId = ownRowId,
        )

        // ② 冲突清掉之后**再改一次名**：MediaStore 之前因为同名冲突把新文件叫成了 `xxx (2).mp4`，
        //    现在旧行没了，这次改名就能落到实处（真机实测 `resolver.update(DISPLAY_NAME)`
        //    是生效的 —— 它先给出了 (1)、(2)，说明"改不动"的唯一原因就是冲突）。
        if (!FinalNameVerifyRules.displayNameMatches(fileName, storedDisplayName(resolver, uri))) {
            renameStaging(resolver, uri, fileName, storedDisplayName(resolver, uri) ?: stagingName)
        }

        // ③ 新文件已经就位、名字也改好了 → 现在才**真正删掉**那些挪开的旧文件
        //    （删行 = MediaProvider 连带删文件；此时我们自己的文件已经是正式名，不会被波及）
        deleteAsideFiles(resolver, asides)

        if (!verifyFinalName(resolver, uri, stagingName, fileName)) {
            // 重试一轮：再删一次同名行 + 再清一次孤儿 + 再改一次名
            Log.w(TAG, "第一次交付后名字不符，重试删除同名旧记录: 期望=$fileName")
            deleteSameContentRows(
                resolver = resolver,
                fileName = fileName,
                relativePath = relativePath,
                targetDir = ownDir,
                excludeId = ownRowId,
            )
            renameStaging(resolver, uri, fileName, storedDisplayName(resolver, uri) ?: stagingName)
        }

        val verdict = finalNameVerdict(resolver, uri, fileName, stagingName)
        if (verdict != FinalNameVerifyRules.Verdict.OK) {
            // ⚠️ 这里**不再删行删文件**（2026-09-15 复审）。原实现在"名字对不上"时
            // `resolver.delete(uri)` + return null，等于把刚下好的成品连记录一起丢掉；
            // 而这台设备上 MediaStore 改名本来就不生效 → 每次下载都以"失败 + 丢文件"收场。
            // 名字不完美可以忍，文件没了不能忍 —— 交给 ensureStoredName 尽力修正，修不好也保留。
            Log.w(
                TAG,
                "交付名不是期望值（$verdict）：期望=$fileName 回读=${storedDisplayName(resolver, uri)}" +
                    " —— 保留文件，交给 ensureStoredName 尽力修正",
            )
        }

        // 磁盘上已经是正式名了，但**媒体库那一行**可能还记着暂存名
        // （真机取证：改名返回 0 行、回读仍是 `xxx.mp3.part.mp3`）。
        // 用户在图库/文件里看到的就是这个名字，所以尽力修好；**修不好也不删文件**。
        val fixedUri = ensureStoredName(resolver, uri, fileName, directoryOf(uri))
        file.delete()
        return fixedUri.toString()
    }

    /**
     * 保证媒体库里那一行的 DISPLAY_NAME 与磁盘上的正式名一致。
     *
     * ⚠️ **无论修不修得成，都返回一个可用的 uri、绝不删文件**（2026-09-15 复审）：
     * 原实现在"改名仍不生效"时会 `resolver.delete(uri)` + 重扫，扫不出来就返回一个**已删除的 uri**
     * —— 文件与记录一起没了，用户看到"已完成"却打不开。名字不好看可以忍，文件没了不能忍。
     *
     * 返回可用的 uri（修不好时就是原 uri —— 文件本身是好的，不能让整次下载白费）。
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
        // 2) 改名这条路在这台设备上不可靠 → **保留文件与记录**，只把差异记进日志。
        //    不再"删行 + 重扫"：scanFile 是异步的，紧接着查往往查不到，旧实现正好在这里
        //    返回一个已删除的 uri。
        Log.w(
            TAG,
            "MediaStore 改名仍不生效，保留文件与记录: 期望=$fileName " +
                "回读=${storedDisplayName(resolver, uri)} 目录=$dir",
        )
        return uri
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

    /** 交付结果判定：**以目录里实际存在的名字为准**，目录拿不到时才退回媒体库回读 */
    private fun finalNameVerdict(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        fileName: String,
        stagingName: String,
    ): FinalNameVerifyRules.Verdict {
        // ⚠️ 必须先看磁盘（2026-09-15 复审）。原来只看媒体库回读到的 DISPLAY_NAME，
        // 于是"文件已经被删掉、行里的名字却还是对的"会被判成 OK → 报成功但文件不存在。
        val names = directoryOf(uri)?.listFiles()?.map { it.name }
        if (names != null) {
            return FinalNameVerifyRules.verify(fileName, stagingName, names)
        }
        // 拿不到目录（这台设备可能限制 DATA 查询）时才退回媒体库回读：至少要求名字对
        return if (FinalNameVerifyRules.displayNameMatches(
                fileName,
                storedDisplayName(resolver, uri),
            )
        ) {
            FinalNameVerifyRules.Verdict.OK
        } else {
            FinalNameVerifyRules.Verdict.MISSING
        }
    }

    private fun verifyFinalName(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        stagingName: String,
        fileName: String,
    ): Boolean = finalNameVerdict(resolver, uri, fileName, stagingName) ==
        FinalNameVerifyRules.Verdict.OK

    /**
     * ⚠️ **已停用**（2026-10-01 真机复现后从 MediaStore 交付路径摘除）：
     * 它在"改名失败、行里的名字还是 `.part`"时会把 [keepName] 认成正式名，
     * 而磁盘上真正的成品此刻可能叫别的名字 —— 一旦那个文件恰好属主是 app（新版就会这样），
     * `File.delete()` 会成功，**刚交付的成品被自己删掉**。真机日志：
     * `按目录删除同名旧文件: … 删除=1 个` 紧接着 `交付名不是期望值（MISSING）：回读=null`。
     * 现在删除只走"按 MediaStore 行删"（[deleteSameContentRows]，按 `_ID` 排除自己）。
     * 代码保留是为了 Android 9 及以前 / 已授予全盘权限的机型将来还可能用到，
     * 但**不要再从 MediaStore 路径调它**。
     *
     * 按**目录枚举**删掉与 [fileName] 指向同一份内容的旧文件。
     *
     * 返回真正删掉的个数。
     *
     * @param keepName 本次**刚交付**的那一份的名字，必须排除 —— 否则会把刚写好的成品删掉
     *   （2026-09-15 复审：新版先改名再删，磁盘上的正式名就是本次的成品）。
     */
    @Suppress("unused")
    private fun deleteSiblingCopies(expectedName: String, dir: File, keepName: String?): Int {
        val names = dir.listFiles()?.map { it.name } ?: return 0
        val targets = FinalNameVerifyRules.siblingCopies(expectedName, names, keepName = keepName)
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

    /** 「挪到一边」的旧文件：记住它的行 uri 与原名，供成功后删除 / 失败后回滚 */
    private data class AsideFile(
        val rowUri: android.net.Uri,
        val originalName: String,
    )

    /**
     * 交付**之前**把目录里与 [fileName] 同内容的旧文件整行挪到一边。
     *
     * ## 为什么是"挪开"而不是"删掉"（2026-10-01 真机复现后第三次改写）
     * 这台 ROM 的 MediaStore 实测行为：
     *  · 往 `Download/` 里 `insert` 一个**已存在**的名字，会被自动改成 `xxx (1).mp4`、`(2)`…；
     *  · 事后再 `update(DISPLAY_NAME)` 想改回正式名，也是继续往 `(N)` 上加；
     *  · `_data` / 回读出来的名字与实际磁盘名**可能不一致**（真机上出现过 `_data` 指向
     *    另一个文件、`DISPLAY_NAME` 回读为 null）。
     * 于是"先写新的、再删旧的"必然留下 `(N)` 副本；"先删旧的、再写新的"又会在写入失败时
     * 把用户原有文件弄丢。所以走中间路线：**先把旧行改成一个带时间戳的临时名**
     * （MediaStore 改名是生效的，磁盘上那个文件跟着改名），正式名就空出来了：
     *  · 写入前正式名空闲 → 新文件不会再拿到 `(N)`；
     *  · 旧文件只是改名、没有删除 → 写入失败可以 [restoreAsideFiles] 改回来；
     *  · 这一步发生在"我们自己的新文件还不存在"时 → **物理上不可能误删自己的成品**。
     *
     * ⚠️ 孤儿文件（磁盘有、媒体库没行）同样适用：先 [scanFileBlocking] 让媒体库收录拿到 uri
     * （已收录的会返回原来那一行），再改名。
     */
    private fun moveAsideSameContentFiles(
        resolver: android.content.ContentResolver,
        fileName: String,
        dir: File,
    ): List<AsideFile> {
        val victims = runCatching {
            dir.listFiles()?.filter { f ->
                f.isFile && FinalNameVerifyRules.isSameContentName(f.name, fileName)
            }
        }.getOrNull().orEmpty()
        if (victims.isEmpty()) return emptyList()

        val result = mutableListOf<AsideFile>()
        val stamp = System.currentTimeMillis()
        victims.forEach { victim ->
            val uri = scanFileBlocking(victim) ?: return@forEach
            val dataPath = contentDataPath(resolver, uri)
            if (dataPath != null && dataPath != victim.absolutePath) {
                // 扫描回来的行指向别的文件：绝不动它（宁可留着副本，也不删错东西）
                Log.w(TAG, "扫描回来的行不是目标文件，跳过挪开: ${victim.name} → $dataPath")
                return@forEach
            }
            val asideName = "$fileName.old-$stamp"
            val rows = runCatching {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, asideName) },
                    null,
                    null,
                )
            }.getOrDefault(0)
            if (rows > 0) {
                result += AsideFile(uri, victim.name)
            } else {
                Log.w(TAG, "挪开同名旧文件失败（改名 0 行），保持原样: ${victim.name}")
            }
        }
        Log.d(TAG, "交付前挪开同名旧文件: $fileName 目标=${victims.size} 成功=${result.size}")
        return result
    }

    /** 交付成功后：删掉那些挪开的旧文件（删行 = MediaProvider 连带删文件） */
    private fun deleteAsideFiles(
        resolver: android.content.ContentResolver,
        asides: List<AsideFile>,
    ) {
        if (asides.isEmpty()) return
        var deleted = 0
        asides.forEach { aside ->
            runCatching { resolver.delete(aside.rowUri, null, null) }
                .onSuccess { deleted += it }
        }
        Log.d(TAG, "交付成功，清理挪开的旧文件: 目标=${asides.size} 实际删除=$deleted")
    }

    /** 交付失败时：把挪开的旧文件改回原名（用户原有的东西必须原样还在） */
    private fun restoreAsideFiles(
        resolver: android.content.ContentResolver,
        asides: List<AsideFile>,
    ) {
        if (asides.isEmpty()) return
        var restored = 0
        asides.forEach { aside ->
            runCatching {
                resolver.update(
                    aside.rowUri,
                    ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, aside.originalName) },
                    null,
                    null,
                )
            }.onSuccess { restored += it }
        }
        Log.w(TAG, "已把挪开的旧文件改回原名: 目标=${asides.size} 成功=$restored")
    }

    /** 某个 MediaStore 行当前指向的磁盘路径 */
    private fun contentDataPath(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
    ): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.Downloads.DATA), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** 让媒体库收录一个文件并等它返回 uri（媒体扫描是异步的，最多等 3 秒） */
    private fun scanFileBlocking(file: File): android.net.Uri? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var scanned: android.net.Uri? = null
        runCatching {
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                null,
            ) { _, uri ->
                scanned = uri
                latch.countDown()
            }
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return scanned
    }

    /** 读回某个 MediaStore 行当前的 DISPLAY_NAME（仅用于观测/兜底日志） */
    private fun storedDisplayName(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
    ): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    /**
     * 删掉**同一个目录下**与 [fileName] 同内容的旧记录（连带旧文件）。
     *
     * ## 为什么改成"按名字查 + Kotlin 侧校验目录"（2026-10-01 真机复现后重写）
     * 之前是 `DISPLAY_NAME=? AND RELATIVE_PATH IN (?,?) AND _ID!=?` 的**等值**查询，真机上
     * 重下同一集时它**命中=0**（旧文件不删 → MediaStore 只能把新文件改成 `xxx (2).mp4`，
     * 一个视频攒成两份 72MB）。同时另一条"目录枚举直删"的路也失败了，根因这次查清了：
     *
     * ```
     * ls -l → 属主 10271 = com.android.providers.media.module，而 app 的 appId=10851
     * ```
     * scoped storage 下 app 对 MediaProvider 拥有的文件 `File.delete()/renameTo()` 必然 EACCES，
     * 所以**唯一的删除途径是删 MediaStore 行**（MediaProvider 会连带删文件）。
     * 既然要删行，就必须把"查得到旧行"这件事做对，于是：
     *
     * 1. **去掉 RELATIVE_PATH 等值条件**（它在真机上匹配不到，正是"静默 no-op"的来源），
     *    只按 `DISPLAY_NAME` 查（正式名 + 用转义过的 LIKE 查 `xxx (N).ext` 副本名）；
     * 2. **目录一致性在 Kotlin 里校验**：拿行的 `_data` 父目录与目标目录比对（回退用
     *    RELATIVE_PATH 的两种写法），**同名但不同目录的文件绝不删**；
     * 3. 排除本次自己那一行（[excludeId]）。
     *
     * @return 实际删掉的行数（0 也要打日志 —— 这个数字是"删除有没有真的生效"的唯一证据）
     */
    private fun deleteSameContentRows(
        resolver: android.content.ContentResolver,
        fileName: String,
        relativePath: String,
        targetDir: File?,
        excludeId: Long,
    ): Int {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.RELATIVE_PATH,
            MediaStore.Downloads.DATA,
        )
        // ⚠️ LIKE 必须带 ESCAPE：文件名里的 `_` 在 LIKE 里是通配符（见 duplicateNameLikePattern 注释）
        val selection =
            "${MediaStore.Downloads.DISPLAY_NAME}=? OR " +
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
        val args = arrayOf(fileName, FinalNameVerifyRules.duplicateNameLikePattern(fileName))
        val pathForms = DownloadRecordReuseRules.relativePathCandidates(relativePath)

        val ids = mutableListOf<Long>()
        runCatching {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val relIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
                val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATA)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    if (id == excludeId) continue
                    val name = cursor.getString(nameIdx) ?: continue
                    if (!FinalNameVerifyRules.isSameContentName(name, fileName)) continue
                    // 目录校验：先看 _data 的父目录（最可靠），取不到再退回 RELATIVE_PATH
                    val dataPath = cursor.getString(dataIdx)
                    val relPath = cursor.getString(relIdx)
                    val sameDir = when {
                        dataPath != null && targetDir != null ->
                            File(dataPath).parentFile?.absolutePath == targetDir.absolutePath

                        relPath != null ->
                            DownloadRecordReuseRules.relativePathCandidates(relPath)
                                .any { it in pathForms }

                        else -> false
                    }
                    if (!sameDir) continue
                    ids += id
                }
            }
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
        Log.d(
            TAG,
            "删除同名旧记录: $fileName 命中=${ids.size} 实际删除=$deleted " +
                "路径=${pathForms.joinToString()} 目录=${targetDir?.absolutePath}",
        )
        return deleted
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
            folderPath.split("/").filter { it.isNotBlank() }.forEach { part ->
                targetDir = File(targetDir, part)
                if (!targetDir.exists()) targetDir.mkdirs()
            }
        }

        val targetFile = File(targetDir, fileName)
        // 与 MediaStore 那条路径同一个道理：**先写临时文件、写成功了才动旧文件**。
        // 直接往 targetFile 写会在"打开流"的瞬间就把旧文件截断清空，中途失败就把它毁了。
        val stagingFile = File(targetDir, DownloadRecordReuseRules.stagingFileName(fileName))
        // ⚠️ 旧成品的让位顺序（2026-09-15 复审 H10）：**先把旧成品改名成 `.bak`，不是先删**。
        // 原实现在这里直接 `targetFile.delete()`，紧接着 `stagingFile.renameTo(targetFile)` ——
        // 一旦改名失败就是"旧文件没了、新的也没了"，而日志还写着"旧文件已保留"（与事实相反）。
        // 现在：写完新文件 → 旧成品改名为 `.bak` → 新文件改名成正式名 → 删 `.bak`；
        // 任一步失败都把 `.bak` 改回来，用户原有的文件永远不会凭空消失。
        val backupFile = File(targetDir, "$fileName.bak")
        return try {
            file.inputStream().use { inputStream ->
                stagingFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            val hadOld = targetFile.exists()
            if (hadOld) {
                backupFile.delete()
                if (!targetFile.renameTo(backupFile)) {
                    // 旧成品动不了（被占用/无权限）→ 放弃本次移动，现场原样保留
                    Log.e(TAG, "旧成品无法让位，放弃本次移动（旧文件未动）: $fileName")
                    stagingFile.delete()
                    return null
                }
            }
            if (!stagingFile.renameTo(targetFile)) {
                Log.e(TAG, "改名失败，回滚（旧文件已恢复）: $fileName")
                stagingFile.delete()
                if (hadOld && !backupFile.renameTo(targetFile)) {
                    Log.e(TAG, "旧文件回滚也失败了，仍是 .bak: ${backupFile.absolutePath}")
                }
                return null
            }
            if (hadOld) backupFile.delete()
            file.delete()
            targetFile.absolutePath
        } catch (e: Exception) {
            // 新文件没写成：删掉半截的临时文件，**旧文件原样保留/恢复**
            Log.e(TAG, "写入下载目录失败，已回滚（旧文件未动）: $fileName", e)
            stagingFile.delete()
            if (backupFile.exists() && !targetFile.exists()) {
                backupFile.renameTo(targetFile)
            }
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
