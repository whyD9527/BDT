package com.imcys.bilibilias.common.utils.download

import com.imcys.bilibilias.network.model.video.BILIVideoCCInfo
import java.util.Locale


object CCJsonToSrt {

    fun jsonToSrt(videoCCInfo: BILIVideoCCInfo): String {
        val srtBuilder = StringBuilder()
        videoCCInfo.body.forEachIndexed { index, it ->
            val startTime = formatSrtTime(it.from)
            val endTime = formatSrtTime(it.to)
            srtBuilder.append("${index + 1}\n")
            srtBuilder.append("$startTime --> $endTime\n")
            srtBuilder.append("${it.content}\n\n")
        }
        return srtBuilder.toString().trim()
    }

    private fun formatSrtTime(seconds: Double): String {
        // ⚠️ 负数 / NaN / 超大值都要先夹住（2026-09-15 复审 F7）：
        // 接口给的 `from`/`to` 是 Double，负数会算出 `00:00:00,-500` 这种非法时间戳
        // （`secs - secs.toInt()` 对负数得负毫秒），整份字幕会被播放器判为格式错误。
        val safeSeconds = if (seconds.isNaN() || seconds < 0) 0.0 else seconds
        val hours = (safeSeconds / 3600).toInt()
        val minutes = ((safeSeconds % 3600) / 60).toInt()
        val secs = safeSeconds % 60
        val millis = ((secs - secs.toInt()) * 1000).toInt()
        // ⚠️ Locale.ROOT（2026-09-15 复审 A-M8）：默认 Locale 下 `%d` 在
        // ar/fa 等区域会输出本地数字、逗号小数点区域会破坏 `hh:mm:ss,mmm` 格式。
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d,%03d",
            hours,
            minutes,
            secs.toInt(),
            millis,
        )
    }
}
