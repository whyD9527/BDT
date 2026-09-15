#!/usr/bin/env python3
"""变异验证（mutation testing）跑手：把 bug 逐一注回生产代码，确认测试真的变红。

## 为什么需要它
这个项目被"类型/语法/构建全对、只有运行时/派生过程是错的"坑过三次
（wbi 的 `.take(32)`、`:core:data` 缺 serialization 插件、分片写偏移）。
这类错误**编译与 CI 构建都抓不到**，只能靠"能不能区分对错"的测试；
而"测试写没写对"本身又只能靠变异验证来回答 —— 把 bug 注回去，看它是否变红。

## 怎么用
    python3 tools/mutation_check.py                 # 跑全部
    python3 tools/mutation_check.py --filter 4      # 只跑名字以 "4" 开头的那个

需要 `JAVA_HOME` / `ANDROID_HOME`，并且**本机要先 `export LC_ALL=C.utf8`**
（否则中文名测试方法生成的类文件路径编码不了，报 Internal compiler error，见交接文档第八节第 9 条）。

## 判读
- `RED` = 测试抓到了（这是我们要的）；
- `GREEN` = **测试漏网**，必须补断言 —— 说明那条断言并不真的区分对错；
- `COMPILE_ERROR` = **无效变异**（代码根本没编译过，测试没跑），要换成等价但能编译的写法重跑，
  不能拿它当"抓到了"。

脚本只做"字符串替换 → 跑测试 → 还原"，不依赖 git，所以**未提交的新文件也能安全还原**
（绝不用 `git checkout -- .`，那会连未暂存的修改一起回滚，见交接文档第八节第 1 条）。
"""
import argparse
import os
import subprocess
import sys
from pathlib import Path

# 默认仓库根 = 本脚本所在目录的上一级
REPO = Path(os.environ.get("MUTATION_REPO", Path(__file__).resolve().parent.parent))
SEG = REPO / "core/data/src/main/java/com/imcys/bilibilias/data/download/segmented"
MERGE = REPO / "core/data/src/main/java/com/imcys/bilibilias/data/download/merge"
COMMON = REPO / "core/common/src/main/java/com/imcys/bilibilias/common/utils"
QUEUE = REPO / "core/data/src/main/java/com/imcys/bilibilias/data/download/queue"
ANALYSIS = REPO / "core/data/src/main/java/com/imcys/bilibilias/data/analysis"
RECORD = REPO / "core/data/src/main/java/com/imcys/bilibilias/data/download/record"
PREDECESSOR = REPO / "core/data/src/main/java/com/imcys/bilibilias/data/download/predecessor"
STARTUP = REPO / "core/data/src/main/java/com/imcys/bilibilias/data/download/startup"
CANCEL = REPO / "core/data/src/main/java/com/imcys/bilibilias/data/download/cancel"
RESUME = REPO / "core/data/src/main/java/com/imcys/bilibilias/data/download/resume"
CLIPBOARD = REPO / "core/data/src/main/java/com/imcys/bilibilias/data/clipboard"
NAMING = REPO / "core/data/src/main/java/com/imcys/bilibilias/data/download/naming"
SUBTITLE = REPO / "core/data/src/main/java/com/imcys/bilibilias/data/download/subtitle"
BVID = REPO / "core/data/src/main/java/com/imcys/bilibilias/data/download/bvid"
EMBED_CACHE = REPO / "core/data/src/main/java/com/imcys/bilibilias/data/download/cache"
EXEC_RULES = REPO / "core/data/src/main/java/com/imcys/bilibilias/data/download/execution"
DATASTORE = REPO / "core/datastore/src/main/java/com/imcys/bilibilias/datastore"
OUTPUT = REPO / "core/data/src/main/java/com/imcys/bilibilias/data/download/output"
UTIL = REPO / "core/data/src/main/java/com/imcys/bilibilias/data/util"

# (名字, 文件, 原串, 替换成)  —— 原串必须在文件里**恰好出现一次**，否则跳过并报警
MUTATIONS = [
    ("1 续传写偏移用片起点（经典续传写歪）",
     SEG / "SegmentedDownloader.kt",
     "raf.seek(startOffset)", "raf.seek(segment.start)"),
    ("2 不校验 206（服务端忽略 Range 也照写）",
     SEG / "SegmentedDownloader.kt",
     "if (response.status != HttpStatusCode.PartialContent) {", "if (false) {"),
    # 注意：不能把条件整个换成 false —— 后面的 contentRange.start 依赖那处空值检查的智能转换，
    # 换掉会**编译失败**，那就成了"无效变异"（测试根本没跑）。
    ("3 不校验 Content-Range 起点",
     SEG / "SegmentedDownloader.kt",
     "if (contentRange != null && contentRange.start != startOffset) {",
     "if (contentRange != null && contentRange.start != startOffset && false) {"),
    ("4 不把写入截断在片尾（照单全收多发的字节）",
     SEG / "SegmentedDownloader.kt",
     "val want =\n                        minOf(BUFFER_SIZE.toLong(), segment.endInclusive - position + 1).toInt()",
     "val want = BUFFER_SIZE"),
    ("5 回落单连接前不截断临时文件（洞被当成已下载）",
     SEG / "SegmentedTempFileReconciler.kt",
     "if (tempFile.exists() && fileLength != prefix) {", "if (false) {"),
    ("6 连续前缀把未完成的片也算成满的",
     SEG / "SegmentDownloadMeta.kt",
     "prefix += part.downloaded.coerceIn(0L, part.length)", "prefix += part.length"),
    ("7 matches 不校验自身声明的总长",
     SEG / "SegmentDownloadMeta.kt",
     "if (totalLength != planned.sumOf { it.length }) return false", ""),
    ("8 续传决策不校验计划一致性",
     SEG / "SegmentResumePlanner.kt",
     "val trusted = meta?.takeIf { it.matches(segments) }", "val trusted = meta"),
    ("9 边车进度超出文件长度时仍然照用",
     SEG / "SegmentedDownloader.kt",
     "if (part.alreadyDownloaded > usable) {", "if (false) {"),
    ("10 进度只按第一片算（永远到不了 1）",
     SEG / "SegmentedDownloader.kt",
     "val current = SegmentedDownloadPlan.progressOf(sum(), totalLength)",
     "val current = SegmentedDownloadPlan.progressOf(downloaded.get(0), totalLength)"),
    # ---- 第 3 步：设置项解析 ----
    ("11 没设置过时把分片开关默认成关（升级即静默失去功能）",
     SEG / "SegmentedDownloadPlan.kt",
     "fun resolveEnabled(configured: Boolean?): Boolean = configured ?: DEFAULT_ENABLED",
     "fun resolveEnabled(configured: Boolean?): Boolean = configured ?: false"),
    ("12 并发数不夹取（设置里填 0/1 会让分片开关形同虚设）",
     SEG / "SegmentedDownloadPlan.kt",
     "        configured?.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY) ?: DEFAULT_CONCURRENCY",
     "        configured ?: DEFAULT_CONCURRENCY"),
    # ---- 第 1 批：崩溃 / 误判 / ffmpeg 元数据（见交接文档第十八轮）----
    ("13 数字 ID 用 toLong 而不是 toLongOrNull（剪贴板可触发的崩溃）",
     COMMON / "AsRegexUtil.kt",
     "private fun String.toIdOrNull(): Long? = toLongOrNull()",
     "private fun String.toIdOrNull(): Long? = toLong()",
     ":core:common:testDebugUnitTest"),
    ("14 去掉域名/整串门槛（非B站文本被误判成链接）",
     COMMON / "AsRegexUtil.kt",
     "        if (!hasBiliHost && !coversWholeText) return null",
     "        if (false) return null",
     ":core:common:testDebugUnitTest"),
    ("15 av 关键字左边界失效（nav12345 被当成 av12345）",
     COMMON / "AsRegexUtil.kt",
     r'private val regexAV = Regex("""(?<![A-Za-z0-9])[Aa][Vv]([0-9]+)""")',
     r'private val regexAV = Regex("""[Aa][Vv]([0-9]+)""")',
     ":core:common:testDebugUnitTest"),
    ("16 ffmpeg 元数据又手工拼引号（标题含双引号时合并必失败）",
     MERGE / "FfmpegCommandBuilder.kt",
     '            add("title=$title")',
     '            add("title=\\"$title\\"")'),
    # ---- 第 2 批：队列规则（见交接文档第十九轮）----
    ("17 失败任务不再允许重下（被当成已在队列里静默跳过）",
     QUEUE / "DownloadQueueRules.kt",
     "                stateOf(result[index]) == DownloadState.ERROR -> result[index] = task",
     "                false -> result[index] = task"),
    ("18 选任务时不看 activeIds（同一任务可能被起两个 Job，双写同一文件）",
     QUEUE / "DownloadQueueRules.kt",
     "            .indexOfFirst { (id, state) -> state == DownloadState.WAITING && id !in activeIds }",
     "            .indexOfFirst { (_, state) -> state == DownloadState.WAITING }"),
    # ⚠️ 第 9 批（H3）把这段逻辑挪进了 `EmbedStreamMappingRules`，原来那条变异串
    #    （`} else if (mediaInputs.size == 1) {`）已经定位不到 → 变成了 SKIP。
    #    现在改成"把内嵌音轨的映射目标换掉"：等价、能编译、且确实产出无声视频。
    ("20 durl 单文件不映射内嵌音轨（产出无声视频却标已完成）",
     MERGE / "FfmpegCommandBuilder.kt",
     '                add("0:a:0?")',
     '                add("0:v:0")'),
    ("19 PAUSE 不算收工（有暂停任务时队列永不退出 → 前台服务与通知常驻）",
     QUEUE / "DownloadQueueRules.kt",
     """        DownloadState.CANCELLED,
        DownloadState.PAUSE,
    )""",
     """        DownloadState.CANCELLED,
    )"""),
    # ---- 第 3 批 A：收尾阶段（见交接文档第十八轮）----
    ("21 不看「是否勾了媒体」就合并（只勾封面/弹幕/字幕时必然 ERROR）",
     MERGE / "DownloadSuccessorRules.kt",
     "        downloadMedia && subTaskCount > 0",
     "        subTaskCount > 0"),
    ("22 不看子任务条数就合并（没有媒体流时取 first() 抛异常）",
     MERGE / "DownloadSuccessorRules.kt",
     "        downloadMedia && subTaskCount > 0",
     "        downloadMedia"),
    ("23 移动失败也把源文件删掉（重试只能整集重下）",
     MERGE / "DownloadSuccessorRules.kt",
     "        val paths = if (moveSucceeded) subTaskPaths + tempOutputPath else listOf(tempOutputPath)",
     "        val paths = subTaskPaths + tempOutputPath"),
    ("24 移动成功也不清理源文件（私有目录里堆满已下完的分片）",
     MERGE / "DownloadSuccessorRules.kt",
     "        val paths = if (moveSucceeded) subTaskPaths + tempOutputPath else listOf(tempOutputPath)",
     "        val paths = listOf(tempOutputPath)"),
    # ---- 第 3 批 B：解析输入管线（见交接文档第十八轮）----
    ("25 提交时对等值去重（同一段文本再粘一次 / 点重试都没反应）",
     ANALYSIS / "AnalysisInputPipeline.kt",
     """    fun submit(text: String) {
        submissions.tryEmit(text)
    }""",
     """    private var lastSubmitted: String? = null

    fun submit(text: String) {
        if (text == lastSubmitted) return
        lastSubmitted = text
        submissions.tryEmit(text)
    }"""),
    ("26 去掉防抖（连续输入时每次按键都发一次解析请求）",
     ANALYSIS / "AnalysisInputPipeline.kt",
     "    val requests: Flow<String> = submissions.debounce(debounceMillis)",
     "    val requests: Flow<String> = submissions"),
    ("27 replay=1（订阅时重放旧输入 → 界面莫名重解析一段旧文本）",
     ANALYSIS / "AnalysisInputPipeline.kt",
     "        replay = 0,",
     "        replay = 1,"),
    # ---- 第 3 批 C：显示规则 / 前置任务（见交接文档第二十一轮真机记录与第十八轮）----
    ("28 没有媒体文件也照显示「模式/画质/封装」（看着像视频下好了）",
     RECORD / "DownloadRecordDisplayRules.kt",
     "    ): List<String> = if (hasMediaFile(savePath)) {",
     "    ): List<String> = if (true) {"),
    ("29 空白 savePath 也算有媒体文件",
     RECORD / "DownloadRecordDisplayRules.kt",
     "    fun hasMediaFile(savePath: String): Boolean = savePath.isNotBlank()",
     "    fun hasMediaFile(savePath: String): Boolean = true"),
    ("30 封面为 null 也去发请求（空 URL 抛异常 → 整集下载失败）",
     PREDECESSOR / "DownloadPredecessorRules.kt",
     "    fun canFetchCover(coverUrl: String?): Boolean = !coverUrl.isNullOrBlank()",
     "    fun canFetchCover(coverUrl: String?): Boolean = true"),
    ("31 扩展名取不到就拼出 _pic.null",
     PREDECESSOR / "DownloadPredecessorRules.kt",
     '            "jpg"',
     '            "null"'),
    # ---- 第 4 批：重启清理 / 取消语义（见交接文档第十八轮）----
    ("32 PAUSE 被当已完成保留（重启后又是看不见的幽灵）",
     STARTUP / "DownloadStartupRules.kt",
     "        DownloadState.COMPLETED -> StartupAction.KEEP",
     "        DownloadState.COMPLETED, DownloadState.PAUSE -> StartupAction.KEEP"),
    ("33 失败/取消的临时文件不清理（磁盘残留）",
     STARTUP / "DownloadStartupRules.kt",
     "        DownloadState.ERROR,\n        DownloadState.CANCELLED,\n        -> StartupAction.DISCARD_AND_CLEAN",
     "        DownloadState.ERROR,\n        DownloadState.CANCELLED,\n        -> StartupAction.DISCARD_KEEP_FILES"),
    ("34 临时文件前缀不带下划线（会误删相邻编号的产物）",
     STARTUP / "DownloadStartupRules.kt",
     '    fun tempFilePrefix(platformId: String): String = "${platformId}_"',
     '    fun tempFilePrefix(platformId: String): String = platformId'),
    ("35 取消被当成可重试失败（暂停 → 白重试 5 次 → 记成下载失败）",
     CANCEL / "DownloadCancellationRules.kt",
     "    fun isRetryableFailure(error: Throwable): Boolean = !isCancellation(error)",
     "    fun isRetryableFailure(error: Throwable): Boolean = isCancellation(error)"),
    ("36 不看 cause 链（被包一层的取消又变成失败）",
     CANCEL / "DownloadCancellationRules.kt",
     "            current = current.cause",
     "            current = null"),
    # 注：这里不能直接换成 kotlinx 的 `last()` —— 那需要给文件加 import，会变成**无效变异**（编译不过）。
    # 换成等价的"等一个永不满足的条件"，在 Room 那种不会结束的热流上同样是**永远不返回**。
    ("37 一次性读取变成永远等下去（Room 热流不结束 → 清理再也不执行）",
     UTIL / "FlowReadOnce.kt",
     "suspend fun <T> Flow<T>.readOnce(): T = first()",
     "suspend fun <T> Flow<T>.readOnce(): T = first { false }"),
    # ---- 第 5 批：重复 segment / 落盘顺序 ----
    ("38 有旧记录也插新的（同一集插出第二条记录，指向不存在的文件）",
     RECORD / "DownloadRecordReuseRules.kt",
     "        if (hasExistingSegment) PersistAction.UPDATE_EXISTING else PersistAction.INSERT_NEW",
     "        if (false) PersistAction.UPDATE_EXISTING else PersistAction.INSERT_NEW"),
    ("39 复用已完成的记录却不重置状态（带着 COMPLETED 进队 → 点了下载什么都不发生）",
     RECORD / "DownloadRecordReuseRules.kt",
     "        if (previous == DownloadState.COMPLETED) DownloadState.WAITING else previous",
     "        previous"),
    ("40 临时名与正式名相同（等于边写边覆盖用户已有的文件）",
     RECORD / "DownloadRecordReuseRules.kt",
     '    fun stagingFileName(finalName: String): String = "$finalName.part"',
     "    fun stagingFileName(finalName: String): String = finalName"),
    ("41 新文件没写完就允许删旧文件（失败就把用户的旧文件弄没了）",
     RECORD / "DownloadRecordReuseRules.kt",
     "    fun canDeleteExistingFile(newFileWritten: Boolean): Boolean = newFileWritten",
     "    fun canDeleteExistingFile(newFileWritten: Boolean): Boolean = true"),
    ("42 查同名文件只试一种 RELATIVE_PATH（MediaStore 存的是带斜杠的 → 旧文件永远删不掉）",
     RECORD / "DownloadRecordReuseRules.kt",
     """        listOf(relativePath, relativePath.trimEnd('/') + "/").distinct()""",
     "        listOf(relativePath)"),
    # ---- 第 6 批：单连接续传写模式 ----
    ("43 不校验 206（要了 Range 却回 200 时按追加写 → 文件长度翻倍、内容错位）",
     RESUME / "SingleConnectionResumeRules.kt",
     "        if (!statusIsPartialContent) return WriteMode.WRITE_FROM_START",
     "        if (false) return WriteMode.WRITE_FROM_START"),
    ("44 不校验 Content-Range 起点（服务端回了别的段也照追加）",
     RESUME / "SingleConnectionResumeRules.kt",
     "        if (contentRangeStart != null && contentRangeStart != requestedRangeFrom) {",
     "        if (false) {"),
    # ---- 第 7 批：剪贴板门槛与去重 ----
    ("45 隐私门槛把「已拒绝」也放行（回到原来两处不一致的行为）",
     CLIPBOARD / "ClipboardHandlingRules.kt",
     "            privacyState == AppSettings.AgreePrivacyPolicyState.Agreed",
     "            privacyState != AppSettings.AgreePrivacyPolicyState.Default"),
    ("46 去重失效（同一段剪贴板文本被反复处理）",
     CLIPBOARD / "ClipboardHandlingRules.kt",
     "        !text.isNullOrBlank() && text != lastHandledText",
     "        !text.isNullOrBlank()"),
    # ---- 第 8 批：字幕文件名 / 命名规则 / bvid 兜底 / 内嵌缓存 ----
    ("47 字幕文件名又丢掉扩展名（下到下载目录的文件没有后缀）",
     SUBTITLE / "SubtitleFileNameRules.kt",
     '        return "${title}_${language}.$ext"',
     '        return "${title}_${language}"'),
    ("48 字幕后缀一律回落 srt（ASS 字幕被写成 .srt）",
     SUBTITLE / "SubtitleFileNameRules.kt",
     '        "ass" -> "ass"',
     '        "ass" -> "srt"'),
    ("49 命名规则又对整串 collapse（标题里的下划线被吃掉，就是那个 bug）",
     NAMING / "NamingConventionRenderer.kt",
     '                var literal = part.text.replace(runsOfSeparators, "_")',
     '                var literal = part.text'),
    ("50 取值为空时不吃掉留下的分隔下划线（模板里出现双下划线）",
     NAMING / "NamingConventionRenderer.kt",
     '                    literal = literal.removePrefix("_")',
     '                    literal = literal'),
    ("51 平台 JSON 没有 bvid 字段时不落到节点上（兜底又变回死代码）",
     BVID / "DownloadBvIdResolver.kt",
     "        return nodePlatformId?.takeIf { this.isValidBvId(it) }",
     "        return null"),
    ("52 bvid 不校验形状（数字 CID 被当成 BV 号发出去）",
     BVID / "DownloadBvIdResolver.kt",
     '    private val bvIdPattern = Regex("^BV[0-9A-Za-z]{10}$")',
     '    private val bvIdPattern = Regex("^.*$")'),
    ("53 内嵌缓存不校验目录（别处的同名文件也会被删）",
     EMBED_CACHE / "EmbedCacheRules.kt",
     "        if (parentDirName !in dirNames) return false",
     "        if (false) return false"),
    ("54 内嵌缓存不校验文件名前缀（整目录的东西都会被删）",
     EMBED_CACHE / "EmbedCacheRules.kt",
     '        return fileName.startsWith(EMBED_PREFIX) && fileName.length > EMBED_PREFIX.length',
     '        return true'),
    # ---- 第 9 批：全量审计高危 H1~H7（v3.2.5）----
    ("55 不核对实收字节数（CDN 提前断流时半截文件当成品交付）",
     EXEC_RULES / "DownloadCompletionRules.kt",
     "        expectedLength > 0 && receivedLength < expectedLength -> Outcome.INCOMPLETE",
     "        false -> Outcome.INCOMPLETE"),
    ("56 改名失败也算成功（目标文件不存在却报成功）",
     EXEC_RULES / "DownloadCompletionRules.kt",
     "        !renamed -> Outcome.RENAME_FAILED",
     "        false -> Outcome.RENAME_FAILED"),
    ("57 durl 补内嵌音轨的判断又挂回 audioEnabled 等价条件（无声视频）",
     EXEC_RULES / "EmbedStreamMappingRules.kt",
     "    ): Boolean = mediaInputCount == 1 && !hasSeparateAudioInput",
     "    ): Boolean = mediaInputCount == 1 && !hasSeparateAudioInput && false"),
    ("58 纯音频容器也允许映射字幕（合并必然失败）",
     EXEC_RULES / "EmbedStreamMappingRules.kt",
     "    ): Boolean = containerSupportsSubtitle && videoEnabled",
     "    ): Boolean = containerSupportsSubtitle"),
    ("59 Cookie 解码失败不回退（裸百分号又崩进程）",
     EXEC_RULES / "CookieParsingRules.kt",
     "    }.getOrDefault(value)",
     "    }.getOrThrow()"),
    ("60 Cookie 解析不跳过没有等号的片段",
     EXEC_RULES / "CookieParsingRules.kt",
     "                if (parts.size != 2) return@mapNotNull null",
     "                if (parts.size < 1) return@mapNotNull null"),
    ("61 设置兜底又写错字段（容器永远补不成默认值）",
     DATASTORE / "AppSettingsSerializer.kt",
     "                builder.setUseVideoContainer(defaultValue.useVideoContainer)",
     "                builder.setVideoParsePlatform(defaultValue.videoParsePlatform)",
     ":core:datastore:testDebugUnitTest"),
    # ---- 第 10 批：重下同一集留下 (1)/(2) 副本（真机复现）----
    ("62 只剩暂存名也当成交付成功（用户拿到 .part 名字的文件）",
     OUTPUT / "FinalNameVerifyRules.kt",
     "            hasStaging -> Verdict.KEPT_STAGING_NAME",
     "            hasStaging -> Verdict.OK"),
    ("63 出现同名副本也不报（用户目录里越攒越多 (1)/(2)）",
     OUTPUT / "FinalNameVerifyRules.kt",
     "            hasDuplicate -> Verdict.DUPLICATE_SUFFIX",
     "            hasDuplicate -> Verdict.OK"),
    ("64 回读名字不校验（改名失败也算成功）",
     OUTPUT / "FinalNameVerifyRules.kt",
     "        storedName == expectedName",
     "        true"),
]

DEFAULT_TEST_TASK = ":core:data:testDebugUnitTest"


# 变异条目：4 元组用 DEFAULT_TEST_TASK；5 元组最后一项是显式测试任务
# （有些目标在 core:common，得跑那个模块的单测）
def run_tests(task: str) -> tuple[int, bool, str]:
    env = dict(os.environ)
    env.setdefault("LC_ALL", "C.utf8")
    env.setdefault("LANG", "C.utf8")
    proc = subprocess.run(
        ["sh", "gradlew", task],
        cwd=REPO,
        capture_output=True,
        text=True,
        env=env,
    )
    out = proc.stdout + proc.stderr
    compile_error = "Compilation error" in out or "Internal compiler error" in out
    return proc.returncode, compile_error, out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--filter", default="", help="只跑名字以该前缀开头的变异")
    args = parser.parse_args()

    results = []
    for entry in MUTATIONS:
        name, path, old, new = entry[:4]
        task = entry[4] if len(entry) > 4 else DEFAULT_TEST_TASK
        if args.filter and not name.startswith(args.filter):
            continue

        original = path.read_text()
        if original.count(old) != 1:
            print(f"{'SKIP(定位不到唯一匹配)':28} {name}  [count={original.count(old)}]")
            results.append((name, "SKIP"))
            continue

        path.write_text(original.replace(old, new, 1))
        try:
            code, compile_error, out = run_tests(task)
        finally:
            # 无论成败都必须还原：还原失败会让后续变异全部失真
            path.write_text(original)

        if compile_error:
            verdict = "COMPILE_ERROR(无效变异)"
        elif code != 0:
            verdict = "RED(测试抓到了) ✅"
        else:
            verdict = "GREEN(测试漏网) ❌"
        results.append((name, verdict))
        print(f"{verdict:28} {name}", flush=True)

    print("\n=== 汇总 ===")
    bad = [r for r in results if not r[1].startswith("RED")]
    print(f"变异总数={len(results)}  被测试抓到={len(results) - len(bad)}  漏网/无效={len(bad)}")
    for name, verdict in bad:
        print(f"  ⚠️  {verdict}  {name}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
