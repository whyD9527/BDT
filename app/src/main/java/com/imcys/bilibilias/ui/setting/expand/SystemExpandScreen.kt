package com.imcys.bilibilias.ui.setting.expand

import kotlinx.serialization.Serializable
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.navigation3.runtime.NavKey
import com.imcys.bilibilias.R
import com.imcys.bilibilias.ui.widget.ASTopAppBar
import com.imcys.bilibilias.ui.widget.AsBackIconButton
import com.imcys.bilibilias.ui.widget.BILIBILIASTopAppBarStyle
import com.imcys.bilibilias.ui.widget.CategorySettingsItem
import com.imcys.bilibilias.ui.widget.SwitchSettingsItem


// ⚠️ 必须 @Serializable：Navigation3 在 Activity 状态保存（后台化）时会序列化路由，
// 缺了它就会 `Serializer for class '…' is not found` 崩在 onSaveInstanceState（真机 crash.log 已复现）
@Serializable
data object SystemExpandRoute : NavKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemExpandScreen(systemExpandRoute: SystemExpandRoute, onToBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    SystemExpandScaffold(
        scrollBehavior = scrollBehavior,
        onToBack = onToBack
    ) { paddingValues ->
        SystemExpandContent(scrollBehavior, paddingValues)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SystemExpandContent(
    scrollBehavior: TopAppBarScrollBehavior,
    paddingValues: PaddingValues
) {
    LazyColumn(
        modifier = Modifier
            .padding(paddingValues)
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
    ) {
        item {
            CategorySettingsItem(
                text = stringResource(R.string.expand_permission_title)
            )
        }

        item {
            SwitchSettingsItem(
                painter = painterResource(R.drawable.ic_shizuku_logo_512px),
                text = "Shizuku",
                description = stringResource(R.string.expand_permission_desc),
                checked = false,
                isImage = true
            ) { check ->

            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SystemExpandScaffold(
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
                title = { Text(text = stringResource(R.string.expand_capability_title)) },
                navigationIcon = {
                    AsBackIconButton(onClick = {
                        onToBack.invoke()
                    })
                }
            )
        },
    ) {
        content(it)
    }

}