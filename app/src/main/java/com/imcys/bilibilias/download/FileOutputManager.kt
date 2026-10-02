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
import com.imcys.bilibilias.data.download.output.DuplicateDownloadRules
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
private const val MAX_TRACE_BYTES = 256L * 1024

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
        return createDownloadOutputStream(fileName, mime, DownloadDir.NAME)
    }

    /**
     * 创建弹幕输出流
     */
    suspend fun createDanmakuOutputStream(fileName: String): OutputStream =
        withContext(Dispatchers.IO) {
            createDownloadOutputStream(fileName, "application/xml", "${DownloadDir.NAME}/Danmaku")
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
            "${Environment.DIRECTORY_DOWNLOADS}/${DownloadDir.NAME}/$folderPath"
        else
            "${Environment.DIRECTORY_DOWNLOADS}/${DownloadDir.NAME}"

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
        // ⚠️ 目录要按 RELATIVE_PATH 的写法算（2026-10-01 真机复现）：
        // 这里的 `relativePath` 是 **"Download/BDT"**（MediaStore 的相对路径，
        // 自带 `Download/` 前缀），若再拼一次 Downloads 根目录就会变成
        // `.../Download/Download/BiliDownloader` —— 目录不存在 → `listFiles()` 为 null →
        // "挪开旧文件"整步**静默 no-op**（真机日志里连一条都没有，又踩了一次"静默失效"）。
        val targetDir = resolveDownloadDir(relativePath)
        // 目录里的名字用 MediaStore 枚举（app 自己列目录会被 scoped storage 拒掉）
        val dirNames = queryDownloadDirNames(relativePath)
        val asides = moveAsideSameContentFiles(resolver, fileName, targetDir, dirNames)

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

    /**
     * 「清理重复文件」的结果。
     *
     * [needsUserConsent] 是**不属于本 app** 的那些行（别的 App 写进来的、或上一版安装留下的副本）——
     * Android 11+ 规定删这些行必须走 `MediaStore.createDeleteRequest` 让系统弹确认框，
     * 直接 `resolver.delete` 会被拒（真机表现：`实际删除=0`、目录纹丝不动）。
     * 调用方拿到这些 uri 后必须去启动那个系统确认框，否则清理就变成"静默 no-op"。
     */
    data class CleanupResult(
        val deleted: Int,
        val needsUserConsent: List<android.net.Uri>,
    )


    /**
     * 「挪到一边」的旧文件：记住它的原名（回滚要用），以及"怎么删/怎么还原"。
     *
     * 两种模式：
     *  · MediaStore 模式（默认）：[rowUri] 那一行被改了名，删行/改名都靠 MediaProvider；
     *  · 直删模式（用户授了"所有文件访问"）：文件被 [File.renameTo] 挪成了 [asidePath]，
     *    媒体库那几行只是被顺手删掉（文件已经不在原路径，删行不会碰磁盘）。
     */
    private data class AsideFile(
        val originalName: String,
        val rowUri: android.net.Uri? = null,
        val asidePath: String? = null,
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
        dirNames: List<String>,
    ): List<AsideFile> {
        // ⚠️ **只看名字，绝不 stat**（2026-10-01 真机复现）：
        // scoped storage 下 app 对 MediaProvider 拥有的文件 `stat()` 会被拒 ——
        // `File.isFile` 返回 false（而**目录列表本身是可用的**，所以 `verify()` 能看到名字）。
        // 上一版就是因为在过滤条件里加了 `f.isFile`，把所有目标都滤掉了：
        // 真机日志 `目标=0（目录=…/Download/BiliDownloader 存在=true）`，而目录里明明躺着 3 份同名文件。
        val matched = dirNames.filter { FinalNameVerifyRules.isSameContentName(it, fileName) }
        // 枚举拿不到时至少试一下正式名：scanFile 走 MediaProvider，不依赖 app 的列目录/stat 权限
        val victims = matched.ifEmpty { if (dirNames.isEmpty()) listOf(fileName) else emptyList() }
        if (victims.isEmpty()) {
            // ⚠️ 空也要打日志：这一步"静默不生效"正是 2026-10-01 那次真机复现的形态
            Log.d(
                TAG,
                "交付前挪开同名旧文件: $fileName 枚举=${dirNames.size} 目标=0（目录=${dir.absolutePath}）",
            )
            return emptyList()
        }

        val result = mutableListOf<AsideFile>()
        val stamp = System.currentTimeMillis()
        val direct = hasAllFilesAccess()
        victims.forEach { victimName ->
            val victim = File(dir, victimName)
            if (direct) {
                // ⚠️ 有"所有文件访问"时直接改名（真机上这是唯一 100% 可靠的方式）：
                // MediaStore 的 rename 会往 (N) 上加、`_data` 还可能对不上；
                // 而这些文件属主是 MediaProvider，没有这个权限就删不动/改不动。
                val aside = File(dir, "$victimName.old-$stamp")
                if (runCatching { victim.renameTo(aside) }.getOrDefault(false)) {
                    // 文件已经不在了，但媒体库那几行还在 —— 留着会让下次 insert 继续拿到 (N)，删掉它们
                    deleteRowsByDisplayName(resolver, victimName)
                    result += AsideFile(
                        originalName = victimName,
                        asidePath = aside.absolutePath,
                    )
                    return@forEach
                }
                Log.w(TAG, "直删模式下改名失败，退回 MediaStore 方式: $victimName")
            }
            val uri = scanFileBlocking(victim) ?: return@forEach
            val dataPath = contentDataPath(resolver, uri)
            if (dataPath != null && dataPath != victim.absolutePath) {
                // 扫描回来的行指向别的文件：绝不动它（宁可留着副本，也不删错东西）
                Log.w(TAG, "扫描回来的行不是目标文件，跳过挪开: $victimName → $dataPath")
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
                result += AsideFile(originalName = victimName, rowUri = uri)
            } else {
                Log.w(TAG, "挪开同名旧文件失败（改名 0 行），保持原样: $victimName")
            }
        }
        trace(
            "交付前挪开同名旧文件: $fileName 枚举=${dirNames.size} 目标=${victims.size} " +
                "成功=${result.size} 目录=${dir.absolutePath}",
        )
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
            val asidePath = aside.asidePath
            if (asidePath != null) {
                // 直删模式：直接删那个临时文件
                if (runCatching { File(asidePath).delete() }.getOrDefault(false)) deleted++
            } else {
                val rowUri = aside.rowUri ?: return@forEach
                runCatching { resolver.delete(rowUri, null, null) }.onSuccess { deleted += it }
            }
        }
        trace("交付成功，清理挪开的旧文件: 目标=${asides.size} 实际删除=$deleted")
    }

    /** 交付失败时：把挪开的旧文件改回原名（用户原有的东西必须原样还在） */
    private fun restoreAsideFiles(
        resolver: android.content.ContentResolver,
        asides: List<AsideFile>,
    ) {
        if (asides.isEmpty()) return
        var restored = 0
        asides.forEach { aside ->
            val asidePath = aside.asidePath
            if (asidePath != null) {
                // ⚠️ 别把局部变量也命名成 aside（会遮蔽外层参数，`aside.originalName` 直接编译不过）
                val moved = File(asidePath)
                val back = File(moved.parentFile, aside.originalName)
                if (runCatching { moved.renameTo(back) }.getOrDefault(false)) restored++
            } else {
                val rowUri = aside.rowUri ?: return@forEach
                runCatching {
                    resolver.update(
                        rowUri,
                        ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, aside.originalName)
                        },
                        null,
                        null,
                    )
                }.onSuccess { restored += it }
            }
        }
        trace("已把挪开的旧文件改回原名: 目标=${asides.size} 成功=$restored")
    }

    /**
     * 把 MediaStore 的 `RELATIVE_PATH`（如 `Download/BDT`）解析成磁盘目录。
     *
     * ⚠️ 这里必须**去掉开头的 `Download/`**：公共下载目录本身就是 Downloads 根，
     * 再拼一次会得到 `Download/Download/...`（2026-10-01 真机复现：这直接让"挪开旧文件"
     * 变成静默 no-op）。两种写法都容错（有的调用方传的是不含前缀的相对路径）。
     */
    private fun resolveDownloadDir(relativePath: String): File {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val cleaned = DownloadRecordReuseRules.downloadDirRelativePath(relativePath)
        return if (cleaned.isBlank()) root else File(root, cleaned)
    }

    /**
     * 用 **MediaStore** 枚举某个下载目录下的文件名。
     *
     * ⚠️⚠️ **绝不能用 `File(dir).listFiles()`**（2026-10-01 真机复现）：
     * 这台 ROM 上 app 对下载目录（`Download/BDT`）的**目录列举被 scoped storage 拒掉**，
     * `listFiles()` 返回 null —— 于是"重复文件检测"和"交付前挪开旧文件"**一起变成静默 no-op**
     * （真机表现：明明有两份同名文件，下载管理页却连"发现重复文件"的卡片都不出现）。
     * 而 MediaStore 本来就是这些文件的主人，问它最靠谱：先按 `RELATIVE_PATH` 前缀查，
     * 拿不到再退回 `_data` 前缀；两个都拿不到才认输并打日志（不再伪装成"没有重复"）。
     */
    private fun queryDownloadDirNames(relativePath: String): List<String> {
        val rel = relativePath.trim('/')
        val fromRelative = queryDownloadNames(
            selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?",
            args = arrayOf("$rel/%"),
        )
        if (fromRelative.isNotEmpty()) {
            trace("枚举下载目录: $rel 命中=${fromRelative.size}（按 RELATIVE_PATH）")
            return fromRelative
        }
        val fromData = queryDownloadNames(
            selection = "${MediaStore.Files.FileColumns.DATA} LIKE ?",
            args = arrayOf("%/$rel/%"),
        )
        trace("枚举下载目录: $rel rel命中=0 data命中=${fromData.size}")
        return fromData
    }

    private fun queryDownloadNames(selection: String, args: Array<String>): List<String> {
        val names = mutableListOf<String>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME),
                selection,
                args,
                null,
            )?.use { cursor ->
                val idx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { names += it }
                }
            }
        }
        return names
    }

    /**
     * 诊断轨迹：既打 logcat，**也落一份文件**。
     *
     * ⚠️ 为什么必须落文件（2026-10-01 真机教训）：这台 ROM（MIUI/Android 16）会**过滤掉
     * app 自己的日志**——同一个进程、同一段代码，有时能看到 `ASFileOutput`，重装后就一条都没有；
     * 于是"删除/挪开旧文件有没有真的生效"这类问题**完全没法取证**，只能靠猜和反复装包。
     * 轨迹文件写在 `Android/data/<pkg>/files/logs/download-trace.log`，adb 侧可读、也不受过滤影响。
     */
    private fun trace(message: String) {
        Log.d(TAG, message)
        runCatching {
            val file = traceFile()
            if (file.length() > MAX_TRACE_BYTES) file.delete()
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT)
                .format(java.util.Date())
            file.appendText("$stamp $message\n")
        }
    }

    /** 轨迹文件（顺带建目录） */
    private fun traceFile(): File =
        File(File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }, "download-trace.log")

    /**
     * 让下载链路的**其它环节**（下载/合并失败…）也写进同一条时间线。
     *
     * 为什么需要：这台 ROM 会过滤 app 自己的 logcat，"失败原因"以前只出现在 logcat 里 =
     * 用户报障时基本拿不到。现在失败原因和交付/清理记在同一个文件里，导出一份就能复盘。
     */
    fun logDiagnostic(tag: String, message: String) {
        trace("[$tag] $message")
    }

    /**
     * 把一段文本导出到下载目录（设置备份、诊断日志都用它），返回导出后的文件名。
     *
     * Android 10+ 没有「所有文件访问」时不能直接用 File 写公共下载目录，
     * 所以同样走 `MediaStore.Downloads` 插入（本 app 自己拥有的行）。
     */
    fun exportTextToDownload(displayName: String, content: String, mimeType: String = "application/json"): String? =
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val dir = DownloadDir.dir().apply { mkdirs() }
                File(dir, displayName).writeText(content)
                trace("导出文本(legacy): $displayName")
                return@runCatching displayName
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, DownloadDir.RELATIVE_PATH)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            resolver.openOutputStream(uri)?.use { out -> out.write(content.toByteArray()) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            trace("导出文本: $displayName")
            displayName
        }.getOrNull()

    /** 读轨迹日志（默认取末尾 500 行），用于应用内「诊断日志」 */
    fun readTraceText(maxLines: Int = 500): String = runCatching {
        val file = traceFile()
        if (!file.exists()) "" else file.readLines().takeLast(maxLines).joinToString("\n")
    }.getOrElse { "" }

    /** 清空轨迹日志 */
    fun clearTrace() {
        runCatching { traceFile().delete() }
    }

    /** 下载目录里的一个文件（给「下载目录文件」用） */
    data class DownloadFileEntry(
        val displayName: String,
        val uriString: String,
        val sizeBytes: Long,
        val addedMs: Long,
        val relativePath: String,
    )

    /**
     * 列出下载目录（含子目录）里的文件。
     *
     * ⚠️ 走 MediaStore 而不是 `File.listFiles()`：这台 ROM 上 app 对媒体库拥有的文件
     * `listFiles()` 会返回 null（2026-10-01 真机复现）。
     */
    fun listDownloadFiles(relativePath: String = DownloadDir.RELATIVE_PATH): List<DownloadFileEntry> {
        val result = mutableListOf<DownloadFileEntry>()
        runCatching {
            val like = DownloadRecordReuseRules.downloadDirRelativePath(relativePath) + "%"
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE,
                    MediaStore.Downloads.DATE_ADDED,
                    MediaStore.Downloads.RELATIVE_PATH,
                ),
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? OR ${MediaStore.Downloads.DATA} LIKE ?",
                arrayOf(like, "%/$like%"),
                "${MediaStore.Downloads.DATE_ADDED} DESC",
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val dateIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
                val pathIdx = c.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    result += DownloadFileEntry(
                        displayName = c.getString(nameIdx) ?: continue,
                        uriString = android.content.ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                        ).toString(),
                        sizeBytes = c.getLong(sizeIdx),
                        addedMs = c.getLong(dateIdx) * 1000L,
                        relativePath = c.getString(pathIdx) ?: "",
                    )
                }
            }
        }
        return result
    }

    /**
     * 某个下载目录里**是否存在这个显示名对应的媒体库行**。
     *
     * 用途（2026-10-02 真机）：`savePath` 可能是 `file://` 形式，而这台 ROM 上 app
     * 打开媒体库拥有的文件会拿到 **EACCES**，而它和"文件真的没了"一样都抛
     * `FileNotFoundException` —— 光看异常分不出来。所以这种情况下再用"按名字问媒体库"
     * 确认一次，避免把好好的记录误标成「文件已丢失」。
     */
    fun downloadDirContains(relativePath: String, displayName: String): Boolean =
        runCatching { queryDownloadDirNames(relativePath) }
            .getOrElse { emptyList() }
            .any { it == displayName }

    /**
     * 把**已下载的文件**移到下载目录下的某个子目录（B2 批量移动）。
     *
     * 走 MediaStore 的 `RELATIVE_PATH` 更新（MediaProvider 支持的"移动"语义）：
     * - 行的 `_ID` 不变 → **记录里的 `savePath`（content URI）依然有效**，
     *   所以移动之后"已完成下载"里点开仍然能找到文件；
     * - 不加 `(N)` 后缀问题：这里改的是**路径**不是显示名，不触发那台 ROM 的撞名改名逻辑。
     *
     * ⚠️ 返回 false 的常见原因：这条行不是本应用拥有的（别人的副本）——
     * 那就需要系统确认或「所有文件访问」，界面据此提示。
     */
    fun moveDownloadFileToSubDir(uriString: String, subDirName: String): Boolean = runCatching {
        val clean = subDirName.trim().trim('/')
        if (clean.isEmpty() || clean.contains("..")) return@runCatching false
        val target = "${DownloadDir.RELATIVE_PATH}/$clean/"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.RELATIVE_PATH, target)
        }
        val moved = context.contentResolver
            .update(android.net.Uri.parse(uriString), values, null, null) > 0
        trace("批量移动: ${uriString.substringAfterLast("/")} → $target 结果=$moved")
        moved
    }.getOrElse { false }

    /** 轨迹文件是否有内容（界面用它区分"导出失败"和"还没产生日志"） */
    fun hasTraceLog(): Boolean = traceFile().let { it.exists() && it.length() > 0L }

    /**
     * 把轨迹日志导出到下载目录（`Download/BDT/`），返回导出后的文件名；没有日志时返回 null。
     *
     * 为什么走 MediaStore 插入而不是直接 `File` 写：Android 10+ 在**没有「所有文件访问」**时，
     * app 不能直接用 File 写公共下载目录（会被 scoped storage 拒），而往 `MediaStore.Downloads`
     * 插一条**本 app 拥有**的记录是允许的 —— 交付链路一直就是这么写的。
     * Android 9 及以下没有 `MediaStore.Downloads`，退回直接写文件（尽力而为）。
     */
    fun exportTraceLog(): String? = runCatching {
        val src = traceFile()
        if (!src.exists() || src.length() == 0L) return@runCatching null
        // ⚠️ 后缀必须与 MIME(text/plain) 一致：以前写成 `.log`，MediaProvider 会补成
        // `.log.txt`（2026-10-02 真机看到的就是双后缀）。
        val name = "BDT-诊断日志-" +
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date()) +
            ".txt"

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = DownloadDir.dir().apply { mkdirs() }
            src.copyTo(File(dir, name), overwrite = true)
            trace("导出诊断日志(legacy): $name")
            return@runCatching name
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, DownloadDir.RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { input -> input.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        trace("导出诊断日志: $name")
        name
    }.getOrNull()

    /** 把某个显示名对应的媒体库行删掉（文件已经不在原路径时用它清残留行） */
    private fun deleteRowsByDisplayName(
        resolver: android.content.ContentResolver,
        displayName: String,
    ) {
        val ids = mutableListOf<Long>()
        runCatching {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(displayName),
                null,
            )?.use { c ->
                val idx = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                while (c.moveToNext()) ids += c.getLong(idx)
            }
        }
        ids.forEach { id ->
            runCatching {
                resolver.delete(
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                    null,
                    null,
                )
            }
        }
    }

    /**
     * 是否拿到"所有文件访问"（MANAGE_EXTERNAL_STORAGE）。
     *
     * 有了它，交付与清理就能直接对文件 `delete()/renameTo()` —— 这台 ROM 上
     * MediaStore 的改名会往 `(N)` 上加、`_data` 还会与实际名字不一致，
     * 而 app 对这些 MediaProvider 拥有的文件本来是无权直改的。**用户可选授权**，
     * 没授权就走 MediaStore 那条（可用但可能留下副本，由"重复文件清理"兜底）。
     */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /**
     * 扫描下载目录里"同一部视频存在多份"的分组（只按名字判定，不做 stat ——
     * scoped storage 下这些文件的属性是读不到的，见 moveAsideSameContentFiles 的注释）。
     */
    fun findDuplicateGroups(
        relativePath: String,
    ): List<DuplicateDownloadRules.DuplicateGroup> {
        // ⚠️ 这里原来是 File(dir).listFiles()，在这台 ROM 上恒为 null（见 queryDownloadDirNames）
        val dir = resolveDownloadDir(relativePath)
        var names = queryDownloadDirNames(relativePath)
        if (names.size <= 1) {
            // 只看到 ≤1 条时**很可能是"目录里有文件还没被媒体库收录"**：
            // 实测过——用文件管理器/其他 App 写进下载目录的副本，MediaStore 一开始根本没有它的行，
            // 按行查自然查不到（真机表现：目录里两份、卡片却不出现）。
            // 所以先让媒体库扫一遍这个目录，再查一次；扫不动就按现有结果返回（不影响正确性）。
            trace("重复检查: $relativePath 首次只拿到 ${names.size} 条，触发目录扫描后重查")
            scanFileBlocking(dir)
            names = queryDownloadDirNames(relativePath)
            trace("重复检查: $relativePath 扫描后拿到 ${names.size} 条")
        }
        return DuplicateDownloadRules.groupDuplicates(names)
    }

    /**
     * 按名字删除下载目录里的文件（用户确认后的"清理重复文件"动作）。
     *
     * 两种模式：有"所有文件访问"就直接 `delete()`；否则先 `scanFile` 让媒体库收录/定位这一行、
     * 校验 `_data` 确实指向该文件、再删行（删行 = MediaProvider 连带删文件）。
     *
     * @return 实际删掉的个数（0 也要打日志 —— "删除静默失效"是这次踩过好几次的坑）
     */
    fun deleteFilesByName(relativePath: String, names: List<String>): CleanupResult {
        if (names.isEmpty()) return CleanupResult(0, emptyList())
        val dir = resolveDownloadDir(relativePath)
        val resolver = context.contentResolver
        val direct = hasAllFilesAccess()
        var deleted = 0
        val needsConsent = mutableListOf<android.net.Uri>()
        names.forEach { name ->
            val target = File(dir, name)
            if (direct) {
                if (runCatching { target.delete() }.getOrDefault(false)) deleted++
                return@forEach
            }
            // 先 scanFile 拿行；拿不到再按名字 + 相对路径查一次（能出现在枚举结果里就说明行是存在的）
            val uri = scanFileBlocking(target) ?: queryUriByName(resolver, relativePath, name)
            if (uri == null) {
                trace("清理重复文件: 找不到 $name 对应的媒体库行，跳过")
                return@forEach
            }
            val dataPath = contentDataPath(resolver, uri)
            if (dataPath != null && dataPath != target.absolutePath) {
                trace("清理重复文件: 扫描回来的行不是目标文件，跳过: $name → $dataPath")
                return@forEach
            }
            val rows = runCatching { resolver.delete(uri, null, null) }.getOrDefault(0)
            if (rows > 0) {
                deleted += rows
            } else {
                // ⚠️ 不是本 app 的行（上一版安装留下的、别的 App 写进来的副本）：
                // Android 11+ 必须走 MediaStore.createDeleteRequest 让系统弹确认框，
                // 直接 delete 会被拒 —— 真机表现就是"实际删除=0、目录纹丝不动"。
                needsConsent += uri
            }
        }
        trace(
            "清理重复文件: 目标=${names.size} 实际删除=$deleted 待用户确认=${needsConsent.size} " +
                "直删=$direct 目录=${dir.absolutePath}",
        )
        return CleanupResult(deleted, needsConsent)
    }

    /** 按显示名 + 相对路径前缀找出某个文件的媒体库行 uri（删除/发起用户确认都要用它） */
    fun queryUriByName(
        resolver: android.content.ContentResolver,
        relativePath: String,
        displayName: String,
    ): android.net.Uri? = runCatching {
        val rel = relativePath.trim('/')
        val collection = MediaStore.Files.getContentUri("external")
        resolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(displayName, "$rel/%"),
            null,
        )?.use { c ->
            if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null
        }
    }.getOrNull()

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
        trace(
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
        var targetDir = File(downloadsDir, DownloadDir.NAME)
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
