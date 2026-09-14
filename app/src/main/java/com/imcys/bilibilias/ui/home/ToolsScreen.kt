package com.imcys.bilibilias.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CopyAll
import androidx.compose.material.icons.outlined.VideoCameraBack
import androidx.compose.material.icons.outlined.WebAsset
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import com.imcys.bilibilias.BuildConfig
import com.imcys.bilibilias.R
import com.imcys.bilibilias.common.event.sendToastEvent
import com.imcys.bilibilias.common.utils.DeviceInfoUtils
import com.imcys.bilibilias.common.utils.openLink
import com.imcys.bilibilias.ui.home.navigation.HomeRoute
import com.imcys.bilibilias.ui.tools.frame.FrameExtractorRoute
import com.imcys.bilibilias.ui.tools.parser.WebParserRoute
import com.imcys.bilibilias.ui.widget.ASAlertDialog
import com.imcys.bilibilias.ui.widget.ASIconButton
import com.imcys.bilibilias.ui.widget.ASTextButton
import com.imcys.bilibilias.ui.widget.tip.ASInfoTip
import kotlinx.coroutines.launch

@Composable
fun ToolsScreen(vm: HomeViewModel, onToPage: (NavKey) -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 15.dp)
            .padding(top = 10.dp),
    ) {
        ToolsContent(vm, onToPage)
    }
}


enum class ToolInfo(
    val title: String,
    val desc: String,
    val icon: ImageVector? = null,
    val iconRes: Int? = null,
    val navKey: NavKey = HomeRoute(),
    val isScreen: Boolean = true,
) {
    // 逐帧提取
    FrameExtractor(
        title = "逐帧提取",
        desc = "从视频中逐帧提取图片，画手书的好帮手！",
        icon = Icons.Outlined.VideoCameraBack,
        navKey = FrameExtractorRoute
    ),
    WebParser(
        title = "网页解析",
        desc = "直接在网页找到你需要的视频，可自动解析视频。",
        icon = Icons.Outlined.WebAsset,
        navKey = WebParserRoute
    ),
    // 反馈问题
    Feedback(
        title = "反馈问题",
        desc = "🐞帮助我们改进程序，这对本项目的发展有重大意义！",
        icon = Icons.Outlined.BugReport,
        isScreen = false,
    ),
}

@Composable
private fun ToolsContent(vm: HomeViewModel, onToPage: (NavKey) -> Unit) {

    var showFeedbackDialog by remember { mutableStateOf(false) }

    val videoTools = listOf(
        ToolInfo.FrameExtractor
    )
    val parserTools = listOf(
        ToolInfo.WebParser
    )
    // 捐赠入口已移除：该页原本引导用户向原作者捐款，而原项目已停止维护，
    // 保留在个人自用构建里没有意义，也容易造成误解。
    val otherTools = listOf(
        ToolInfo.Feedback
    )

    // 点击工具处理
    fun clickTool(toolInfo: ToolInfo) {
        vm.updateUseToolRecord(toolInfo)
        when (toolInfo) {
            ToolInfo.Feedback -> {
                showFeedbackDialog = true
            }
            else -> {
                onToPage.invoke(toolInfo.navKey)
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 180.dp),
        modifier = Modifier.padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(
            span = { GridItemSpan(maxLineSpan) }
        ) {
            Text(stringResource(R.string.tools_video_processing))
        }
        items(videoTools) {
            ToolCard(it, onClick = {
                clickTool(it)
            })
        }

        item(
            span = { GridItemSpan(maxLineSpan) }
        ) {
            Text(stringResource(R.string.tools_parser_tools))
        }
        items(parserTools) {
            ToolCard(it, onClick = {
                clickTool(it)
            })
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(stringResource(R.string.tools_other))
        }

        items(otherTools) {
            ToolCard(it, onClick = {
                clickTool(it)
            })
        }

    }

    FeedbackDialog(showFeedbackDialog, onDismiss = {
        showFeedbackDialog = false
    })

}

@Composable
fun FeedbackDialog(showFeedbackDialog: Boolean, onDismiss: () -> Unit) {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    ASAlertDialog(
        showState = showFeedbackDialog,
        title = {
            Text(stringResource(R.string.tools_feedback))
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {

                Spacer(Modifier)

                ASInfoTip {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "反馈时需要带上你的设备信息，点击可一键复制。",
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        ASIconButton(onClick = {
                            scope.launch {
                                val copyText = DeviceInfoUtils.getDeviceInfoCopyString(context)
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("版本信息", copyText)
                                clipboard.setPrimaryClip(clip)
                             sendToastEvent("已复制到剪贴板")
                            }
                        }) {
                            Icon(Icons.Outlined.CopyAll, contentDescription = "复制按钮")
                        }
                    }
                }

                BadgedBox(badge = {
                    Badge { Text(stringResource(R.string.common_recommend)) }
                }) {
                    Surface(
                        shape = CardDefaults.shape,
                        onClick = {
                            context.openLink("https://github.com/whyD9527/BDT/issues")
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_github_24px),
                                contentDescription = "图标",
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "前往Github反馈，由开发者和社区贡献者处理你的问题。",
                            )
                        }
                    }
                }

                // 「前往 QQ 频道反馈」「二次元爱好者交流群」两个入口已移除：
                // 它们指向的是原作者自建的 QQ 频道与群，与个人自用构建无关。
                // 反馈入口只保留指向本仓库的 GitHub issues。

            }
        },
        onDismiss = onDismiss,
        confirmButton = {
            ASTextButton(onClick = {
                onDismiss.invoke()
            }) {
                Text(stringResource(R.string.common_ok))
            }
        }

    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Preview
@Composable
private fun ToolCard(
    toolInfo: ToolInfo = ToolInfo.Feedback,
    onClick: () -> Unit = { }
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = CardDefaults.shape, onClick = onClick) {
        Column(
            Modifier
                .padding(10.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialShapes.Circle.toShape()
            ) {
                toolInfo.icon?.let {
                    Icon(
                        it,
                        contentDescription = "图标",
                        modifier = Modifier
                            .padding(8.dp)
                            .size(22.dp)
                    )
                } ?: run {
                    Icon(
                        painter = painterResource(toolInfo.iconRes!!),
                        contentDescription = "图标",
                        modifier = Modifier
                            .padding(8.dp)
                            .size(22.dp)
                    )
                }

            }
            Spacer(Modifier.height(2.dp))
            Text(toolInfo.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                toolInfo.desc,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                minLines = 2,
            )
        }
    }
}