package com.imcys.bilibilias.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.Bitmap
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ASFramePlayer(modifier: Modifier, list: List<Bitmap>, fps: Int) {
    if (list.isEmpty()) return
    val totalFrames = list.size
    // ⚠️ fps 必须夹到 ≥1（2026-09-15 复审 L11）：上游在"平均帧率解析为 0"时会把值夹到 0，
    // 于是 `1000L / fps` 直接 ArithmeticException 崩在协程里；顺便把时长改成浮点除法
    // （原来 `totalFrames / fps` 先整除再乘 1f，100 帧 30fps 会显示 3 秒而不是 4 秒）。
    val safeFps = fps.coerceAtLeast(1)
    val duration = ceil(totalFrames / safeFps.toFloat()).toInt()
    var currentFrame by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(true) }
    var isDragging by remember { mutableStateOf(false) }

    // 自动播放逻辑
    LaunchedEffect(list, isPlaying, isDragging) {
        while (isPlaying && !isDragging) {
            delay(1000L / safeFps)
            currentFrame = (currentFrame + 1) % totalFrames
        }
    }

    val valueRange = 0f..(totalFrames - 1).toFloat()

    Box(modifier = modifier) {
        Image(
            bitmap = list[currentFrame].asImageBitmap(),
            contentDescription = "帧图片",
            modifier = Modifier.fillMaxSize()
        )
        Text(
            "${currentFrame + 1} / $totalFrames",
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 10.dp, top = 10.dp),
            color = MaterialTheme.colorScheme.primary
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 10.dp)
        ) {
            // 进度条
            Slider(
                value = currentFrame.toFloat(),
                onValueChange = {
                    isDragging = true
                    currentFrame = it.toInt().coerceIn(0, totalFrames - 1)
                },
                onValueChangeFinished = {
                    isDragging = false
                },
                valueRange = valueRange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp),
                track = { positions ->
                    val fraction = (positions.value - valueRange.start) /
                            (valueRange.endInclusive - valueRange.start)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.onPrimary)
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                },
                thumb = { },
            )
            // 进度显示
            Text(
                text = "${((currentFrame + 1) / fps.toFloat()).format1()}s / ${duration}s",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// 保留一位小数的扩展函数
private fun Float.format1(): String = String.format(Locale.US, "%.1f", this)
