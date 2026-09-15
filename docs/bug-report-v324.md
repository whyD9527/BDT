# 哔哩下载器（BILIBILIAS 二次开发）全量缺陷审计报告

> 审计对象：`/sdcard/MT2/bilibilias`，**已发布 v3.2.4**（versionCode 324，commit `0850e9bf`，CI run `34845910471`）
> 审计日期：2026-09-14
> 审计范围：318 个 Kotlin 文件 / 40,762 行（不含构建产物）—— `app` 23,247 行、`core/data` 7,043 行、
> `core/network` 6,174 行、`core/database` 1,023 行、`core/ui` 2,112 行、`core/common` 519 行、`core/ffmpeg` 410 行
>
> ## 方法与证据等级（请先读这一节）
>
> 1. **12 个只读审计代理分区普查**（下载执行/队列/落盘/合并/解析/剪贴板/Room/网络/UI/设置/清单构建/协程资源），
>    产出候选清单；
> 2. **父代理逐条亲自核实**：凡本报告标 `✅ 已核实` 的，都由我本人重新打开对应文件、逐行确认过
>    （含刻意去证伪，例如"某个被声称不存在的测试其实存在"、"某个声称会走崩溃页的路径其实
>    `AppCrashHandler.init` 被注释掉了"）；
> 3. **凡未经真机复现的，本报告一律不写"已验证"**，只写"读码确认"或"待真机"。
>
> 证据等级图例：
> - `✅ 已核实`：父代理本人读码确认过（含行号）
> - `⚠️ 部分核实`：机制确认，但触发条件/可达性还需真机或实测
> - `❓ 存疑`：读码可疑但无法确证可达，**不要当结论用**
>
> ## 重要前提：这些**不是** bug（本轮特意排除的历史项）
>
> - `initDownloadList` 的 `.last()` —— 已改为 `readOnce()`（`FlowReadOnce.kt`），现存 `.last()` 都在会结束的网络流上
> - `RELATIVE_PATH` 带结尾斜杠 —— 已改两种写法都试（`DownloadRecordReuseRules.relativePathCandidates`）
> - 取消≠失败、分片写偏移/206/Content-Range/截断、重复 segment、单子任务提前写 COMPLETED、
>   空封面 URL、解析失败静默、番剧 `section[0].episodes[0]` —— 均已在这几轮修掉并保持修好
> - `AppCrashHandler`（崩溃页 + `killProcess`）**当前是关闭的** —— `BILIBIBLIAApplication.kt:17`
>   那行 `AppCrashHandler.instance.init(this)` 被注释掉了。所以下面任何"崩溃"的实际表现是
>   **Android 默认崩溃**（弹系统"应用已停止"），不是自定义崩溃页。有代理报告写成了"拉起崩溃页并 killProcess"，
>   那是错的，已按实际行为修正。
> - `core:ffmpeg` 整个模块已被 `settings.gradle.kts:32` 注释掉、不进构建，里面的缺陷是"启用即生效"，本轮单列。

---

# 一、高危（会导致坏产物 / 数据丢失 / 崩溃 / 功能彻底不可用）

## H1. 单连接下载**从不校验实际收到的字节数** → CDN 提前断流时把半截文件当成品交付 ✅ 已核实

- **位置**：`app/src/main/java/com/imcys/bilibilias/download/DownloadExecutor.kt:210-243`
- **证据**：`downloadedBytes` 只在第 226 行累加，**唯一的用途是第 228-232 行算进度**；
  写入循环 `while (!channel.exhausted())` 只在 EOF 退出，之后**无条件** `onProgress(1f); return true`。
- **触发**：服务端在发完 `Content-Length` 之前关闭连接 / 中途被中间设备掐断。
- **后果**：产出**被截断的媒体文件**，状态写 COMPLETED。分片路径本来有
  `actualLength >= totalLength` 校验（`SegmentedDownloader.kt:157-171`），但一旦回落到单连接，
  **这道保证就没了**。合并侧的兜底也几乎没有：`FfmpegMerger.kt:117-124` 只看返回码 + 输出非空。
- **严重度：高**。这是"静默产出坏文件"级别，且用户完全无从察觉（和本项目最怕的那类问题同源）。
- **修法**：`totalLength > 0 && downloadedBytes < totalLength` 时 **return false**（当次失败、进重试），
  并在日志里打出 `期望/实收`。

## H2. 单连接成功路径忽略 `renameTo` 返回值 → 报"下载成功"但目标文件不存在，且旧文件已先删 ✅ 已核实

- **位置**：`DownloadExecutor.kt:132-135`
  ```kotlin
  if (success) {
      if (file.exists()) file.delete()     // 133 行：先删旧成品
      tempFile.renameTo(file)              // 134 行：返回值被丢弃
      return@withContext true              // 135 行：无条件成功
  }
  ```
  对照分片路径 `:107-113` 是**有校验**的（`if (!tempFile.renameTo(file)) { log; return false }`）——两条分支不对称。
- **触发**：同目录 rename 失败（目标被占用、FUSE/MediaProvider 拒绝、并发清理）。
- **后果**：内容还在 `.downloading` 里、目标 `file` 已被删除，函数却返回 true → 任务 COMPLETED →
  合并阶段拿到一个**不存在的输入**。重试也不会自愈（第 76 行的"已完整"判断看的是 `file`）。
- **严重度：高**。与 H1 同一类"假成功"，但多一条"顺手删了旧成品"。
- **修法**：与分片分支一致，`renameTo` 失败即 `return false`；并且**把删除旧文件挪到 rename 成功之后**。

## H3. durl 单文件在「仅视频」下**仍然丢内嵌音轨** —— 上一轮 `0:a:0?` 修复被 `audioEnabled` 挡掉 ✅ 已核实

- **位置**：`core/data/.../download/merge/FfmpegCommandBuilder.kt:85-105`
- **机制**：`-map 0:a:0?` 被写在 `if (audioEnabled) { … mediaInputs.size == 1 … }` 内部；
  而 `audioEnabled = downloadMode in (AUDIO_ONLY, AUDIO_VIDEO)`（`:57-58`）——
  **durl + 仅视频（VIDEO_ONLY）时 `audioEnabled = false`，那行补映射根本不会执行**。
  命令只剩 `-map 0:v:0 -c:v copy`，而 **ffmpeg 一旦用了 `-map` 就只输出被映射的流**。
- **触发**：解析结果只有 durl（渐进式单文件）且用户选了「仅视频」。
  `analysis/components/DownloadInfoScreen` 对 durl 场景只列「仅视频」，所以这条是**用户很容易踩到的**。
- **后果**：**无声视频**、状态 COMPLETED、源文件已删 —— 正是第二十轮注释里声称要避免的场景，
  修复只覆盖了 AUDIO_VIDEO 一半。
- **严重度：高**（静默产出坏产物）。
- **修法**：`mediaInputs.size == 1 && videoFileCount == 1` 时**无条件**加 `-map 0:a:0?`（不挂在 `audioEnabled` 下）。

## H4. `AUDIO_ONLY` + 内嵌字幕：把音频流当字幕映射 + 容器不支持字幕 → **合并必然失败** ✅ 已核实

- **位置**：`FfmpegCommandBuilder.kt:106-113`（字幕映射）与 `:126-139`（`-c:s` 只在 `videoEnabled` 时给）
- **机制**：AUDIO_ONLY 时 `videoEnabled=false`，于是 `-map N:s:0` 里的 `N = subFileStartIdx = mediaInputs.size`
  （`:61`），指向的**是音频文件**；而 `-c:s`（mov_text/copy）被 `&& videoEnabled` 挡住，
  到 mp3 容器里既没有字幕流、也没有编码器 → ffmpeg 非 0 退出 → `FfmpegMerger.kt:132-138` 删产物抛错 → ERROR。
- **触发**：缓存模式=仅音频、音频容器=MP3、勾了「内嵌字幕」。
- **另一层问题**：本该拦住它的 `MediaContainer.canEmbedSubtitle()/canEmbedCover()`
  （`core/database/.../MediaContainer.kt:15-16,28,41-50`）**全仓只有定义、零调用**（已用 grep 全仓确认）。
- **后果**：任务每次都失败，**用户不改设置永远下不下来**；源文件保留，重试必然再失败。
- **严重度：高**（功能彻底不可用 + 无提示指向真正原因）。

## H5. Cookie 登录输入框：输入含裸 `%` 的字符串**直接崩溃**（主线程未捕获异常） ✅ 已核实

- **位置**：`ui/login/CookieLoginScreen.kt:98-101`（`onValueChange` → 同步调 `vm.checkCookies(it)`）
  → `ui/login/CookieLoginViewModel.kt:50` → `:118-119` `URLDecoder.decode(this, "UTF-8")`
- **机制**：`URLDecoder.decode` 对不完整/非法转义（`a=b%`、`%z`、`%2`）抛 `IllegalArgumentException`，
  整条链路（Compose 事件回调 → VM）**没有任何 try/catch**。
- **后果**：主线程未捕获异常 → **应用直接崩溃**（注意：`AppCrashHandler` 当前未注册，
  所以是系统默认崩溃，不是自定义崩溃页 —— 有代理这条写错了，已修正）。
- **严重度：高**。用户只是**粘贴/输入**就崩，且 Cookie 字符串里出现单个 `%` 并不罕见（转义残缺、URL 编码片段）。
- **修法**：`runCatching { URLDecoder.decode(...) }`，失败时退回原串并提示 Cookie 格式不对。

## H6. 抽帧功能：整段视频**按原生帧率**全量解码并全部常驻内存 → 必然 OOM ✅ 已核实（机制）

- **位置**：`ui/tools/frame/FrameExtractorViewModel.kt:180`（命令用 `-vf fps=$videoFps`，即**视频原生帧率**）、
  `:199-213`（`BitmapFactory.decodeFile` 全部进 `bitmapList`，无 `inSampleSize`、无 `recycle`、无上限）、
  `:144-149`（整份塞进 `_uiState.ImportSuccess.frameList`，跨屏存活）
- **规模估算**：10 分钟 30fps 视频 → 先落约 1.8 万张 PNG，再解码约 600 张 1080p 帧；
  ARGB_8888 每张约 8MB → 数 GB。
- **后果**：`OutOfMemoryError`（属 `Error`，`importVideo` 只 `catch (Exception)` 兜不住）→ 崩；
  磁盘侧还会写出上万张 PNG（`externalCacheDir/frameTemp`）。
- **严重度：高**（功能实际不可用 + 可能塞满存储）。
- **修法**：`inSampleSize` 降采样 + 总量上限 + `recycle` + 用 `-vf fps=$selectFps` 直接按需要的帧率抽。

## H7. `AppSettingsSerializer` 把「视频容器为空」的兜底**写错字段** → 可能崩 + 静默改回 Web 平台 ✅ 已核实

- **位置**：`core/datastore/.../AppSettingsSerializer.kt:60-63`
  ```kotlin
  if (parsed.useVideoContainer.isNullOrEmpty()){
      builder.setVideoParsePlatform(defaultValue.videoParsePlatform)   // ← 应为 setUseVideoContainer
      modified = true
  }
  ```
- **触发**：`app_setting.pb` 里 field 18（`use_video_container`）为空/缺失（老版本写入的 pb）。
- **后果两条**：
  1. 容器永远补不成默认 `mp4` → `AppSettingsRepository.storeMediaContainerFromExtension`（`:275-277`）
     的 `MediaContainer.entries.first { it.extension == extension }` 对**空串**抛
     `NoSuchElementException`；调用点 `AnalysisViewModel.kt:131-141`（settings 的 collect，未捕获）
     → **进解析页即崩**；
  2. 同一分支每次都把用户选的 TV 解析平台**静默改回 Web**（且会被后续写入固化）。
- **严重度：高**（崩溃 + 静默改用户设置）。是否命中取决于磁盘上那个 pb 的形态 → 触发概率 `⚠️ 部分核实`。
- **修法**：改成 `setUseVideoContainer(defaultValue.useVideoContainer)`；`first{}` 换成 `firstOrNull{} ?: MP4`。

## H8. 命名规则「目录路径」在弹幕/字幕/封面路径未被解析 → 要么失败要么拍平 ✅ 已核实（机制）

- **位置**：`FileOutputManager.kt:86-108`（`createDownloadOutputStream` 把整个 `fileName` 当成 `DISPLAY_NAME`，
  不解 `/`）；对比媒体路径 `:69-81` 会 `split("/")` 建子目录。
  设置页明确宣传这种写法可用：`ui/setting/contract/NamingConventionScreen.kt:207`（`"{author}/{p_title}"`）。
- **触发**：命名规则含 `/`，同时勾了弹幕/字幕/封面。
- **后果**：legacy 分支（Android ≤9）直接 `FileOutputStream(File(dir, fileName))` → 父目录不存在 →
  `FileNotFoundException` → 经 `handlePredecessor` 变**整集失败**；MediaStore 分支要么 insert 异常、
  要么被 sanitize 拍平（弹幕与视频分家）。
- **严重度：高**（与 H9 叠加时用户完全不知道为什么下不了）。

## H9. 前置任务（字幕/弹幕/封面）任何一步抛异常 → **整集下载失败**，媒体还没开始下 ✅ 已核实

- **位置**：`NewDownloadManager.kt:538-591`（`handlePredecessor` 整体无 catch）
  → `SubtitleDownloader.kt:73`（`getVideoCCInfo` 无 try/catch，与 `:42-56` 的 embed 分支**不对称**）、
  `:582-588`（下载字幕到下载目录）、`:572-574` + `:930-956`（封面）
- **触发**：字幕接口一次 403/超时、`finalSubtitleUrl` 为空串、封面请求抛异常、文件名为非法字符（见 H8）。
- **后果**：异常冒到 `handleTaskError` → 任务 ERROR，**能下的这一集完全下不下来**。
  附加内容本该是"可选活儿"（项目自己在 `DownloadPredecessorRules.canFetchCover` 里已经承认了这个原则），
  但这里是**一刀切致命**。
- **严重度：高**（可用性）。**修法**：每个附加步骤各自 `runCatching` + 降级为日志/提示，不拖垮媒体。

## H10. legacy 分支（Android 7–9）**先删旧文件再改名**，改名失败 = 用户旧成品永久丢失 ✅ 已核实

- **位置**：`FileOutputManager.kt:296-306`
  ```kotlin
  if (canDeleteExistingFile(newFileWritten = true) && targetFile.exists()) {
      targetFile.delete()                       // 300 行：删掉用户已有的成品
  }
  if (!stagingFile.renameTo(targetFile)) {      // 302 行：改名失败
      Log.e(TAG, "改名失败，放弃本次移动（旧文件已保留）: $fileName")  // ← 日志是错的，旧文件已经删了
      stagingFile.delete()                      // 304 行：把新文件也删了
      return null
  }
  ```
- **后果**：旧成品没了、新的也没了，调用方还会顺手删掉源子任务 → 该集只能整集重下。
  MediaStore 分支（`:193-215`）在同类失败下会**保留 `.part`**，两条分支语义不一致。
  连日志文案（"旧文件已保留"）都与事实相反。
- **严重度：高**（数据丢失）。触发需 `renameTo` 返回 false，概率较低 → `⚠️ 部分核实`。

## H11. 收藏/投稿等列表的重复 key 崩溃（同类：`WorkListScreen`、`LikeVideoScreen`） ⚠️ 部分核实

- **位置**：`ui/user/work/WorkListScreen.kt:140`（`key = { it.bvid }`）+ `WorkListViewModel.kt:109-113`
  （分页 `s.items + newList`，**无 bvid 去重**）；`ui/user/like/LikeVideoScreen.kt:89`（`key = cid ?: bvid`，同样无去重）
- **触发**：翻页期间 UP 主发新视频（B 站 pn/ps 分页整体后移 → 前后页出现同一 bvid）。
- **后果**：Compose `LazyGrid` 要求 key 唯一 → `IllegalArgumentException: Key … was already used` → **崩溃**。
- **严重度：高**（崩溃）但触发要靠时序 → `⚠️ 部分核实`。**修法**：合并时按 key 去重（本项目在
  `DownloadScreen` 已经为同一类问题把 key 从 platformId 换成 segmentId，这里是同类漏网）。

## H12. `NamingConventionConverter` 的 ruleType 与实体定义**错配** → 每次下载完成都把命名信息写坏/清空 ✅ 已核实

- **位置**：`core/database/.../converter/download/NamingConventionConverter.kt:12-35`
  - 写入：`put("ruleType", it.ruleType)`，而实体是 **`Video(1)` / `Donghua(2)`**（`NamingConventionInfo.kt:77/85`）
  - 读取：`when (ruleType) { 0 -> Video; 1 -> Donghua; else -> null }` ← **读的分支是 0/1**
- **机制**：写进去的是 1/2，读回来时：`ruleType=1`（Video）**被当成 Donghua**，
  `ruleType=2`（Donghua）落到 `else` **变成 null**。
- **后果**：`handleSuccessor`（`NewDownloadManager.kt:824-832`）与 `markCompletedWithoutMerge`（`:858-866`）
  都会 `getSegmentBySegmentId`（读回 → 已按错误规则降级）再整行 `updateSegment` 写回 →
  **持久化列被永久改写/清空**；且 `p`/`collection_title`/`collection_season_title` 这三个字段
  序列化里根本没写（`put` 列表里没有）。今天文件名用内存对象所以看不出来，
  但任何"从库里重建命名"的路径都会立刻错，重启后的重命名/续传也会错。
- **严重度：高**（每次下载静默损坏持久化数据；目前被内存路径掩盖）。
- **修法**：读取分支改成 `1 -> Video; 2 -> Donghua`，序列化补上缺的字段，并补一个 round-trip 单测。

---

# 二、中危

## M1. 换输入不复位解析结果 / 并发解析竞态 → 可能**下错稿件** ✅ 已核实（机制）

- `AnalysisViewModel.kt:207-216`（`updateInputAsText` 只清 `analysisBaseInfo`/`parseErrorMessage`，
  **不动** `asLinkResultType`/`downloadInfo`）；FAB 可见性只看 `downloadInfo.selectedCid/selectedEpId` 非空
  （`AnalysisScreen.kt:1306-1313`）；而 `createDownloadTask` 用的是旧 `asLinkResultType`（`:553-587`）。
- 另有并发入口：`updateSelectSeason`（`:437-442`）是独立 IO 协程、`collect` 里 fire-and-forget；
  登录态 collector（`:101-116`）会再次调 `analysisInputText`。**无 generation token、无 Job 取消**，
  谁后回来谁赢；旧请求的失败还能把新结果盖成错误卡（`:238-241` 无来源校验，而错误卡优先级更高）。
- **后果**：粘贴新链接后、防抖+网络窗口内点下载 → **下到上一个视频**。**严重度：中**。

## M2. 空选择被当成功 → 弹"已添加到下载队列"但队列为空 ✅ 已核实

- `DownloadTaskRepository.kt:202-221`（`pages.isEmpty()` → `emptyList()`，**不报错**）、`:276-287`
  （过滤后为空则不建节点）、`:328-349`（epId 同理）；`NewDownloadManager.addDownloadTask:308-323`
  不校验空 roots；`AnalysisViewModel.kt:584-588` 无条件弹成功 toast。
- 触发：选中集合与当前稿件不匹配（见 M1 的陈旧选择残留），或稿件没有可选分 P。
- **后果**：界面说"已加入队列"，实际队列里什么都没有；DB 里还插了一条**没有 segment 的 task**。
  **严重度：中**。

## M3. 「取消全选」按钮在**9 种语言**下名不符实 → 可能误删文件 ✅ 已核实

- `DownloadScreen.kt:381-391`：按钮文案 `R.string.download_deselect_all`，实现是
  `completedSegments.forEach { toggleSelection(...) }`（**逐个取反 = 反选**）。
- 资源对照：`values/strings.xml:203` = 「反选」、`values-es` = 「Invertir selección」（与实现一致）；
  而 **`values-en/ar/hi/ja/km/ko-rKR/rn/ur/vi`** 全是「Deselect All / 全部取消选择」（与实现**相反**）。
- **后果**：英/日/韩等语言用户想清空选择 → 未选中的全被选中 → 接着点删除（确认框不含数量/名称，
  `:265-284`）→ **删掉本不想删的任务与文件**。**严重度：中**（数据丢失，且只在非中文语言下发生，
  开发者自己也很难撞到）。

## M4. 清空缓存会删掉**正在下载**的工作文件 ✅ 已核实

- `StorageUtil.kt:169-183` 的 `clearCache` 递归删 `cacheDir`、`externalCacheDir`、
  **`getExternalFilesDir("video")`、`getExternalFilesDir("audio")`** —— 而后两个正是
  `NewDownloadManager.getSaveTempSubTaskPath`（`:1090-1096`）返回的下载工作目录
  （`.downloading`、`.downloadpart`、合并前的分片都在里面）。调用方
  `StorageManagementViewModel.kt:52-64` **没有"是否有任务在跑"的判断**，UI 上还标着"可放心清理"。
- **后果**：下载中被 unlink（FD 仍可写 → 最后 `renameTo` 失败 → 配合 H2 变"假成功"），
  或暂停任务的分片全丢、续传从头再来。**严重度：中**。

## M5. 命名规则目录名里出现 `//` 时 `pathForms[1]` 越界 ✅ 已核实

- `DownloadRecordReuseRules.relativePathCandidates`（`:81-82`）= `listOf(relativePath, relativePath.trimEnd('/') + "/").distinct()`
  —— 当传入值**已经**以 `/` 结尾时，两项相同 → `distinct()` 只剩 **1 个元素**；
  而 `FileOutputManager.kt:240-247` **无条件**用 `pathForms[1]`。
- 触发：渲染出的名字含 `//`（`runsOfSeparators` 只塌 `_`、不碰 `/`；模板写 `{author}//{p_title}` 即可）
  → `folderPath` 以 `/` 结尾。
- **后果**：`IndexOutOfBoundsException` 被 `:806` 的 `runCatching` 吞成"移动失败" →
  **媒体已下完却报失败**。**严重度：中**。

## M6. `AUDIO_VIDEO` + 音频容器选 MP3 → 二次有损转码（且该设置在此模式无意义） ✅ 已核实

- `FfmpegCommandBuilder.kt:120-123`（`-c:a copy`）与 `:152-154`（`-codec:a libmp3lame -q:a 2`）**作用于同一输出流，后者生效**；
  容器/扩展名仍由 `videoContainer` 决定（`NewDownloadManager.kt:884-909`）。
- **后果**：输出还是 `.mp4`，但 AAC/FLAC/EC-3 被强制重编码成 MP3 —— 无意义的有损转码 + UI 误导。
  **严重度：中**（音质损失、用户不知情）。

## M7. 合并进度时间单位不一致 → 合并阶段进度**一开就 100%** ⚠️ 部分核实

- `FfmpegMerger.kt:57` 与 `:144-152`：`statistics.time / duration`。`statistics.time` 是**毫秒**
  （同文件 `:78-80` 的 `getMediaDuration` 明确 `×1000`），而 `segment.duration` 来自
  `page.duration`/`episode.duration`（B 站接口是**秒**）。
- **后果**：`ms/秒` 被 `coerceIn(0f,1f)` 夹到 1.0 → 合并阶段通知恒 100%；`duration=0` 时进度不动。
  **严重度：中**（进度失真，用户以为卡住）。

## M8. 合并结果只校验返回码 + 输出非空，**从不校验流** ✅ 已核实

- `FfmpegMerger.kt:117-124`。任何"返回 0 但缺轨"（最现实的就是 H3 那个 `-map 0:a:0?` 的 `?`
  让映射失败不报错）都会被当成成功 → 写 COMPLETED 并删源文件 → 事后无法补救。**严重度：中**。

## M9. 房间级数据问题：多步写库**全程无事务** + 关键查询漏字段 ✅ 已核实

- **无事务**：全仓 `grep` 不到 `@Transaction`/`withTransaction`/`runInTransaction`。一次
  `createDownloadTask` 内最多 N 次 insert/update，中途抛异常（如 `:630`）就留半成品
  （只有 task 没有 segment / 节点建了 0 个 segment）。
- **`getTaskByPlatformId` 的 WHERE 漏 `download_platform`/type**（`DownloadTaskDao.kt:22-23`），
  且 `download_task.platform_id` **无唯一索引**（schema 4.json）；`getOrCreateTask` 是"先查后插"
  → 并发添加（UI 没有禁用按钮，双击即可）或数字命名空间撞车（番剧 seasonId / 合集 ugcSeasonId
  都直接当 platform_id）会插出两条 task，后续只取第一行，另一条成孤儿。
- **后果**：树/列表一致性与计数靠运气；老库的重复行**没有去重迁移**，永久留在「已完成」里指向坏文件。
  **严重度：中**。

## M10. `getBILIUserListByMid` 把 mid 传进 DAO 的 `uid` 槽 + 调用方先删行 → **账号自检恒失败、静默登出** ✅ 已核实

- `UserInfoRepository.kt:57`：`getBILIUserListByMid(mid) = biliUsersDao.getBILIUserListByUid(mid)`
  而 DAO 的 SQL 是 `where mid in (select mid from bili_users where id = :uid)`
  （`BILIUsersDao.kt:31-34`）—— **id 主键 ≠ B 站 mid**。
- 调用方 `BILIBILIASAppViewModel.kt:52-68` 还**先** `deleteBILIUserByUid(当前id)` **再**用 oldMid 查。
- **后果**：子查询必然空 → `userList.isEmpty()` → `setUserId(0)` + 提示"没找到合适的账户平台" →
  **用户被静默登出**；而这段逻辑本意是"切到同一 mid 的其它平台账号"，**从来没有生效过**。
  **严重度：中**（登录态丢失）。

## M11. 首页排版「补齐缺失板块」不落盘 → 升级后新增的首页模块**永不出现** ✅ 已核实

- `AppSettingsRepository.asyncHomeLayoutTypesetList`（`:127-145`）：`existingList.isEmpty()` 分支会
  `updateData`，而**非空**分支只把 `missingItems` 加进**返回值**、**不落盘**；
  两个调用方（`HomeViewModel.kt:162-171`、`LayoutTypesetViewModel.kt:20-28`）都把返回值丢掉、只 collect 磁盘值。
- **后果**：老用户升级后，新增的首页板块既不显示、也无法在排版页手动恢复 —— "自动补默认项"是死代码。
  **严重度：中**。

## M12. `episode_list_mode` **只写不读**（自赋值） ✅ 已核实

- `AnalysisViewModel.kt:131-141`：`_uiState.update { it.copy(episodeListMode = it.episodeListMode, …) }`
  —— `it` 是 UI state，本意应是 `appSetting.episodeListMode`。
- **后果**：用户在设置里切换"列表/网格"（`:864-871` 会落盘）后，**重进解析页永远回到默认**。
  **严重度：中**（设置项形同虚设）。

## M13. 首页 `LazyColumn` item 无 key，与可拖动排序页冲突 ✅ 已核实

- `HomeScreen.kt:360-546` 全程没有 `key`，却用了 `Modifier.animateItem()`；而
  `LayoutTypesetScreen.kt:106` 允许拖动改序。**后果**：改序后按位置复用、`animateItem` 失效、
  卡片内状态（弹窗开关）可能串到别的卡片。**严重度：中**（UI 错乱）。

## M14. 更新检查：`api.github.com` 在**未同意隐私政策时**就已出网 🔷 仅读码

- `BILIBILIASApp.kt:127-149`（Default 分支同时渲染 NavDisplay 与隐私弹窗）+
  `HomeScreen.kt:344-346` + `HomeViewModel.kt:125-146` + `GitHubReleaseService.kt:30`。
- **后果**：与 App 内文案"所有网络请求均直接发往哔哩哔哩官方接口"不符；首次启动即向 GitHub 发请求。
  **严重度：中**（隐私合规，非功能故障）。**存疑点**：需确认首次启动时 HomeViewModel 确实会跑（`⚠️`）。

---

# 三、低危（真实但影响面小 / 需要特定条件）

| # | 位置 | 问题 | 证据 |
|---|---|---|---|
| L1 | `DownloadExecutor.kt:74-79` | "已完整"只比 `file.length() >= remoteLength`，**不看清晰度**；合并/移动失败后源文件被有意保留 → 改清晰度重下会用旧文件冒充新成品 | ✅ |
| L2 | `SegmentedTempFileReconciler.kt:41-49` + `DownloadExecutor.kt:120-126` | 截断失败被 `runCatching` 吞且无日志；`reconcile` 的返回值只用于打日志（真正起点重读 `tempFile.length()`）→ 空洞可能被当已下载 | ⚠️ |
| L3 | `SegmentedDownloader.kt:157-171` | 成功判定只信 HEAD 的 `totalLength`，`Content-Range` 里解析到的总长**没用**；且探针用原主机、下载用替换后的主机 | ⚠️ |
| L4 | `DownloadExecutor.kt:129-148` | 本地 `.downloading` 比远端长 → 每次都发同一个越界 Range → 416 → 5 次必然同败，**没有"截断本地重来"的自愈** | ⚠️ |
| L5 | `DownloadExecutor.kt:132-135` | 单连接成功路径不删边车 `.downloadpart`（只在删任务时清） | ✅ |
| L6 | 全仓格式化 | **默认 Locale 参与**：`DanmakuXmlUtil.kt:30`（`%.5f` → 逗号小数点地区弹幕全乱）、`CCJsonToSrt.kt:20-26`、`CCJsonToAss.kt:88-94`（`DecimalFormat`） | ✅ |
| L7 | `FileOutputManager.kt:86-137` | 字幕/弹幕/封面**只 insert 不删同名**（媒体路径已修，这三条没修）→ 重下会出现 `xxx (1).xml/(1).srt/(1).jpg`；legacy 是**原地截断覆盖**，写一半失败毁旧文件 | ⚠️ |
| L8 | `SubtitleFileNameRules.kt:46-49` | 字幕文件名从 `segment.title` 直出，**不 sanitize、不截断**（标题含 `/` 或超 255 字节 → 落盘失败）；媒体路径会把 `/` 换 `_`，两条不一致 | ✅ |
| L9 | `DownloadTaskRepository.kt:752-768` | 复用记录时**不重置 `savePath`/`fileSize`**（只有新建才置空）→ 只勾附加内容也显示"音视频/旧画质"，旧文件被删后点开"文件不存在" | ✅ |
| L10 | `NamingConventionConverter.kt:20-45` | 除了 H12 的 ruleType，序列化还**丢字段**：Video 不存 `p`/`collection_title`/`collection_season_title`，Donghua 不存 `season_title` → 重启后按规则渲染出的名字会变 | ✅ |
| L11 | `NewDownloadManager.kt:918-932` | 弹幕分页失败被 `getOrNull()` 静默当结束 → **半截弹幕当成品**，无日志无提示 | ✅ |
| L12 | `NewDownloadManager.kt:1095` | `getExternalFilesDir(dirName)?.absolutePath!!` —— 外部存储不可用时 NPE（被上层 catch 成"添加下载任务失败：null"，无有效信息） | ✅ |
| L13 | `NewDownloadManager.kt:234,558`、`SubtitleDownloader.kt:46` | `File(context.externalCacheDir, …)` —— `externalCacheDir` 为 null 时静默变**相对路径**，后续写盘/ffmpeg 拿到不存在的路径 | ⚠️ |
| L14 | `ToastEvent.kt:61-75` | `sendToastEventOnBlocking` 在**主线程** `runBlocking`（当前 channel 是 UNLIMITED 所以 `send` 不挂起，实害小，但是 ANR 地基） | ✅ |
| L15 | `FileOutputManager.kt:122-136` + `AnalysisViewModel.kt:184-201` | 封面 insert 返回 null 时静默 no-op，UI 却**无条件**弹"保存成功" | ✅ |
| L16 | `QRCodeLoginViewModel.kt:245-261` | 保存二维码失败（写盘异常/无权限/磁盘满）后**照样弹"保存成功"** | ✅ |
| L17 | `QRCodeLoginViewModel.kt:202-262` | 二维码位图 + `compress(JPEG,100)` + 落盘**全在主线程**（弱机 ANR 风险） | ✅ |
| L18 | `CookieLoginViewModel.kt:41-57` | `currentCookies.addAll(...)` **前无 clear**（对比 `QRCodeLoginViewModel.kt:343` 有 clear），且每次按键调用 → 同一 Cookie 在库里重复上千行；每键一次无防抖网络请求 | ✅ |
| L19 | `FrameExtractorViewModel.kt:180-181` | 逐帧提取**仍在手工拼引号** + `FFmpegKit.execute(String)`（本项目为合并路径专门消灭过这个写法），标题含 `"` 时命令被切碎、失败还被静默吞成 `UIState.Default`（界面无提示） | ⚠️ |
| L20 | `FrameExtractorViewModel.kt:119-122,274-281` | legacy 路径下 frames 落在 **`Download/BiliDownloader/frames`**（用户可见目录），`deleteCacheDir` 只清 `externalCacheDir/frameTemp` → 上千张 PNG 永久残留 | ⚠️ |
| L21 | `FrameExtractorViewModel.kt:69-113,181` | `extractionJob.cancel()` **停不掉** 同步阻塞的 `FFmpegKit.execute`（需 `FFmpegKit.cancel()`）→ 旧任务继续写同一目录，新任务又 `deleteRecursively` | ✅ |
| L22 | `GooglePlayAppUpdateManage.kt:56-62,100-120` | `suspendCancellableCoroutine` **只注册成功回调**，无 failure/canceled → 无 Play 服务时协程永久挂起；`updateScope` 从不 cancel 且持有 Activity | ✅ |
| L23 | `HomeScreen.kt:401` + `settings.proto:25` | 「跳过更新」比较 `lastSkipUpdateVersion`（String，**全仓无写入点**），而写入的是 `lastSkipUpdateVersionCode` → 条件恒 false，卡片无法关闭 | ✅ |
| L24 | `SettingScreen.kt:570-584` | 「前台通知」开关 `onCheckedChange` 忽略传入值、`hasForegroundServicePermission` 是 remember 本地值 → 开关点不动、状态陈旧 | ✅ |
| L25 | `SettingScreen.kt:187-195` | 「缓存目录」卡片 `onClick = {}` —— 可点但无反应（误导） | ✅ |
| L26 | `StorageManagementScreen.kt:94-98` | `is Error -> {}` **整屏空白**（无文案无重试）；`StorageUtil.kt:117-119` 失败返回全 -1 仍照常显示"-1 B" | ✅ |
| L27 | `AppSettingsRepository.kt:223-237` | 工具历史上限 **off-by-one**（先 `if (size > 10) removeLast()` 再 `add(0, …)` → 稳定 11 条） | ✅ |
| L28 | `DownloadTaskDao.kt:117-118,136-137` | 两个**同名同义**删除方法；所有 `@Query` 的 DELETE/UPDATE 都不检查影响行数 → 删失败仍报"删除成功" | ✅ |
| L29 | `BILIUsersDao.kt:14-15` + schema | `bili_users` 无 `(mid, login_platform)` 唯一索引 → `OnConflictStrategy.IGNORE` 形同虚设，并发保存会出现重复用户行 | ✅ |
| L30 | `BILIBILIASAppViewModel.kt:61`、`ParsePlatformViewModel.kt:103` | 删账号**不删其 cookie 行**（`bili_user_cookies` 无外键）→ 明文 SESSDATA 永久残留（数据卫生） | ✅ |
| L31 | `Navigation/BILIBILAISNavDisplay.kt:466-483` | `addWithReuse` 在 `goToPage`/`onToPage` 调用点因 reified `T` 被推成 `NavKey` 而**恒不命中** → 栈内复用是死逻辑，重复入栈累积 | ✅ |
| L32 | `widget/DownloadTaskCard.kt:129-135,197-206` | COMPLETED/WAITING 也渲染**不确定进度条**（看起来还在下）；且"下载中"不显示取消按钮（只能先暂停） | ✅ |
| L33 | `core/ui/.../ASAsyncImage.kt:88-97` | 同一个 `modifier` 同时给 `Surface` 和内部 `AsyncImage` → `padding/border` 会叠加两次 | ✅ |
| L34 | `UserScreen.kt:425,437,585` | 可空值直接插进字符串 → 界面出现字面量 **"null" / "LV null"**，并把 `"null"` 当图片地址交给 Coil | ✅ |
| L35 | `WorkListViewModel`/`LikeVideoScreen` | 与 H11 同源的 key 重复（已单列高危，此处仅备注） | ⚠️ |
| L36 | `AndroidManifest.xml:8-12` | `READ_MEDIA_AUDIO`、`ACCESS_MEDIA_LOCATION` **全仓未使用**；`READ/WRITE_EXTERNAL_STORAGE` 无 `maxSdkVersion`（13+ 已失效仍声明） | ✅ |
| L37 | 大量 UI 文案 | 硬编码中文（`DownloadScreen.kt:439`、`SettingScreen.kt:143-435`、`DownloadTaskCard.kt:160-169`、`AnalysisScreen.kt` 多处、`ToolsScreen.kt:215`……）→ 已存在的 11 套翻译形同虚设 | ✅ |
| L38 | `AnalysisViewModel.kt:583-591` | `catch (e: Exception)` 把**取消**报成"添加下载任务失败"（`DownloadCancellationRules` 的同类漏网） | ✅ |
| L39 | `LineConfigViewModel.kt:160-171` | `catch (e: Throwable)` 把取消吞成 `0L` → 退出页面时所有线路被刷成"测速失败" | ✅ |
| L40 | `SegmentedDownloader.kt:371-381` | 在 `synchronized(emitLock)` 内调用外部回调（通知 IPC + StateFlow 更新）→ 4 片读循环排队等锁，binder 抖动时拖慢整体 | ⚠️ |

---

# 四、存疑（读码可疑，但**未确证可达**，不要当结论）

- `DownloadExecutor.kt:108-113/132-135`：**"要了 Range 却回 200"时 app 会先删旧文件再从头写** —— 若服务端确实回 200，这是正确行为；但如果 CDN 只是**偶发**忽略 Range，用户原来那份完整文件会先被删掉再重下（中途失败即数据丢失）。⚠️
- `KtorDI.kt:152-180` + `AutoBILIInfoPlugin.kt:41-52`：下载插件的 `onRequest` 会对媒体 CDN **覆盖** `DownloadExecutor` 传入的具体 Referer → 番剧等需要精确 Referer 的资源可能一直 403。❓
- `KtorDI.kt:152-157`：下载 client 只设 `requestTimeoutMillis`、**无 socket 超时**；若超时不覆盖 body 读取，半开连接会无限挂起（不失败、不重试）。❓
- `NewDownloadManager.kt:405-441`：`isAppInForeground` 只认 `IMPORTANCE_FOREGROUND`，前台服务态(125)不算 → 点下载后立刻切后台时，队列不启动且**无提示**，任务永远停在 WAITING。⚠️
- `NewDownloadManager.kt:490-504`：`downloadScope.launch` 在锁内、job 自己在 `finally` 里无锁 `remove` → 极端交错下陈旧 job 会被写回 `activeDownloadJobs`，由于并发上限是 1，**队列永久不再起新任务也不收工**。❓
- `NewDownloadManager.kt:1124-1127`：`cancelActiveJob` 的"查-删"不在 `queueLock` 内 → 与队列注册交错时可能**取消不到活 Job**（然后它跑完写 COMPLETED 复活）或误删别人的占位。❓
- `NewDownloadManager.kt:806-822`：移动阶段 `runCatching { moveToDownloadAndRegister(...) }` 会把 `CancellationException` 吞成"移动失败"→ 用户在 MERGING 点取消会**看到"下载失败：文件移入下载目录失败"**（取消≠失败的又一个漏网点）。⚠️
- `FfmpegMerger.kt`：合并完成后 ffmpeg 的 `statistics` 回调若再落一拍，会把状态写回 MERGING（`createProgressCallback` 无条件 `updateTaskState(MERGING)`）→ 任务永久留在列表、队列不收工。取决于 ffmpeg-kit 回调是否同线程有序。❓
- `AnalysisViewModel.kt:923-968`：`selectedCid.containsAll(cidList)` 判"取消全选"，有陈旧 id 残留时全选/取消全选**不对称**（[旧B1,C1] → 全选 → [B1,C1,C2,C3] → 取消 → 剩 [B1]）。⚠️
- `AnalysisViewModel.kt:733-737`：`redirectUrl` 递归调用**无终止条件**（重定向目标仍是短链 → 无限请求）。❓
- `VideoInfoFetcher.kt:82-103`：四处 `…?.durl?.first()` 对**非 null 空列表**会抛 `NoSuchElementException`（调用点未包 runCatching）；FLV 多段 durl 只取第一段。❓
- `AsRegexUtil.kt:35,52-53`：域名门槛是**子串**匹配 → `notbilibili.com`、`bilibili.com.evil.com` 都算 B 站域名（第十八轮"误判"修复不完整）。✅（机制确定）
- `AsRegexUtil.kt:38,82-84`：BV 正则**缺右边界**且不要求第 3 位是 `1` → `bv1234567890` 被当 BV、12+ 位长串被静默截成 10 位。✅（机制确定）
- `ClipboardAutoHandler.kt:77`：`clip.getItemAt(0)` 未查 `itemCount`（0 项 ClipData 会越界崩溃，但第三方 App 基本不会这么设置）。❓
- `ClipboardAutoHandler.kt:27,60-64`：去重只按文本内容、进程内全局、**无清除路径** → 同一链接再次复制**不再被识别**（旧实现靠清空剪贴板反而能识别）。⚠️
- `AnalysisScreen.kt:159-162`：`LaunchedEffect(analysisRoute.asInputText)` 在重新进入组合时会重跑，而 VM 按 key 保留 → 从登录页返回会**把用户改过的输入冲回旧文本**；经搜索 FAB 进入时甚至直接清空。❓
- `ClipboardAutoHandler.kt:54-58,74-86`：剪贴板读取（Binder + `coerceToText` 对 `content://` 会同步读流）**在主线程**。⚠️
- `StorageManagementScreen.kt:184-190,208-213`：硬编码 `substringAfter("/storage/emulated/0/")` → 多用户/工作资料下拼出非法 document uri。❓
- `NewDownloadManager.kt:509-511`：`downloadService?.let { … }` 为 null 时**静默什么都不做**，任务保持 WAITING 且 finally 立刻重挑 → 可能空转。❓
- `DownloadService.kt:25,92-111`：`notificationCompat` 是 `lateinit`，若服务只被 `bindService` 创建、`onStartCommand` 没跑，`updateNotification` 抛 `UninitializedPropertyAccessException` → 每个进度回调失败 → 重试 5 次后任务被误报失败。❓
- `QRCodeLoginViewModel.kt:141-143`、`CookieLoginViewModel.kt:69-71`：`if (mid == 0L) return` 挡不住 `mid == null`，随后 `mid!!` NPE。❓
- `AnalysisViewModel.kt:193`：封面后缀用 `substringAfterLast(".")`（与 `DownloadPredecessorRules.coverExtension` 不一致），URL 无 `.` 时可能带 `/`、`?` 进 `DISPLAY_NAME` → insert 抛异常。❓

---

# 五、已排除（本轮特意确认"**不是** bug"，避免下次重查）

- `core:ffmpeg` 整个模块**不在构建里**（`settings.gradle.kts:32` 注释掉、全仓无 import）→ 里面的
  `FFmpegMediaProcessor` 永不 resume、`MediaMergeManager` 异常路径不 release 都是"启用即生效"，**现在不是 bug**。
- `AppCrashHandler` 未注册（`BILIBILIASApplication.kt:17` 被注释）→ 它持有 Context、崩溃页等都不生效。
- 双 DataStore 实例（历史旧值问题）不复现：`KoinDI`/`RepositoryDI`/`StorageUtil`/`AppSettingSource` 都复用同一个 `by dataStore` 委托。
- 分片下载 proto（20/21 `optional`、夹取 2..8、UI 与消费方一致）修复完整。
- `RemoteVersion` 比较（v 前缀、段数补齐、预发布、非数字）正确且有单测；更新检查不会误报。
- Room 迁移/schema 一致（version=4、三条 Migration 齐全、identityHash 与生成代码一致、`PRAGMA foreign_keys=ON`）。
- `dash.audio.add(0, dolby/flac)` 不会 `UnsupportedOperationException`（模型声明为 `MutableList`）。
- `insertTask/Node/Segment` 用 REPLACE 不会因主键冲突级联删除（写路径都"先查后插"）。
- 空列表 `.first()`：`needsMerge` 已守住 `subTaskCount > 0`。
- `playResult!!`/`ogvResult?.videoInfo!!`：字段非空由反序列化保证，未找到可达 NPE 路径。
- `codecs.split(".")[0]`、`authorList.first()`（有 size 守卫）、超长数字 `toLong()`（已改 `toLongOrNull`）不越界/不崩。
- `sendToastEventOnBlocking` 不会 ANR（UNLIMITED Channel 的 `send` 不挂起）。
- `tool.iconRes!!`、`PlayVoucherErrorPage`/`RequestFrequentScreen`（事件无调用点）、`Screen.kt` 的 `context as Activity?` 等不可达或常规宿主下安全。
- `Mobile` 解析平台无写入入口；`fps=0` 的除零被 UI 夹取与 ffmpeg 先失败挡住。

---

# 六、修复进度（第 1 批已实施）

> 第 1 批（H1/H2/H3/H4/H5/H7）已于 2026-09-15 实施并推送：
> commit `847e52dd`，CI run **34938404265** 全绿（`Run unit tests` ✅ / `Assemble alphaRelease` ✅ / 固定签名 ✅）。
> 单测 core:data **203**、core:datastore **5**（新模块）、core:common 9、core:network 10；
> 变异 **61 条全部被抓到**。

| 项 | 状态 | 落点 | 验证 |
|---|---|---|---|
| H1 实收字节校验 | ✅ 已修 | 新 `DownloadCompletionRules`（:core:data）＋ `DownloadExecutor` 写完后按 `expectedLength/receivedLength/renamed` 判定 | 单测 6 条；变异 55 抓到。**真机未造出断流**，正常下载无回归 |
| H2 rename 失败不算成功 | ✅ 已修 | 同上；两条分支都改成"**先改名、成功后才允许删旧文件**"（原来单连接分支是先删旧文件、还丢弃返回值） | 单测；变异 56 抓到 |
| H3 durl 仅视频丢音轨 | ✅ 已修 | 新 `EmbedStreamMappingRules`＋ `FfmpegCommandBuilder`：`-map 0:a:0?` 不再挂在 `audioEnabled` 下 | 单测（`仅视频的单文件资源仍要带上内嵌音轨`）；变异 20/57 抓到。**真机待验**：需要"解析结果只有 durl"的稿件，B 站现在几乎不给 durl，手头没有可复现的样本 → 只有单测＋变异 |
| H4 音频容器硬塞字幕/封面 | ✅ 已修 | 同上：字幕要"容器支持 + 有视频轨"，封面按容器声明；把一直零调用的 `canEmbedSubtitle()/canEmbedCover()` 真正接上 | 单测 3 条；变异 58 抓到。真机：新构建在**仅音频+mp3＋勾选内嵌字幕**下跑通两次（`执行命令` 里**没有**任何 `:s:0` 映射、`合并完成` 正常）；但两次用的稿件**都没有字幕流**（ffmpeg 统计 `subtitle:0kB`、下载目录无 `.srt`），所以"**容器不支持时被拦住**"这一支**没有在真机上触发过** —— 它只有单测＋变异保证 |
| H5 Cookie 解码崩溃 | ✅ 已修 | 新 `CookieParsingRules`（解不出**退回原串**、绝不抛）＋ `CookieLoginViewModel` | 单测 8 条；变异 59/60 抓到。**真机已验证 ✅**：在 Cookie 输入框里输入含裸 `%` 的文本，不再崩溃、App 正常 |
| H7 设置兜底写错字段 | ✅ 已修 | `AppSettingsSerializer` 改回 `setUseVideoContainer`；另给 `storeMediaContainerFromExtension` 加 `firstOrNull` 回落（原 `first{}` 对空串抛异常 → 进解析页崩） | 新开 **:core:datastore 单测**（原本该模块 0 测试）5 条；变异 61 抓到 |

**顺带修掉一个隐患**：第 9 批的改动让旧的**变异 20 失效**（它原本盯的那行被重构成 `EmbedStreamMappingRules`），
全套跑下来它变成 `SKIP`。已按第八节第 11 条的要求把它改成"把内嵌音轨的映射目标换掉"（等价、能编译、确实产出无声视频），
重跑后抓到 —— **失效的变异不能算"测过了"**。

**真机顺带发现的新问题（已记，未修）**：
1. **`.part` 残留 + 改名校验误报**：`FileOutputManager` 里 MediaStore 那条路径用 `<正式名>.part` 当暂存名，
   改名后**回读 `DISPLAY_NAME` 校验**时读到的仍是带 `.part` 的临时名 → 打出
   `改名后 MediaStore 里的名字不是预期值（可能又生成了同名副本）` 这条**误报**（日志里连续两次），
   而且下载目录里真的留下了 `xxx.mp3` 与 `xxx (1).mp3` 两份 —— 说明"删同名旧文件"或"改名"仍有一步没生效。
   这条不在第 1 批范围内，属**新一轮真机暴露**，建议并入第 2 批一起查。
2. 下载目录里出现 `xxx (1).mp3` 副本（同上，重下同一集时旧文件没被替换掉）。

**仍未做**：第 2 批（H12 命名信息写坏、H6 抽帧 OOM、H8/H9 附加内容拖垮整集、清缓存删工作文件）、
第 3 批（M 系列）、第 4 批（事务/唯一约束、ffmpeg 语义）。

---

# 七、修复优先级建议

| 批次 | 内容 | 理由 |
|---|---|---|
| **第 1 批（建议立即）** | H1（截断当成功）、H2（rename 失败报成功）、H3（durl 丢音轨）、H4（音频+字幕必失败）、H5（Cookie 崩溃）、H7（容器字段写错） | 全是"坏产物/崩溃/功能不可用"，且**改动都很小**；其中 H1/H2/H3 直接关系"用户拿到的文件对不对" |
| **第 2 批** | H12（命名信息写坏）、H6（抽帧 OOM）、H8/H9（附加内容拖垮整集）、M4（清缓存删工作文件） | 数据完整性 + 可用性 |
| **第 3 批** | M1/M2（下错稿件、空任务假成功）、M5、M10、M11、M12、M3（误删按钮文案） | 用户可感知的错误行为 |
| **第 4 批** | M9（事务/唯一约束）、M6/M7/M8（ffmpeg 语义）、其余低危 | 结构性改进，改动面较大，建议单独立批 |

> ⚠️ **本报告的边界**：全部结论来自**读码 + 我在会话内的核实**。标注 `⚠️/❓` 的条目**没有真机复现**。
> 按本项目一贯规矩，动手修之前应对每条先写单测（能进库模块的抽成纯规则）或真机走一遍，
> 再决定是否值得改 —— 不要拿本报告当"已验证的事实清单"。
