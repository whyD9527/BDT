package com.imcys.bilibilias.ui.setting.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import com.imcys.bilibilias.R
import com.imcys.bilibilias.ui.widget.ASTopAppBar
import com.imcys.bilibilias.ui.widget.AsBackIconButton
import com.imcys.bilibilias.ui.widget.BILIBILIASTopAppBarStyle
import com.imcys.bilibilias.widget.maybeNestedScroll
import kotlinx.serialization.Serializable

@Serializable
data object AboutRouter : NavKey

@Preview
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(aboutRouter: AboutRouter = AboutRouter, onToBack: () -> Unit = {}) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

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
                title = { Text(text = "关于") },
                navigationIcon = {
                    AsBackIconButton { onToBack.invoke() }
                },
                alwaysDisplay = false
            )
        },
    ) { paddingValues ->
        AboutContent(
            modifier = Modifier.maybeNestedScroll(scrollBehavior),
            paddingValues = paddingValues,
        )
    }

}

@Composable
fun AboutContent(
    modifier: Modifier = Modifier,
    paddingValues: PaddingValues,
) {
    LazyColumn(
        modifier = modifier
            .padding(paddingValues)
            .fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            IconArea()
        }
        item {
            Spacer(Modifier.height(10.dp))
            TitleArea()
        }
    }
}

@Composable
fun TitleArea(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            text = stringResource(id = R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "一款简单的视频缓存工具",
            textAlign = TextAlign.Center,
            fontSize = 17.sp
        )

        Card(
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    modifier = Modifier.padding(top = 12.dp),
                    text = """
                        本应用基于开源项目 BILIBILIAS 的源码二次开发，仅供个人自用。

                        请尊重每一位创作者的劳动成果，缓存内容不得二次传播；
                        请勿将本软件用于任何商业用途，一切后果自负。

                        原项目 BILIBILIAS 由原作者开发，现已停止维护。
                        本构建所做的改动（换皮、隐私加固、下载修复等）均与原项目及其作者无关。
                    """.trimIndent(),
                )
            }
        }
    }
}

@Composable
fun IconArea() {
    Icon(
        modifier = Modifier
            .padding(4.dp)
            .size(60.dp),
        painter = painterResource(id = R.drawable.ic_logo_mini),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
    )
}
