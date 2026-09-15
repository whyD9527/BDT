package com.imcys.bilibilias.data.download.execution

/**
 * 「内嵌音轨 / 字幕 / 封面该不该映射」的纯规则（可单测）。
 *
 * ## 背景（2026-09-14 全量审计 H3/H4）
 * `FfmpegCommandBuilder` 生成 `-map` 时有两个洞，都属于"**ffmpeg 一旦用了 `-map`
 * 就只输出被映射的流**"这一条的后果：
 *
 * **H3 —— durl 单文件的音轨又被丢掉。** 第二十轮曾经为它补过 `-map 0:a:0?`，
 * 但那行被写在 `if (audioEnabled)` 里面；而 `audioEnabled = mode in (AUDIO_ONLY, AUDIO_VIDEO)`，
 * **「仅视频」时它是 false** —— 补映射根本不执行。durl 是渐进式单文件，
 * 音轨就在同一个输入里、没有独立的音频输入，于是命令只剩 `-map 0:v:0` → 无声视频 + COMPLETED。
 *
 * **H4 —— 纯音频容器里硬塞字幕/封面。** 字幕映射用 `-map ${mediaInputs.size}:s:0`，
 * 而"仅音频"时 `mediaInputs` 里是音频文件，这个下标指向的**是音频流**；
 * 同时 `-c:s` 只在 `videoEnabled` 时才给。结果是 mp3/m4a 容器 + 无编码器的字幕流
 * → ffmpeg 必然非 0 退出 → 这一集**永远下不下来**。
 * 本该拦住它的 `MediaContainer.canEmbedSubtitle()/canEmbedCover()`
 * 在整个仓库里**只有定义、零调用**。
 *
 * 本规则把这三件事都变成可测的布尔判断，命令构建器只负责照着拼参数。
 */
object EmbedStreamMappingRules {

    /**
     * 是否要补 `-map 0:a:0?`（把单文件里的内嵌音轨也映射出来）。
     *
     * - `mediaInputCount == 1`：只有一个输入文件（durl / 单流资源）；
     * - `hasSeparateAudioInput == false`：没有独立的音频子任务，音轨只可能在那个文件里。
     *
     * 注意**不看** `downloadMode`：只要"仅视频 + 单文件"，就必须把音轨带上 ——
     * 不带的结果是无声视频，比多带一条音轨糟糕得多。末尾的 `?` 保证"没有音轨也不报错"。
     */
    fun shouldMapEmbeddedAudio(
        mediaInputCount: Int,
        hasSeparateAudioInput: Boolean,
    ): Boolean = mediaInputCount == 1 && !hasSeparateAudioInput

    /**
     * 是否允许把字幕流映射进输出。
     *
     * 需要容器真的支持内嵌字幕（调用方传 `MediaContainer.canEmbedSubtitle()`），
     * **并且**有视频轨（纯音频容器基本没有字幕编码器，硬塞必然让整次合并失败）。
     */
    fun canMapSubtitles(
        containerSupportsSubtitle: Boolean,
        videoEnabled: Boolean,
    ): Boolean = containerSupportsSubtitle && videoEnabled

    /**
     * 是否允许把封面图映射进输出。
     *
     * 与字幕同理，但**不要求** `videoEnabled`：m4a 这类音频容器是可以带封面（APIC）的，
     * `MediaContainer.M4A.canEmbedCover()` 就是 true。是否真的能装下由容器的声明决定。
     */
    fun canMapCover(containerSupportsCover: Boolean): Boolean = containerSupportsCover
}
