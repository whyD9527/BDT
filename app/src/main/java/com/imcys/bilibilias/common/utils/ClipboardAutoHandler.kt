import com.imcys.bilibilias.common.utils.AsRegexUtil

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.imcys.bilibilias.data.clipboard.ClipboardHandlingRules
import com.imcys.bilibilias.datastore.AppSettings
import kotlinx.coroutines.delay

private const val CLIPBOARD_READ_DELAY_MS = 180L

/**
 * 进程内"上次已经处理过"的剪贴板文本。
 *
 * 用它做去重，**替代以前"识别成功就清空用户剪贴板"** —— 那种做法等于把用户复制的东西弄没了
 * （他还可能想粘到别处）。放在文件级（进程级）而不是 `remember`，是因为两个调用点
 * （首页 / 解析页）都要共用同一份去重状态，否则两屏各自都会处理一次。
 */
private var lastHandledClipboardText: String? = null

/**
 * 处理剪贴板自动识别。
 *
 * ⚠️ **隐私门槛只在这一处判定**：以前是两个调用点各写一遍 ——
 * 首页写 `agreePrivacyPolicy != Default`（"已拒绝"也会被读），解析页干脆没有门槛。
 * 现在一律走 [ClipboardHandlingRules.canHandleClipboard]：只有**明确同意**才读。
 */
@Composable
fun ClipboardAutoHandler(
    appSettings: AppSettings,
    onClipboardText: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val allowed by rememberUpdatedState(
        ClipboardHandlingRules.canHandleClipboard(
            privacyState = appSettings.agreePrivacyPolicy,
            autoHandlingEnabled = appSettings.enabledClipboardAutoHandling,
        )
    )
    val onClipboardTextState by rememberUpdatedState(onClipboardText)

    if (!allowed) return

    LaunchedEffect(lifecycleOwner, allowed) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            Log.d("TAG", "ClipboardAutoHandler: RESUMED")
            delay(CLIPBOARD_READ_DELAY_MS)
            val text = context.peekClipboardBiliText() ?: return@repeatOnLifecycle
            // 同一段文本只处理一次；**不动用户的剪贴板**
            if (!ClipboardHandlingRules.shouldHandleText(text, lastHandledClipboardText)) {
                return@repeatOnLifecycle
            }
            lastHandledClipboardText = text
            onClipboardTextState(text)
        }
    }
}

/**
 * 读剪贴板里那段"看起来是 B 站链接/ID"的文本；**只读、不改**。
 *
 * 认不出来的文本一律返回 null（不跳转、不提示），保持原有行为。
 */
private fun Context.peekClipboardBiliText(): String? {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = clipboard.primaryClip ?: return null
    val text = clip.getItemAt(0)
        .coerceToText(this)
        ?.toString()
        ?.trim()
        .takeIf { !it.isNullOrEmpty() }
        ?: return null

    if (AsRegexUtil.parse(text) == null) return null
    return text
}
