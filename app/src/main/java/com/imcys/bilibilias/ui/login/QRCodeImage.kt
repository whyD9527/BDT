package com.imcys.bilibilias.ui.login

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 在本地生成二维码位图。
 *
 * 原实现是把二维码内容拼进 `https://pan.misakamoe.com/qrcode/?url=...` 交给第三方服务器
 * 渲染成图片。而 B 站扫码登录返回的 url 里带有 `qrcode_key`，该值正是轮询换取登录 Cookie
 * 的凭据，等同于登录口令。把它发给第三方服务器，意味着对方可以同步轮询并在用户确认登录的
 * 瞬间拿到登录态。因此改为完全在本地渲染，不再产生任何外部请求。
 */
fun generateQRCodeBitmap(content: String, size: Int = 640): Bitmap? {
    if (content.isBlank()) return null
    return runCatching {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }.getOrNull()
}

/**
 * 本地渲染的二维码图片，用法与 ASAsyncImage 类似，但不发起网络请求。
 */
@Composable
fun ASLocalQRCode(
    content: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(content) { content?.let { generateQRCodeBitmap(it) } }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    }
}
