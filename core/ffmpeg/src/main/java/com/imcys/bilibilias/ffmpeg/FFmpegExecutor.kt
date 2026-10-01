package com.imcys.bilibilias.ffmpeg

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private fun getExecutorConcurrent(): Int {
    val processors = Runtime.getRuntime().availableProcessors()
    return when {
        processors in 1..3 -> 1
        else -> (processors / 2)
    }
}

object FFmpegExecutor {

    private val currentTasks = AtomicInteger(0)
    private val maxConcurrentTasks = getExecutorConcurrent()
    private var threadId = 0
    private val executor: Executor = Executors.newFixedThreadPool(maxConcurrentTasks) {
        Thread(it, "FFmpegThread-${threadId++}").apply { isDaemon = true }
    }

    suspend fun <T> executeFFmpeg(block: () -> T): T {
        return suspendCancellableCoroutine {
            val task = wrapTask(it, block)
            currentTasks.incrementAndGet()
            executor.execute(task)
        }
    }

    private fun <T> wrapTask(con: CancellableContinuation<T>, block: () -> T): Runnable {
        return Runnable {
            try {
                val result = block()
                // ⚠️ 2026-10-01 复审（core:ffmpeg 模块，当前未进构建）：协程被取消之后
                // **不能再 resume** —— 原来的 `con.resumeWith(...)` 无条件调用，
                // 会把结果塞进一个已经取消的续体。任务本身仍会跑完（block 是同步的
                // muxer/ffmpeg 调用，没法真正中断），所以这里只保证"不向已取消的协程交付结果"，
                // 计数照旧在 finally 里减一次（不会重复减）。
                if (con.isActive) {
                    con.resumeWith(Result.success(result))
                }
            } catch (e: Exception) {
                if (con.isActive) {
                    con.resumeWith(Result.failure(e))
                }
            } finally {
                currentTasks.decrementAndGet()
            }
        }
    }

    val pendingTaskCount: Int
        get() = 0.coerceAtLeast(currentTasks.get() - maxConcurrentTasks)
}