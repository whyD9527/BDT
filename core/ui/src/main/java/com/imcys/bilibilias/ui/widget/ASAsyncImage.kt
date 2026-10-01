package com.imcys.bilibilias.ui.widget

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope.Companion.DefaultFilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter.Companion.DefaultTransform
import coil3.compose.AsyncImagePainter.State
import coil3.compose.LocalPlatformContext

@Composable
fun ASAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    transform: (State) -> State = DefaultTransform,
    onState: ((State) -> Unit)? = null,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Crop,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
    filterQuality: FilterQuality = DefaultFilterQuality,
    clipToBounds: Boolean = true,
    onClick: () -> Unit,
) {
    if (!LocalInspectionMode.current) {
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.primaryContainer,
            onClick = onClick
        ) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                imageLoader = SingletonImageLoader.get(LocalPlatformContext.current),
                modifier = modifier,
                transform = transform,
                onState = onState,
                alignment = alignment,
                contentScale = contentScale,
                alpha = alpha,
                colorFilter = colorFilter,
                filterQuality = filterQuality,
                clipToBounds = clipToBounds,
            )
        }
    } else {
        Surface(
            modifier,
            color = MaterialTheme.colorScheme.primary,
            shape = CardDefaults.shape
        ) {
        }
    }
}

@Composable
fun ASAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    transform: (State) -> State = DefaultTransform,
    onState: ((State) -> Unit)? = null,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Crop,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
    filterQuality: FilterQuality = DefaultFilterQuality,
    clipToBounds: Boolean = true,
) {
    if (!LocalInspectionMode.current) {
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.primaryContainer,
            // ⚠️ 同一个 `modifier` 不能再原样传给下面的 AsyncImage（2026-10-01 复审 L33）：
            // 里面的 `padding`/`border`/`size` 会在 Surface 与图片上**各生效一次**
            // （视觉上 padding 翻倍、边框叠两层、尺寸被约束两次），
            // 调用方看到的效果与传进去的 modifier 对不上。这里只让外层生效。
            modifier = modifier.alpha(alpha)
        ) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                imageLoader = SingletonImageLoader.get(LocalPlatformContext.current),
                modifier = Modifier.fillMaxSize(),
                transform = transform,
                onState = onState,
                alignment = alignment,
                contentScale = contentScale,
                alpha = alpha,
                colorFilter = colorFilter,
                filterQuality = filterQuality,
                clipToBounds = clipToBounds,
            )
        }
    } else {
        Surface(
            modifier,
            color = MaterialTheme.colorScheme.primary,
            shape = CardDefaults.shape
        ) {
        }
    }
}