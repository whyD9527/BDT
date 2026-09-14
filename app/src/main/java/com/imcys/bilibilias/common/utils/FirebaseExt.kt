package com.imcys.bilibilias.common.utils

/**
 * 埋点接口的空实现。
 *
 * 原实现把登录方式、解析过的视频/番剧 ID 等上报到 Firebase Analytics，
 * 而 Firebase 项目属于原仓库作者。本项目已彻底移除 Firebase 及一切统计上报，
 * 这里保留同名空函数，使各调用点无需改动，同时确保不会再产生任何外部请求。
 */
object FirebaseExt {
    fun logLogin(method: String) = Unit

    fun logVideoParse(
        bvId: String?,
    ) = Unit

    fun logBangumiParse(
        epId: Long? = null,
        ssId: Long? = null,
    ) = Unit
}
