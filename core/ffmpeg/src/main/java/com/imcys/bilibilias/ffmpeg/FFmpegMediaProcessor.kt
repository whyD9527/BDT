package com.imcys.bilibilias.ffmpeg


/**
 * `IMediaProcessor` 的**封装层（MediaMuxer 实现）**。
 *
 * ⚠️ 2026-10-01 复审（core:ffmpeg 模块，当前未进构建）：
 * 原来这里是 `suspendCancellableCoroutine { cont -> }` —— **一个空体**。
 * `suspendCancellableCoroutine` 不会自己恢复，于是**任何调用方都会永久挂起**
 * （协程一直停在挂起点，既不返回也不报错；界面表现为"合并中…"永远不动）。
 * 这类"空挂起点"是最难查的一种死锁：编译通过、单测不报错、只有真机走到才发现。
 *
 * 现在直接委派给 [MediaMergeManager]（同一模块里真正干活的实现），
 * 并把 `ProcessorListener` 适配过去 —— 两个监听器接口字段相同、只是声明位置不同。
 */
class FFmpegMediaProcessor : IMediaProcessor {
    override suspend fun mergeVideoAndAudioSuspend(
        videoPath: String,
        audioPath: String,
        outputPath: String,
        listener: IMediaProcessor.ProcessorListener
    ): Result<String> = MediaMergeManager.mergeVideoAndAudioSuspend(
        videoPath = videoPath,
        audioPath = audioPath,
        outputPath = outputPath,
        listener = object : MediaMergeManager.MediaMergeListener {
            override fun onProgress(progress: Int) = listener.onProgress(progress)
            override fun onError(errorMsg: String) = listener.onError(errorMsg)
            override fun onComplete() = listener.onComplete()
        },
    )
}
