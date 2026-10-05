# 本仓库的工作铁律（代理必读）

## 一、真机测试是唯一标准

> **社会实践是检验真理的唯一标准。**  代码通过本地校验、单元测试、CI 双绿，**都不等于功能可用**；
> 任何改动**没有在真机上跑过，就不算完成**。

### 必须遵守的动作

1. **每项功能改动都要有真机判据**，且判据要是"可核验的客观证据"，而不是"我觉得没问题"：
   - `Android/data/com.whyd9527.bilibilias/files/logs/download-trace.log` 里的**具体行**（交付/清理/探测/更新检查/导出）；
   - `…/logs/crash.log` 的**条数与时间戳**（不新增 = 修好）；
   - `/data/app` 里本应用安装目录的**时间戳/路径变化**（确认装的是哪一版）；
   - `Download/BDT/` 下**导出文件的真实内容**（反馈包、诊断日志、逐帧 PNG 的分辨率…）。
2. **发版前必须**：装包 → 冒烟（能启动 + 主要页面逐个打开）→ 针对本批改动**逐条**验 → 再看 `crash.log` 无新增。
3. **不允许**用"本地校验通过 / 单测通过 / CI 绿"代替真机验证（它们只是最低门槛）。
4. 真机复验的条目清单见 `docs/真机验证清单.md`；历史结论与踩坑记录见 `docs/项目交接文档.md`。
5. 设备操作优先走 `127.0.0.1:3090` 的 App 层接口（`/app/ui/dump`、`/app/ui/tap`、`/app/ui/key`、`/app/launch` 等），
   需要看设备日志/文件时用受保护的设备 shell；**安装 APK 只能由用户在真机上完成**。

### 已知的真机"坑"（写下来免得重复踩）

- 界面横滑可能被当成退出；**文字点按比坐标点按可靠**；键盘弹出会推动布局，坐标要重新 dump。
- 后台化（按 Home）会触发 Activity 状态保存 → 路由缺 `@Serializable` 会崩（已修，见 3.3.7）。
- 覆盖安装**可能丢**「所有文件访问」与通知权限；**清空数据的新安装**会把隐私政策重置为"未同意"（此后剪贴板识别与更新检查都不会执行）。

## 二、本仓库的界面原则（精简 + 人性化）

- **入口按"用户要办的事"分**：设置 = 下载 / 外观 / 数据（含问题反馈）/ 关于，四个分组；
- **功能按"能不能被包含"合**：能合就一条路、一个页、一份实现（同类列表共用一个页面）；
- **文案**：主句 ≤18 字，长解释收进 ⓘ；术语统一说"**下载**"（不用"缓存/交付/成品"）；空态要告诉用户可以做什么；
- **别让用户点两下**：卡片整块可点（不要"前往"）；"需要处理"的项后面直接给动作按钮。

## 三、既定路线（2026-10 的 A/B/C 重构，未完成前不要另起炉灶）

- **A**：删「版本信息」孤儿页（信息已并入「问题反馈」）；工具列表与首页卡片合一；存储管理的"目录文件/孤儿文件"并入下载管理的重复检查；全量文案/术语/空态规则。
- **B**：4 个用户列表页（点赞/追番/历史/作品）合成一个通用列表页；3 个登录路由合成一个「登录」页两个 Tab；两个错误页抽成通用提示页。
- **C**：设置分组折叠（~20 行 → 6~7 行）+ 合并"缓存配置/缓存目录/存储管理"为「下载与缓存」+ 「线路配置」并入下载组；首页主输入智能分流。
- **已定结论**：`ComplaintScreen` 三个按钮 `onClick = { }` 是空实现（死壳）→ **删除**；`WebParserScreen` 是脆弱的 WebView 嗅探 → **保留为独立工具，不并进主输入**。

### 进度：A/B/C 重构（截至 2026-10-05）

**已完成（已提交）**
- A-① 删除两个死页面：`ComplaintScreen`（三个按钮 `onClick = { }` 空实现）与 `AppVersionInfoScreen`（内容已并入「问题反馈」），连带两条路由与其设置入口；
- A-② 「关于」页新增**「检查更新」**按钮（同样遵守隐私门槛；结果写进 `download-trace.log` 的 `[更新检查]` 行，便于真机核验）；
- A-⑤（部分）4 处长文案缩短（`duplicate_check_hint` / `line_config_info` / `home_no_cache_task` / `home_download_dir_hint`，均保留 `%1$s` 占位符不动调用点）；
- A-④（部分）下载管理的「重复检查」卡片改为**只在真有重复时渲染**（`if (duplicateGroups.isNotEmpty())`）。

**本轮已完成（2026-10-05，均已推送、CI 真绿；真机复验见下一节 —— 除 #4 的四个登录态入口与
#6 的条件触发页外都已验过，因此还没发版）**

> 判据：CI run `37259406657` / 目录清单修复 `37260591580`，`Run unit tests` ✓ / `Assemble alphaRelease` ✓ /
> **`Fallback to alphaDebug` = skipped** ✓ / `Upload APKs` ✓。

1. ~~工具卡片去掉「前往」二次点击~~ —— 已查证不成立（`Surface(onClick)` 整块可点），已划掉；
2. **#2 存储「下载目录文件/孤儿文件」并入下载管理** —— `4ba2747b`：共享对话框
   `ui/download/DownloadDirFilesDialog.kt`；下载页首屏卡片改为**常驻**的「下载目录检查」
   （`DuplicateFilesCard` → `DownloadDirCheckCard`，两个入口：清理重复 / 列出目录文件）；
   目录清单改扫**新目录 + 旧目录**（与重复检查同源）；抽纯规则 `DownloadDirFilesRules` + 5 个 JVM 单测；
   存储管理页只留"空间占用 / 清缓存 / 权限"。轨迹新增 `[目录文件] 列出 N 个文件…` 与
   `[目录文件] 删除成功|失败 名称=…`（真机核验就靠这两行）。
3. **#3 设置分组折叠** —— `cd792b49`：新增 `CollapsibleCategorySettingsItem`（core:ui）+ 四组
   下载 / 外观 / 数据 / 关于（下载组默认展开，展开状态 `rememberSaveable`）；
   合并「缓存配置+缓存目录+存储管理」→ 一行「下载与缓存」；合并限速+分片并发 → 「下载速度」一个弹窗；
   合并备份+恢复 → 一个弹窗；删掉 9 条因此失效的 `setting_*` 死文案。
4. **#4 五个用户列表页合一** —— `8bf7a51d` + 编译修复 `f27293af`：`ui/user/list/`
   （`UserListSource` 枚举 + `UserListRoute` + 通用 PagingSource/ViewModel/Screen）；
   「我的」的投稿/追番/点赞/投币/历史五个入口共用一个页面；删 4 套 Screen+ViewModel 与 2 个 PagingSource；
   导航 4 个 entry → 1 个；抽纯规则 `UserListKeyRules` + 6 个 JVM 单测（守 2026-09-15 H11 撞 key 崩溃）。
5. **#5 三个登录路由合一** —— `f27293af` + 语言包修复 `e910a1ff`：一个「登录」页两个 Tab（扫码 / Cookie）；
   原介绍页的"登录后可用 / 协议 / 特别说明"收进右上角 ⓘ；未登录时设置「关于」组多一行「登录」。
6. **#6 两个错误页抽通用组件** —— `e910a1ff`：新增 `widget/ASMessagePage.kt`
   （标题 / 图标 / 正文 / 页脚 / 按钮**全部参数化**），`PlayVoucherErrorPage` 与
   `RequestFrequentScreen` 的默认态改用它，页面本身不再有排版代码。
7. **#7 首页主输入智能分流** —— `e910a1ff`：首页输入框从"点一下就跳解析页的空壳"改成**真能输入**；
   认出 B 站内容（BV / av / ep / ss / 短链 / 用户空间，判据复用 `AsRegexUtil`）→ **直接带进解析页解析**；
   认不出来的不猜，照旧打开解析页。`WebParserScreen` 仍为独立工具，**不并入**主输入。
8. **#8 `SystemExpandRoute` 处置** —— `e910a1ff`：查证就是空壳（Shizuku 开关 `checked = false`、
   `onCheckedChange` 空实现，与已被删的 `ComplaintScreen` 同类）→ 删页面 / 路由 /
   `onToSystemExpand` 参数 / 3 条专属文案。

### 真机复验记录（2026-10-05，装 `e910a1ff` 出的 alphaRelease）

安装证据：`base.apk` sha256 `c6231552…`（38,196,563 B，mtime 11:35:56 +0800）。

- **#3（设置四组折叠）✓**：首屏 = 下载组（8 行：下载与缓存[显示 Download/BDT]、命名规则、线路配置、
  解析平台、下载速度[限速：不限速 · 并发：8]、多线程分片下载、仅 Wi-Fi、跳过已下载）+
  外观/数据/关于三个折叠标题；点「外观」展开出动态主题+首页排版、点「数据」出问题反馈/自动解析/前台通知、
  点「关于」出关于/隐私政策/登录/Github仓库 ✓；「下载速度」弹窗里限速 + 并发数在**同一个弹窗** ✓；
  「备份与恢复」弹窗里备份/恢复两个动作（各带说明）✓。
  ⚠️ 用文本点按时小心**子串匹配**：点「数据」会命中"移**动数据**…"那句描述、点「下载与缓存」会命中
  分组描述的"下载与缓存、命名规则…"——这种要点坐标（从最新 dump 取可点区域的中心）。
- **#5（登录页两 Tab）✓**：顶栏「登录」+ 返回 + ⓘ「登录说明」（弹窗保留原介绍页的
  登录后可用/协议/特别说明）+ TabRow「B站 扫码登录」「使用Cookie登录」，两 Tab 互切都生效；
  设置未登录时「关于」组里有「登录」行，点进去是同一个页面 ✓。
- **#7（首页主输入分流）✓**：首页输入框可输入 + 右侧「解析输入内容」按钮；
  输入 `BV1xx411c7mD` 点按钮 → 直接进「视频解析」且已开始解析；输入 `hello` → 进解析页但输入为空、不猜 ✓。
- **#8 ✓**：设置里不再有「扩展能力」/「高级」分组（页面、路由、参数、文案均已删）。
- **#2 存储侧 ✓ / 下载侧入口 ✓ / 目录清单 ✗→已修→再复验 ✓**
  - 存储管理（从「下载与缓存」进入）只剩 空间占用环 + 音视频文件 + 所有文件访问 + 临时文件 + 核心文件，
    旧的「下载目录文件」卡片已消失 ✓；
  - 下载管理首屏常驻卡片有两个入口（检查重复下载文件 / 下载目录文件）✓；
  - **真机发现的 bug**：清单里只有"目录自己那一行"（`BDT`、0 B、无记录），真实文件一个都没列出。
    原因（预存在，被本轮挪到主路径后暴露）：`listDownloadFiles` 先去掉 `Download/` 再拼 `%` 得到 `BDT%`，
    而 `RELATIVE_PATH` 实际是 `Download/BDT/xxx.mp4` → 一条真实文件都匹配不到；
    只有 `_data LIKE '%/BDT%'` 命中了那个文件夹行。
    已修：`feaae1cf`（完整相对路径 + 剔目录行 + 3 个 JVM 单测）+ `cf1ae8ca`（目录行判据放宽为
    "0 B 且名字等于被查目录名" —— 真机上那行走的是 `_data`，其 `RELATIVE_PATH` 是父目录 `Download/`），
    CI run `37260591580` 真绿。
  - 修复后真机复验（新包 sha256 `9968ac02…`）：造了 `/sdcard/Download/BDT/zz-dirlist-test.txt` →
    清单列出「zz-dirlist-test.txt / 0 B · 无记录（孤儿）」✓；点「删除」→ 行消失、弹窗变空态
    「目录里还没有文件」、轨迹 `[目录文件] 删除成功 名称=zz-dirlist-test.txt`、磁盘上文件真的没了 ✓。
- **#4 部分 ✓（合并页本体）**：从「解析页 → UP 主 → 更多投稿」进到合并后的列表页：
  标题「投稿」+ 搜索框「搜索投稿」+ 两列视频网格 + 上一页数据加载 ✓（= WORK 来源）。
  点赞/投币/追番/历史四个入口在"我的（登录态）"里 —— **需要登录后再验**。
- **#6 未复验（触发条件苛刻）**：两个错误页要服务端返回"大会员凭证失效 / 请求过于频繁"才会出现；
  代码层已抽成 `widget/ASMessagePage.kt` 由两页共用（编译 + CI 通过）。
- **crash.log**：整套流程（首页/设置/各弹窗/登录页两 Tab/下载管理+清单+删除/解析页）跑完，
  `logs/` 里仍然只有 `download-trace.log` → **0 条新增** ✓。

**剩下的一步**：用户登录后验 #4 的其余四个入口（点赞/投币/追番/历史）→ bump `334 / 3.3.9` →
推 main → tag `v3.3.9` → Release 出签名 APK（#6 属"条件触发"页，随版本一起交付即可）。

### 本轮踩到的坑（CI 相关，都留了记录）

- **删 string 必须同时删所有语言包**：只删 `values/` + `values-en/` 时，`values-ur` 等 10 个语言包
  仍留着同一个 key → `lintVitalAlphaRelease` 报 `ExtraTranslation`（本轮 20 个）→ **release 构建失败**。
  又因为工作流里 `Assemble alphaRelease` 带 continue-on-error、后面接一个"debug 兜底"步骤，
  表现形式是 **`Fallback to alphaDebug = success`（而不是 skipped）** —— 这正是"不能只看
  Assemble alphaRelease"那条判据要拦的情况。删 key 后务必扫一遍所有 `values-*/strings.xml`。
- **`push_via_api.py --stage-all` 会推工作区的全部改动**：本轮有一次只想推 #4 的编译修复，
  结果把当时正在改的 #5 一起推了（两批混进一个提交）。**推送前先看完整的"有差异文件"清单**。
- **两处 Kotlin 细节**（各让 CI 红过一次）：`Modifier.animateItem()` 是 `Lazy*ItemScope` 的
  **成员扩展**，只能在 `items { }` 的 itemContent 里调用；`LikeAndCoinItemData` 是
  `network.model.user` 下的**顶层类**，不是 `BILIUserVideoLikeInfo` 的嵌套类。
- `tools/fetch_ci_apk.py` 原来取 `artifacts[0]`，可能取到 11 KB 的 `room-schema`；
  已改成取**体积最大**的 artifact（APK 永远是最大的）。

### 剩余条目的可执行锚点（2026-10-05 实测定位，动手即可）

> **2026-10-05 更新：#1~#8 已全部处理完**（见上一节"本轮已完成"）。下面保留原始锚点位置，只做回溯用，
> 不用再按它动手；下一个动作是"用户装包 → 真机复验 → 发版"。

> 说明：#1「工具卡片去前往」**已查证不成立** —— 工具卡片是 `Surface(onClick)` 整块可点，
> 全仓没有裸"前往"字符串（只有 `update_go_download`＝前往下载、`roam_go_login`＝前往登录，均保留）。**已划掉**。

**#2 存储"目录文件/孤儿文件"并入下载管理**（同源：都按名字扫下载目录）
- 存储侧（`ui/setting/storage/StorageManagementScreen.kt`）：状态 `showLocalFilesDialog`(:163)、`localFiles`(:164)；
  卡片 `title = R.string.download_dir_files`(:261) → `fileOutputManager.listDownloadFiles()`(:268) → 打开对话框(:277-301，
  内含删除项 `localFiles.remove(file)`(:301)）；对话框实现 `private fun LocalFilesDialog(`(:~320 之后)
- 下载侧（`ui/download/DownloadScreen.kt`）：`duplicateGroups`(:179)、`showDuplicateDialog`(:183)、
  `DuplicateFilesCard(...)`(:316，已按需渲染)、`onClean = { showDuplicateDialog = true }`(:321)、对话框渲染(:417-424)、
  `private fun DuplicateFilesCard(`(:450)
- 做法：把 `LocalFilesDialog` 提到共享位置并改为可复用（`internal`/移入 `ui/download/`），
  在 `DuplicateFilesCard` 里加第二个入口（如"列出目录文件"）指向它；随后**删除存储侧那张卡片与状态**，
  存储页只留"空间占用 + 清缓存"。

**#3 设置分组折叠**（`ui/setting/SettingScreen.kt`，`fun SettingScreen(`(:100) 起）
- 目标四组：下载（下载与缓存＝合并 `缓存配置`+`缓存目录`+`存储管理`、命名规则、线路＝`线路配置`+`解析平台`、速度＝限速+分片并发）
  / 外观（主题色、首页排版）/ 数据（备份与恢复合一、问题反馈）/ 关于（版本+检查更新、隐私政策、账户、许可）
- 现成积木：`BaseSettingsItem(`（各行都在用）、分组标题形如 `Text("高级")`(:~2152 区域)；回调已在 `SettingScreen(` 参数里
  （`onToStorageManagement`/`onToNamingConvention`/`onToLineConfig`/`onToSystemExpand`/`onToLayoutTypeset`…）

**#4 四个用户列表页合一**：`LikeVideoRoute`、`BangumiFollowRoute`、`UserPlayHistoryRoute`、`WorkListRoute`
  → 抽象一个通用列表页（来源枚举 + 分页），并改「我的」页四个入口指向它（`ui/user/**`）

**#5 三个登录路由合一**：`LoginNavigation.kt` 里的 `LoginRoute`/`QRCodeLoginRoute`/`CookeLoginRoute`
  → 合成一个「登录」页两个 Tab（扫码 / Cookie）；设置里「账户」行对齐

**#6 两个错误页合一**：`PlayVoucherErrorNavigation.kt`、`RequestFrequentScreen.kt` → 抽通用提示页组件（文案/按钮参数化）

**#7 首页主输入智能分流**：`ui/home/**` 的输入框 → B 站链接/番剧/短链直接解析；
  普通网页仍走独立 `WebParserScreen`（已定：其 WebView 嗅探脆弱，不并入主输入）

**#8 `SystemExpandRoute` 处置**：`ui/setting/expand/SystemExpandScreen.kt`（已补 `@Serializable`）
  → 先 `grep -rn "SystemExpandRoute" --include=*.kt`（当前离线点：设置"高级"里一行 + 导航 entry），
  决定收进折叠区或删除；删除时连 `onToSystemExpand` 参数一起清理
