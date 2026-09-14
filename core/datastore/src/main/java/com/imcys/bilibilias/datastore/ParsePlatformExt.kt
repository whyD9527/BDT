package com.imcys.bilibilias.datastore

import com.imcys.bilibilias.datastore.AppSettings.VideoParsePlatform

/**
 * 「解析平台」的统一判定。
 *
 * 为什么要有这个文件：
 * 语义其实只有一句「是不是 TV」，但原先在 6 处各写一遍，而且分组写法还不一致 ——
 * 有的地方写 `Web, Mobile, UNRECOGNIZED ->`，换个文件又写成
 * `Mobile, UNRECOGNIZED, Web ->`，读起来像是不同规则，其实完全等价。
 *
 * 收敛到这里的收益：**以后新增一个平台值（比如某种新客户端），
 * 只需要改这一个文件**，不必再去 6 个地方逐个核对有没有漏。
 */
val VideoParsePlatform.isTv: Boolean
    get() = this == VideoParsePlatform.TV

val VideoParsePlatform.isWeb: Boolean
    get() = this == VideoParsePlatform.Web

val VideoParsePlatform.isMobile: Boolean
    get() = this == VideoParsePlatform.Mobile
