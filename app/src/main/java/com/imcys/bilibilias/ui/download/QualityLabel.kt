package com.imcys.bilibilias.ui.download

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.imcys.bilibilias.R

/**
 * F（2026-10-02 真机复验的尾巴）：画质描述（如 `480P 清晰`）来自
 * `core/network` 里"画质 id → 中文描述"的映射，并**写进了 DB**（`quality_description`），
 * 卡片直接显示 → 英文界面下就漏出 `清晰`。
 *
 * DB 里没有画质列（见 `SegmentIdentityRules` 的注释），所以只能在**显示层**把后缀本地化：
 * 把 `分辨率 + 空格 + 后缀` 拆开，分辨率原样（`480P`/`8K` 这类专有名词不译）、后缀走资源；
 * 认不出来的**原样返回**（宁可漏译一个也不显示错）。
 */
@Composable
fun localizeQualityLabel(raw: String?): String? {
    if (raw.isNullOrBlank()) return raw
    val idx = raw.lastIndexOf(' ')
    if (idx <= 0) return raw
    val resolution = raw.substring(0, idx)
    val suffixRes = when (raw.substring(idx + 1)) {
        "清晰" -> R.string.quality_suffix_clear
        "高清" -> R.string.quality_suffix_hd
        "准高清" -> R.string.quality_suffix_hq
        "超高清" -> R.string.quality_suffix_uhd
        "蓝光" -> R.string.quality_suffix_bluray
        "杜比" -> R.string.quality_suffix_dolby
        "流畅" -> R.string.quality_suffix_smooth
        else -> null
    } ?: return raw
    return "$resolution ${stringResource(suffixRes)}"
}
