package com.imcys.bilibilias.ui.setting.platform

import androidx.compose.ui.res.stringResource
import com.imcys.bilibilias.R
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.imcys.bilibilias.data.repository.getDescription
import com.imcys.bilibilias.database.entity.BILIUsersEntity
import com.imcys.bilibilias.datastore.AppSettings
import com.imcys.bilibilias.ui.widget.ASAsyncImage
import com.imcys.bilibilias.ui.widget.ASTopAppBar
import com.imcys.bilibilias.ui.widget.AsBackIconButton
import com.imcys.bilibilias.ui.widget.BILIBILIASTopAppBarStyle
import com.imcys.bilibilias.ui.widget.TipSettingsItem
import com.imcys.bilibilias.ui.widget.tip.ASInfoTip
import com.imcys.bilibilias.widget.ASCommonLoadingScreen
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Locale

@Serializable
object ParsePlatformRoute : NavKey

@Composable
fun ParsePlatformScreen(parsePlatformRoute: ParsePlatformRoute, onToBack: () -> Unit) {
    ParsePlatformScreenScaffold(onToBack = onToBack) {
        ParsePlatformContent(Modifier.padding(it))
    }
}


@Composable
private fun ParsePlatformContent(modifier: Modifier = Modifier) {
    val vm = koinViewModel<ParsePlatformViewModel>()
    val uiState by vm.uiState.collectAsState()
    val useParsePlatform by vm.useParsePlatform.collectAsState()

    AnimatedContent(uiState) { state ->
        when (state) {
            is ParsePlatformViewModel.ParsePlatformUIState.AccountSelect -> {
                ParsePlatformAccountSelect(
                    modifier = modifier, state, onSelectAccount = vm::selectAccount
                )
            }

            ParsePlatformViewModel.ParsePlatformUIState.ChangeLoading -> {
                ASCommonLoadingScreen(R.string.parse_switching_account)
            }

            is ParsePlatformViewModel.ParsePlatformUIState.Default -> {
                ParsePlatformDefaultContent(
                    modifier = modifier, useParsePlatform,
                    onUpdateSelectPlatform = vm::updateSelectPlatform
                )
            }

            ParsePlatformViewModel.ParsePlatformUIState.EffectiveCheckLoading -> {
                ASCommonLoadingScreen(R.string.parse_checking_account)
            }
        }
    }
}

@Composable
private fun ParsePlatformAccountSelect(
    modifier: Modifier,
    uiState: ParsePlatformViewModel.ParsePlatformUIState.AccountSelect,
    onSelectAccount: (BILIUsersEntity) -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .selectableGroup()
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ASInfoTip(Modifier.padding(horizontal = 10.dp)) {
            Text(
                stringResource(R.string.parse_multi_account_hint)
            )
        }
        uiState.accountList.forEach { user ->
            PlatformAccountCard(user, onClick = {
                onSelectAccount(user)
            })
        }
    }
}


@Composable
private fun PlatformAccountCard(user: BILIUsersEntity, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = CardDefaults.shape,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像
            ASAsyncImage(
                model = user.face,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "登录于 ${
                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            .format(user.createdAt)
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ParsePlatformDefaultContent(
    modifier: Modifier,
    usePlatform: AppSettings.VideoParsePlatform,
    onUpdateSelectPlatform: (AppSettings.VideoParsePlatform) -> Unit
) {
    val parsePlatformList by remember(AppSettings.VideoParsePlatform.entries) {
        mutableStateOf(
            listOf(
                AppSettings.VideoParsePlatform.Web,
                AppSettings.VideoParsePlatform.TV
            )
        )
    }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .selectableGroup()
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        parsePlatformList.forEach { platform ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 10.dp)
                    .selectable(
                        selected = usePlatform == platform,
                        onClick = {
                            onUpdateSelectPlatform(platform)
                        },
                        role = Role.RadioButton
                    )
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = usePlatform == platform,
                    onClick = null
                )
                Text(
                    text = platform.getDescription(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        }
        ParsePlatformDescription()
    }
}

@Composable
private fun ParsePlatformDescription() {
    TipSettingsItem(
        stringResource(R.string.parse_platform_description)
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParsePlatformScreenScaffold(
    onToBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            Column {
                ASTopAppBar(
                    style = BILIBILIASTopAppBarStyle.Small,
                    title = {
                        Text(text = stringResource(R.string.setting_parse_platform))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    navigationIcon = {
                        AsBackIconButton(onClick = {
                            onToBack.invoke()
                        })
                    },
                    alwaysDisplay = false
                )
            }
        },
    ) {
        content.invoke(it)
    }


}
