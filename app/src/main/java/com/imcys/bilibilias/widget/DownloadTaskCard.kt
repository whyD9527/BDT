package com.imcys.bilibilias.widget

import com.imcys.bilibilias.ui.download.localizeQualityLabel
import com.imcys.bilibilias.ui.download.downloadModeLabel
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.imcys.bilibilias.R
import com.imcys.bilibilias.common.utils.toHttps
import com.imcys.bilibilias.data.download.record.DownloadRecordDisplayRules
import com.imcys.bilibilias.database.entity.download.DownloadSegment
import com.imcys.bilibilias.database.entity.download.DownloadState
import com.imcys.bilibilias.download.AppDownloadTask
import com.imcys.bilibilias.ui.widget.ASAsyncImage
import com.imcys.bilibilias.ui.widget.ASIconButton
import com.imcys.bilibilias.ui.widget.ASTextButton
import kotlin.math.ceil
import kotlin.text.ifEmpty


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadTaskCard(
    modifier: Modifier = Modifier,
    task: AppDownloadTask,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onCancel: () -> Unit = {}
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CardDefaults.shape,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(10.dp)
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // 左侧图片
            Box(
                modifier = Modifier
                    .weight(0.3f)
                    .fillMaxHeight(),
            ) {

                ASAsyncImage(
                    "${
                        task.cover?.ifEmpty { task.downloadTask.cover }
                            ?.toHttps()
                    }@540w_404h",
                    modifier = Modifier
                        .fillMaxSize(),
                    shape = CardDefaults.shape,
                    contentDescription = stringResource(R.string.cd_cover_image)
                )
            }

            Spacer(Modifier.width(10.dp))

            // 右侧内容
            Column(
                modifier = Modifier
                    .weight(0.7f)
                    .fillMaxHeight()
                    .align(Alignment.CenterVertically), // 垂直居中
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = task.downloadSegment.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W400
                )
                Spacer(Modifier.height(12.dp))

                val animatedProgress by
                animateFloatAsState(
                    targetValue = task.progress,
                    animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                )

                when (task.downloadState) {
                    DownloadState.COMPLETED,
                    DownloadState.WAITING -> {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    DownloadState.PRE_TASK,
                    DownloadState.POST_TASK,
                    DownloadState.PAUSE,
                    DownloadState.MERGING,
                    DownloadState.DOWNLOADING -> {
                        LinearWavyProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                        )
                    }

                    DownloadState.ERROR -> {}
                    DownloadState.CANCELLED -> {}
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Text(
                        when (task.downloadState) {
                            DownloadState.WAITING -> stringResource(R.string.status_waiting)
                            DownloadState.PAUSE -> stringResource(R.string.status_paused)
                            DownloadState.DOWNLOADING -> stringResource(
                                R.string.status_downloading_percent,
                                ceil(animatedProgress * 100).toInt(),
                            )

                            DownloadState.MERGING -> stringResource(
                                R.string.status_merging_percent,
                                ceil(animatedProgress * 100).toInt(),
                            )

                            DownloadState.COMPLETED -> stringResource(R.string.status_completed)
                            DownloadState.ERROR -> stringResource(R.string.status_error)
                            DownloadState.CANCELLED -> stringResource(R.string.status_cancelled)
                            DownloadState.PRE_TASK -> stringResource(R.string.status_pre_task)
                            DownloadState.POST_TASK -> stringResource(R.string.status_post_task)
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.W400
                    )

                    Spacer(Modifier.weight(1f))

                    Surface(
                        shape = CardDefaults.shape,
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            text = downloadModeLabel(task.downloadSegment.downloadMode),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
                            style = TextStyle(
                                platformStyle = PlatformTextStyle(
                                    includeFontPadding = false
                                )
                            )
                        )
                    }

                    Spacer(Modifier.width(10.dp))


                    // 暂停时可取消任务
                    if (task.downloadState != DownloadState.DOWNLOADING) {
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.cd_cancel_download),
                            modifier = Modifier.clickable {
                                onCancel()
                            }
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    if (task.downloadState == DownloadState.DOWNLOADING || task.downloadState == DownloadState.PAUSE) {
                        Icon(
                            if (task.downloadState == DownloadState.DOWNLOADING) {
                                Icons.Outlined.Pause
                            } else {
                                Icons.Outlined.PlayArrow
                            },
                            contentDescription = if (task.downloadState == DownloadState.DOWNLOADING) {
                                stringResource(R.string.cd_pause_download)
                            } else {
                                stringResource(R.string.cd_resume_download)
                            },
                            modifier = Modifier.clickable {
                                if (task.downloadState == DownloadState.DOWNLOADING) {
                                    onPause()
                                } else {
                                    onResume()
                                }
                            })

                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadFinishTaskCard(
    modifier: Modifier = Modifier,
    downloadSegment: DownloadSegment,
    /** 记录里写了媒体路径、但文件已经不在（探测结果，见 DownloadViewModel.refreshMissingFiles） */
    fileMissing: Boolean = false,
    downloadFinishEditState: Boolean,
    selectDeleteList: SnapshotStateList<DownloadSegment>,
    onSelect: () -> Unit = {},
    onDeleteTaskAndFile: () -> Unit
) {

    var showDeleteDialog by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CardDefaults.shape,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // 左侧图片
            Column(
                modifier = Modifier
                    .weight(0.4f)
                    .aspectRatio(16f / 9f)
                    .fillMaxHeight(),
            ) {
                ASAsyncImage(
                    "${downloadSegment.cover?.toHttps()}",
                    modifier = Modifier
                        .fillMaxSize(),
                    shape = CardDefaults.shape,
                    contentDescription = stringResource(R.string.cd_cover_image)
                )
            }


            Spacer(Modifier.width(10.dp))

            // 右侧内容
            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight()
                    .align(Alignment.CenterVertically), // 垂直居中
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = downloadSegment.title,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W400
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // 标签由 DownloadRecordDisplayRules 决定：**没有媒体文件**的记录
                    // （只勾了封面/弹幕/字幕）不能再显示「音视频 / 未知画质 / mp4」——
                    // 那会让人以为视频已经下好了，点「打开」才发现文件不存在。
                    // 见交接文档第二十一轮的真机验证记录。
                    // ⚠️ 规则在纯模块（`:core:data`，没有 Android 资源）里只返回**码**，
                    // 文案在这里映射到 strings.xml（2026-10-02 本地化改造）。
                    DownloadRecordDisplayRules.tags(
                        modeTitle = downloadModeLabel(downloadSegment.downloadMode),
                        qualityTitle = localizeQualityLabel(downloadSegment.qualityDescription),
                        extension = downloadSegment.mediaContainer.extension,
                        savePath = downloadSegment.savePath,
                        fileMissing = fileMissing,
                    ).forEach { tag ->
                        val tagText = when (tag) {
                            is DownloadRecordDisplayRules.Tag.ExtrasOnly ->
                                stringResource(R.string.download_record_extras_only)

                            is DownloadRecordDisplayRules.Tag.MissingFile ->
                                stringResource(R.string.file_missing)

                            is DownloadRecordDisplayRules.Tag.UnknownQuality ->
                                stringResource(R.string.download_record_unknown_quality)

                            is DownloadRecordDisplayRules.Tag.Text -> tag.text
                        }
                        Surface(
                            shape = RoundedCornerShape(percent = 50),
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Text(
                                modifier = Modifier.padding(
                                    horizontal = 8.dp,
                                    vertical = 0.dp
                                ),
                                text = tagText,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.W400,
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .align(Alignment.CenterVertically), // 垂直居中
                verticalArrangement = Arrangement.Center
            ) {

                AnimatedContent(downloadFinishEditState) { state ->
                    if (!state) {
                        ASIconButton(onClick = {
                            showDeleteDialog = true
                        }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.cd_delete_download_task))
                        }
                    } else {
                        Checkbox(
                            checked = downloadSegment in selectDeleteList,
                            onCheckedChange = {
                                onSelect()
                            }
                        )
                    }
                }
            }
        }

        if (showDeleteDialog) {
            // 显示删除对话框
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text(stringResource(R.string.download_delete_confirm_title)) },
                text = { Text(stringResource(R.string.download_delete_single_message)) },
                confirmButton = {
                    ASTextButton(
                        onClick = {
                            onDeleteTaskAndFile()
                            showDeleteDialog = false
                        }
                    ) {
                        Text(stringResource(R.string.common_delete))
                    }
                },
                dismissButton = {
                    ASTextButton(
                        onClick = { showDeleteDialog = false }
                    ) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    }
}