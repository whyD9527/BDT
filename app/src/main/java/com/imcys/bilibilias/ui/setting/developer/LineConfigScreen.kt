package com.imcys.bilibilias.ui.setting.developer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.imcys.bilibilias.R
import com.imcys.bilibilias.ui.utils.rememberWidthSizeClass
import com.imcys.bilibilias.ui.widget.ASIconButton
import com.imcys.bilibilias.ui.widget.ASTextButton
import com.imcys.bilibilias.ui.widget.ASTopAppBar
import com.imcys.bilibilias.ui.widget.AsBackIconButton
import com.imcys.bilibilias.ui.widget.BILIBILIASTopAppBarStyle
import com.imcys.bilibilias.ui.widget.TipSettingsItem
import com.imcys.bilibilias.ui.widget.shimmer.shimmer
import com.imcys.bilibilias.ui.widget.tip.ASInfoTip
import com.imcys.bilibilias.ui.widget.tip.ASWarringTip
import com.imcys.bilibilias.widget.maybeNestedScroll
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel


@Serializable
data object LineConfigRoute : NavKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineConfigScreen(lineConfigRoute: LineConfigRoute, onToBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    LineConfigScaffold(scrollBehavior = scrollBehavior, onToBack = onToBack) { paddingValues ->
        LineConfigContent(
            modifier = Modifier
                .padding(paddingValues)
                .maybeNestedScroll(scrollBehavior)
        )
    }
}

@Composable
fun LineConfigContent(modifier: Modifier) {
    val vm = koinViewModel<LineConfigViewModel>()
    val uiState by vm.uiState.collectAsState()
    val biliLineHostListState by vm.biliLineHostListState.collectAsState()
    val widthSizeClass = rememberWidthSizeClass()

    LaunchedEffect(Unit) {
        vm.loadLineConfig()
    }

    val columns = when (widthSizeClass) {
        WindowWidthSizeClass.Compact -> 1
        WindowWidthSizeClass.Medium, WindowWidthSizeClass.Expanded -> 2
        else -> 1
    }
    LazyVerticalGrid(
        modifier = modifier
            .padding(vertical = 10.dp, horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        columns = GridCells.Fixed(columns)
    ) {

        item(
            span = { GridItemSpan(columns) }
        ) {
            ASInfoTip {
                Text(
                    stringResource(R.string.line_config_info)
                )
            }
        }


        item(span = { GridItemSpan(columns) }) {
            AnimatedVisibility(visible = uiState.currentLineHost.isNotEmpty()) {
                ASWarringTip(
                    modifier = Modifier.animateItem()
                ) {
                    Text(
                        stringResource(R.string.line_config_warning)
                    )
                }
            }
        }

        item(span = { GridItemSpan(columns) }) {
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                ASIconButton(onClick = {
                    vm.startSpeedTest()
                }) {
                    Icon(Icons.Outlined.Speed, contentDescription = stringResource(R.string.cd_icon))
                }
            }
        }

        // L2（大加强）：把测速结果变成**结论** —— 用纯规则挑出最快的那条（忽略未测速的 ✓）
        val fastestHost = com.imcys.bilibilias.data.diagnostics.LineSpeedRules
            .fastestHost(biliLineHostListState.map { it.host to it.speed })

        if (fastestHost != null && fastestHost != uiState.currentLineHost) {
            item {
                ASTextButton(onClick = { vm.updateLineHost(fastestHost) }) {
                    Text(stringResource(R.string.line_pick_fastest))
                }
            }
        }

        biliLineHostListState.forEach {
            item {
                LineHostCard(uiState, it, vm, isFastest = it.host == fastestHost)
            }
        }

        item {
            TipSettingsItem(
                modifier =  Modifier.animateItem(),
                text =
                stringResource(R.string.line_config_cdn_tip)
            )
        }
    }
}

@Composable
private fun LineHostCard(
    uiState: LineConfigUIState,
    item: BILILineHostItem,
    vm: LineConfigViewModel,
    /** L2：这条是不是「测速最快」的那条（由列表层用 LineSpeedRules 算好传进来） */
    isFastest: Boolean = false,
) {
    val colorState by animateColorAsState(
        targetValue = if (uiState.currentLineHost == item.host)
            MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface
    )
    Surface(
        shape = CardDefaults.shape,
        // 选中颜色
        color = colorState,
        onClick = {
            vm.updateLineHost(item.host)
        }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // L5：默认线路那条的名称（数据层里是中文名字）在展示层走资源，便于中英切换；
            //     品牌名（ali（阿里）等）仍用 item.name，**不翻译**
            Text(
                if (item.host.isEmpty()) stringResource(R.string.line_default) else item.name,
                modifier = Modifier.weight(1f),
            )
            // L1（大加强）：选中态此前**只有背景色**，真机上无障碍也读不到 ✗ —— 现在补一个明确的
            // 「当前使用」文本标记：用户一眼可见，自动化/读屏也能确认✅
            if (uiState.currentLineHost == item.host) {
                Text(
                    stringResource(R.string.line_current_in_use),
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            // L2：测速最快的那条给个「最快」标记（未测速的不参与比较，不会误标 ✓）
            if (isFastest) {
                Text(
                    stringResource(R.string.line_fastest_tag),
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }

            ASTextButton(
                enabled = !item.checkSpeeding,
                modifier = Modifier
                    .shimmer(item.checkSpeeding)
                    .then(if (item.host.isNotEmpty()) Modifier else Modifier.alpha(0f)),
                onClick = {
                    vm.startSpeedTest(item)
                }) {
                if (item.speed != null) {
                    Text("${item.speed}")
                } else {
                    Text(stringResource(R.string.line_config_detect))
                }

            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineConfigScaffold(
    scrollBehavior: TopAppBarScrollBehavior,
    onToBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            ASTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                scrollBehavior = scrollBehavior,
                style = BILIBILIASTopAppBarStyle.Large,
                title = {
                    BadgedBox(
                        badge = {
                            Badge {
                                Text(stringResource(R.string.common_beta))
                            }
                        }
                    ) {
                        Text(text = stringResource(R.string.developer_line_config))
                    }
                },
                navigationIcon = {
                    AsBackIconButton(onClick = {
                        onToBack.invoke()
                    })
                },
                alwaysDisplay = false
            )
        },
    ) {
        content(it)
    }

}