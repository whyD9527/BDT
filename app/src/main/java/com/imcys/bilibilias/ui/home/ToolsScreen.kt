package com.imcys.bilibilias.ui.home

import com.imcys.bilibilias.ui.setting.feedback.FeedbackRoute
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.VideoCameraBack
import androidx.compose.material.icons.outlined.WebAsset
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.imcys.bilibilias.R
import com.imcys.bilibilias.ui.home.navigation.HomeRoute
import com.imcys.bilibilias.ui.tools.frame.FrameExtractorRoute
import com.imcys.bilibilias.ui.tools.parser.WebParserRoute

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
    /**
     * ⚠️ 存**资源 id** 而不是文案（i18n 批次 E5）：enum 里拿不到 Context/Composable，
     * 写死中文就永远没法本地化。
     *
     * 注意枚举的 **`name`（`FrameExtractor`/`WebParser`/`Feedback`）是"工具历史"持久化用的键**
     * （`AppSettingsSerializer` 存的就是它），所以只改字段、**绝不能改枚举名**。
     */
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val icon: ImageVector? = null,
    val iconRes: Int? = null,
    val navKey: NavKey = HomeRoute(),
    val isScreen: Boolean = true,
) {
    // 逐帧提取
    FrameExtractor(
        titleRes = R.string.tools_frame_extractor_title,
        descRes = R.string.tools_frame_extractor_desc,
        icon = Icons.Outlined.VideoCameraBack,
        navKey = FrameExtractorRoute
    ),
    WebParser(
        titleRes = R.string.tools_web_parser_title,
        descRes = R.string.tools_web_parser_desc,
        icon = Icons.Outlined.WebAsset,
        navKey = WebParserRoute
    ),
    // 反馈问题
    Feedback(
        titleRes = R.string.tools_feedback_title,
        descRes = R.string.tools_feedback_desc,
        icon = Icons.Outlined.BugReport,
        isScreen = false,
    ),
}

@Composable
private fun ToolsContent(vm: HomeViewModel, onToPage: (NavKey) -> Unit) {


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
                // 合并（2026-10-02 用户反馈"入口太散"）：这里原来弹的是**又一份自己实现的反馈对话框**
                // （复制设备信息 + 打开 Issues），与存储管理/版本页重复 ✗ —— 现在直接跳转唯一的
                // 「问题反馈」诊断中心（状态自检 + 设备信息 + 诊断日志 + 一键导出反馈包 + Issue）。
                onToPage(FeedbackRoute)
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
                        contentDescription = stringResource(R.string.cd_icon),
                        modifier = Modifier
                            .padding(8.dp)
                            .size(22.dp)
                    )
                } ?: run {
                    Icon(
                        painter = painterResource(toolInfo.iconRes!!),
                        contentDescription = stringResource(R.string.cd_icon),
                        modifier = Modifier
                            .padding(8.dp)
                            .size(22.dp)
                    )
                }

            }
            Spacer(Modifier.height(2.dp))
            Text(stringResource(toolInfo.titleRes), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(toolInfo.descRes),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                minLines = 2,
            )
        }
    }
}