package com.imcys.bilibilias.data.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 从一条**不会自己结束**的流里读一次当前值。
 *
 * ## 为什么单独立一个函数（第二十四轮踩到的坑）
 * `NewDownloadManager.initDownloadList()` 原先写的是
 * `downloadTaskRepository.getSegmentAll().last()` —— 而 `getSegmentAll()` 是 Room 的
 * **热流**：它先发一次当前查询结果，之后只在表变化时再发，**永远不会 complete**。
 * `Flow.last()` 的语义是"等到流结束、取最后一个元素"，于是在这种流上它**永远挂住**：
 * 那段"启动清理未完成下载"的代码**一次都没执行过**（真机验证：重启后一行日志都没有）。
 *
 * 正确的读法是 `first()`：拿到当前值就返回。这里的坑不在于 `first()` 难写，
 * 而在于 `last()` **编译得过、单元测试（如果有的话）也可能过得去**，
 * 只有"真机上那段代码从没跑过"这种症状才会暴露它。
 *
 * 所以把它抽成一个有名有姓的函数：名字提醒后来者"这里是一次性读取"，
 * 并用 `FlowReadOnceTest` 把 `first()` 与 `last()` 的区别钉住。
 */
suspend fun <T> Flow<T>.readOnce(): T = first()
