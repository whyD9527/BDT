package com.imcys.bilibilias.ui.login

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.imcys.bilibilias.R
import com.imcys.bilibilias.ui.login.navigation.LoginRoute
import com.imcys.bilibilias.ui.login.navigation.LoginTab
import com.imcys.bilibilias.ui.widget.ASIconButton
import com.imcys.bilibilias.ui.widget.ASTopAppBar
import com.imcys.bilibilias.ui.widget.AsBackIconButton
import com.imcys.bilibilias.ui.widget.BILIBILIASTopAppBarStyle
import org.koin.androidx.compose.koinViewModel

/**
 * 合并后的「登录」页（#5）：**扫码 / Cookie 两个 Tab 在同一个页面**。
 *
 * 2026-10-05 合并前是三个路由：`LoginRoute`（一页介绍 + 一个"扫码登录"按钮）
 * → `QRCodeLoginRoute`（右上角菜单里再跳 `CookeLoginRoute`）。
 * 用户输个 Cookie 要点两次、还得到菜单里找；现在一个页面两个 Tab。
 *
 * 原来介绍页上的"登录后可用功能 / 协议 / 特别说明"没有丢，收进了右上角的 ⓘ
 *（界面原则：主句短，长解释收进 ⓘ）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    route: LoginRoute,
    onToBack: () -> Unit,
    onBackHomePage: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(route.initialTab) }
    var showIntroDialog by remember { mutableStateOf(false) }
    val cookieVm = koinViewModel<CookieLoginViewModel>()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 登录成功后的去路（与合并前三个回调一致）：从漫游/解析页进来的原路返回，否则回首页
    val onLoginSuccess: () -> Unit = {
        if (route.isFromRoam || route.isFromAnalysis) onToBack() else onBackHomePage()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            Column {
                ASTopAppBar(
                    style = BILIBILIASTopAppBarStyle.Small,
                    title = {
                        Text(stringResource(R.string.login_title))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    navigationIcon = {
                        AsBackIconButton { onToBack.invoke() }
                    },
                    actions = {
                        ASIconButton(onClick = { showIntroDialog = true }) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = stringResource(R.string.login_intro_title)
                            )
                        }
                    }
                )
                TabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Tab(
                        selected = selectedTab == LoginTab.QR_CODE,
                        onClick = { selectedTab = LoginTab.QR_CODE },
                        text = { Text(stringResource(R.string.login_scan_qrcode)) },
                    )
                    Tab(
                        selected = selectedTab == LoginTab.COOKIE,
                        onClick = { selectedTab = LoginTab.COOKIE },
                        text = { Text(stringResource(R.string.login_use_cookie)) },
                    )
                }
            }
        },
    ) { paddingValues ->
        Box(Modifier.padding(paddingValues)) {
            when (selectedTab) {
                LoginTab.QR_CODE -> QRCodeLoginTabContent(
                    route = route,
                    onBackHomePage = onLoginSuccess,
                )

                LoginTab.COOKIE -> CookeLoginContent(
                    vm = cookieVm,
                    // 顶栏/Tab 已经把系统栏让出来了，内容不需要再吃一次 padding
                    paddingValues = PaddingValues(0.dp),
                    onFinish = onLoginSuccess,
                )
            }
        }
    }

    if (showIntroDialog) {
        AlertDialog(
            onDismissRequest = { showIntroDialog = false },
            title = { Text(stringResource(R.string.login_intro_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.login_after_you_can))
                    Text(stringResource(R.string.login_benefits))
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.login_agreement_prefix))
                    Text(
                        stringResource(R.string.login_bilibili_agreement),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.login_privacy_agreement),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.login_disclaimer),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.login_special_notice))
                }
            },
            confirmButton = {
                TextButton(onClick = { showIntroDialog = false }) {
                    Text(stringResource(R.string.cd_close))
                }
            },
        )
    }
}
