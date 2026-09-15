package com.imcys.bilibilias.data.download.execution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内嵌流映射决策的测试。
 *
 * 守的是 2026-09-14 全量审计的 H3/H4：
 * - H3：durl 单文件 + 仅视频时，补音轨的 `-map 0:a:0?` 挂在 `audioEnabled` 下 → 从不执行 → 无声视频；
 * - H4：纯音频容器里硬塞字幕/封面 → ffmpeg 必然失败，那一集永远下不下来。
 */
class EmbedStreamMappingRulesTest {

    @Test
    fun `单文件且没有独立音频输入时要补内嵌音轨（H3）`() {
        assertTrue(
            EmbedStreamMappingRules.shouldMapEmbeddedAudio(
                mediaInputCount = 1,
                hasSeparateAudioInput = false,
            ),
        )
    }

    @Test
    fun `有独立音频输入时不要重复补 0-a（音轨已经从音频文件映射了）`() {
        assertFalse(
            EmbedStreamMappingRules.shouldMapEmbeddedAudio(
                mediaInputCount = 2,
                hasSeparateAudioInput = true,
            ),
        )
    }

    @Test
    fun `多输入单文件场景不补（下标 0 未必是带音轨的那个）`() {
        assertFalse(
            EmbedStreamMappingRules.shouldMapEmbeddedAudio(
                mediaInputCount = 2,
                hasSeparateAudioInput = false,
            ),
        )
    }

    @Test
    fun `纯音频容器不允许映射字幕（H4）`() {
        // mp3 / m4a 的音轨里没有字幕流，硬塞会让整次合并失败
        assertFalse(
            EmbedStreamMappingRules.canMapSubtitles(
                containerSupportsSubtitle = false,
                videoEnabled = true,
            ),
        )
        assertFalse(
            EmbedStreamMappingRules.canMapSubtitles(
                containerSupportsSubtitle = false,
                videoEnabled = false,
            ),
        )
    }

    @Test
    fun `没有视频轨时不允许映射字幕（下标会指到音频流上）`() {
        assertFalse(
            EmbedStreamMappingRules.canMapSubtitles(
                containerSupportsSubtitle = true,
                videoEnabled = false,
            ),
        )
    }

    @Test
    fun `只有容器声明支持且确有视频轨才映射字幕`() {
        assertTrue(
            EmbedStreamMappingRules.canMapSubtitles(
                containerSupportsSubtitle = true,
                videoEnabled = true,
            ),
        )
    }

    @Test
    fun `封面只看容器声明（m4a 允许封面，这是它与字幕的区别）`() {
        assertTrue(EmbedStreamMappingRules.canMapCover(containerSupportsCover = true))
        assertFalse(EmbedStreamMappingRules.canMapCover(containerSupportsCover = false))
    }
}
